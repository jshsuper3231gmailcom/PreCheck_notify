# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 저장소의 코드를 다룰 때 참고할 가이드를 제공한다.

## 프로젝트 상태

구현 완료 상태(2026-06-19 확인: `./gradlew test --rerun-tasks`로 재실행, 44개 테스트·10개 클래스 전부 통과, 실패/에러 0). `src/main/java/com/sks/precheck/notify/` 아래에 `scheduler`/`service`/`parser`/`tr`/`mapper`/`domain`/`vo`/`common`/`config` 패키지가 모두 구현되어 있고, `src/main/resources/`에는 `application.yaml`/`application-test.yaml`/`application-prod.yaml`과 MyBatis XML 매퍼(`mapper/*.xml`)도 존재함. 컨트롤러 패키지만 없는데, `notify`는 HTTP 포트를 열지 않는 헤드리스 `@Scheduled` 배치 서버라 원래부터 의도된 것임(REST API 자체가 없음).

지금 코드가 실제로 어떻게 동작하는지는 `FLOW.md`(개발자 참고 문서, 클래스/함수 단위까지 상세)를 먼저 볼 것. 왜 이렇게 설계했는지는 아래 "프로젝트 명세" 섹션과 거기서 링크하는 `docs/` 문서들을 볼 것. 이 모듈을 더 손볼 때는 새 구조를 만들지 말고 기존 패키지 구조와 형제 `precheck_collect/*` 서비스들의 컨벤션을 따를 것.

## 프로젝트 명세

`notify`의 설계는 확정됨(2026-06-18에 `/grill-me` 인터뷰로 결정, `docs/통보서버_PRD.md`에 기록됨), 구현도 이 설계 그대로 끝나 있음(2026-06-19 검증: 코드/테스트가 아래 결정들과 정확히 일치). PRD는 참고/히스토리 용도일 뿐이고, 이 섹션을 현재 기준 압축 명세로, 아래에 링크된 문서들을 정식 상세 스펙으로 취급할 것. 설계 결정이 바뀌면 이 섹션(과 관련 문서, 그리고 실제 코드)을 갱신할 것, PRD는 그대로 둘 것.

**문제**: `collect` → `analyze`가 이미 `TB_ANALYZE_RESULT`에 줄 단위 정상/경고/에러 판정을 채워넣고 있지만, 그걸 밖으로 알려주는 채널이 없음 — 운영자는 `Dashboard`를 직접 열어봐야만 알 수 있음. 1차 명세서(`docs/1__프로그램_명세서_20260514.md`)는 `notify`가 레거시 SMS 테이블에 INSERT하는 방식으로 정의했었지만, 레거시 게이트웨이가 실제로는 TR/TCP 소켓 인터페이스만 제공한다는 게 확인되어, PRD 인터뷰 중 이 방식을 버리고 `notify`가 레거시 TR 프로토콜을 직접 다루는 쪽으로 결정함.

**해결 방향**:
- `notify`는 자체 스케쥴 conf(`PreCheck_NotifyLogs_Schedule.conf`, `collect`/`analyze`와 동일한 `[주기|요일|시작|간격|종료]` 포맷)에 따라 깨어나고, `SERVER_ID`별로 마지막 성공 워터마크 이후의 `TB_ANALYZE_RESULT` 행을 집계함.
- 집계 구간에서 경고+에러 합이 1건 이상인 서버만 통보 대상이며, SMS 본문은 `[서버명] 정상N 경고N 에러N (HH:mm~HH:mm)`.
- 한 스케쥴 실행당 TCP 커넥션 1개를 모든 서버가 공유함: `bind(01)` 1회 후 수신자마다 `submit(03)` 1회(수신자는 DB가 아니라 평문 파일 `PreCheck_NotifyTarget_List.conf`에서 읽음).
- 레거시 게이트웨이는 ack을 안 줌 — 성공 여부는 오직 TCP write 성공 여부로만 판정(fire-and-forget). 모든 시도(성공/부분성공/실패)는 신규 테이블 `TB_NOTIFY_HISTORY`에 서버 단위로 즉시 기록되고, 이 테이블이 다음 실행을 위한 워터마크(`AGGREGATE_TO`)도 함께 들고 있음.
- `TB_ANALYZE_RESULT.NOTIFY_YN`과 `TB_NOTIFY_HISTORY.AGGREGATE_TO`는 역할이 분리됨: 집계 대상을 거를 때는(읽기) `NOTIFY_YN` 필터를 안 쓰고 워터마크 시간구간만 씀 — 정상 건수까지 정확히 세려면 레벨 무관하게 시간구간으로만 걸러야 하기 때문. 통보가 끝난 뒤(쓰기)는 이번 집계 구간에 포함된 행 전부(정상/경고/에러)를 `NOTIFY_YN='Y'`로 갱신함(Dashboard 🔔 아이콘 표시용, `AnalyzeResultMapper.updateNotifyYn`) — `NOTIFY_STATUS`가 FAIL이면 이 UPDATE 자체를 실행 안 함.
- 단일 스레드로 동작함(`@Scheduled`만 쓰고 `@Async`/`@Retryable` 없음) — `collect`/`analyze`의 비동기+재시도 모델과 의도적으로 다름, 워터마크/`NOTIFY_YN` 갱신이 절대 경합하면 안 되기 때문.

**상세 정의서** (해당 영역 코드 만지기 전에 관련 문서부터 읽을 것):
- `docs/통보_DB정의서.md` — `TB_NOTIFY_HISTORY` DDL, 시퀀스, 인덱스, 4가지 표준 쿼리 패턴, crash-safety/워터마크 로직 전체.
- `docs/통보_스케쥴러정의서.md` — 스케쥴 conf + 수신자 파일 포맷, 중복실행 방지, 다음 스케쥴에 재시도하는 동작.
- `docs/통보_TR연동정의서.md` — `BISUB_HEADER`/`SMSSUBMIT_BODY` 바이트 레이아웃, 필드값, 소켓 라이프사이클, 아직 미확인인 부분(bind 바디 내용, 실제 ack 페이로드, 운영 host:port).
- `docs/5__로그분석_DB정의서.md` — `analyze`의 `TB_ANALYZE_RESULT`/`NOTIFY_YN` DB 정의. `notify`가 이 테이블을 직접 읽기 때문에 참고용으로 보관.
- `docs/통보서버_PRD.md` — 전체 PRD: 문제 정의, 모든 User Story, 위 결정들 각각의 근거. 참고용.
- `docs/1__프로그램_명세서_20260514.md` — 원래 1차, 시스템 전체 명세서. `notify` 섹션은 위의 TR 소켓 피벗으로 일부 대체됨(문서 자체 각주 참고). 다른 모듈 관련 컨텍스트 용도로 보관.
- `docs/TaskCreate_결과.md` — `/TaskCreate` 결과물: PRD에서 도출한 13개 구현 작업 목록. 명세가 아니라 작업 체크리스트.

## 전체 시스템에서의 위치

이 모듈은 `precheck_collect/` 아래의 형제 Spring Boot 서비스들과 함께 있음: `collect`, `analyze`, `dashboard`, `notify`, `test_dataset`. 각각 독립된 Gradle 프로젝트이고(멀티 모듈 빌드 아님), group id `com.sks.precheck`와 공용 DB를 공유함. 형제 서비스들 자체 문서(`collect/FLOW.md`, `analyze/FLOW.md`) 기준:

- `collect`는 스케쥴 파일을 폴링하고, 대상 서버에서 SFTP로 로그를 가져와 정규화된 포맷으로 파싱한 뒤 `TB_COLLECT_LOG`에 INSERT함.
- `analyze`는 `TB_COLLECT_LOG`를 읽어 정책 파일에 따라 판정하고, 결과를 `TB_ANALYZE_RESULT` / `TB_ANALYZE_HISTORY`에 기록함.
- `dashboard`는 analyze 테이블들을 읽어서 에러/경고/정상 상태를 시각화함.
- `notify`(이 모듈)는 구현이 끝난 상태로, `SERVER_ID`별로 자체 스케쥴에 따라 `TB_ANALYZE_RESULT`를 집계하고, 집계 구간에 경고/에러가 1건 이상인 서버에 대해 레거시 게이트웨이의 TR/TCP 소켓 인터페이스로 요약 SMS를 전송함(상세 동작은 `FLOW.md`, 설계 배경은 위 "프로젝트 명세" 참고).

`collect`와 `analyze`는 둘 다 헤드리스 배치/스케쥴러 서비스(`@Scheduled` + `@Async` + `@Retryable`, HTTP 포트 없음)이고, MyBatis XML 매퍼, 로컬/테스트는 PostgreSQL, 운영은 Altibase를 사용함. `notify`도 헤드리스 `@Scheduled` 서비스이고 MyBatis 매퍼 레이어를 쓰는 건 같지만, REST 컨트롤러는 없음 — 다만 형제 서비스들과 달리 의도적으로 단일 스레드이며 `@Async`/`@Retryable`을 쓰지 않음(이유는 위 "프로젝트 명세" 참고).

## 명령어

이 프로젝트는 Gradle wrapper를 씀(Gradle 9.5.1, Java 17 toolchain). Windows에서는 `gradlew.bat` 사용; 아래 예시는 POSIX 형태(`./gradlew`)로 적혀있음 — PowerShell/cmd에서 직접 실행할 땐 `gradlew.bat`로 바꿔서 쓸 것.

```bash
# 빌드 (컴파일 + 테스트 실행)
./gradlew build

# 전체 테스트 실행
./gradlew test

# 특정 테스트 클래스만 실행
./gradlew test --tests "com.sks.precheck.notify.NotifyApplicationTests"

# 특정 테스트 메서드만 실행
./gradlew test --tests "com.sks.precheck.notify.NotifyApplicationTests.contextLoads"

# 앱 실행
./gradlew bootRun
```

`application-test.yaml`(PostgreSQL, 기본 활성 프로파일)과 `application-prod.yaml`(Altibase)이 이미 존재함. 운영 DB로 전환하려면 `--spring.profiles.active=prod`를 넘길 것. (`application-local.yaml`은 `collect`/`analyze`처럼 별도로 두지 않음 — 로컬 개발은 기본 `test` 프로파일을 그대로 사용)

## 스택 관련 참고사항

- Java 17, Spring Boot 4.0.7, MyBatis Spring Boot starter 4.0.1, 형제 서비스들처럼 XML 매퍼 적용됨(`src/main/resources/mapper/AnalyzeResultMapper.xml`, `NotifyHistoryMapper.xml`).
- `build.gradle`의 runtime 의존성은 `docs/통보_DB정의서.md` §2가 명시한 PostgreSQL/Altibase 호환 원칙대로 정리되어 있음 — `runtimeOnly 'org.postgresql:postgresql'`(test 프로파일) + `runtimeOnly 'com.altibase:altibase-jdbc:8.1.0.0.1'`(prod 프로파일). 예전에 있던 `ojdbc11`(Oracle) 의존성은 스캐폴드 잔재였던 게 맞았고, 지금은 빠져 있음.
- Lombok 사용 가능(`compileOnly` + annotation processor).
- 로컬 개발용 Spring Boot DevTools 활성화됨.
