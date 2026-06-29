# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 저장소의 코드를 다룰 때 참고할 가이드를 제공한다.

## 프로젝트 상태

구현 완료(2026-06-19 검증, `gradlew test --rerun-tasks` 44개 테스트 전부 통과). HTTP 포트 없는 헤드리스 `@Scheduled` 배치 서버. 코드 동작은 `FLOW.md`, 설계 근거는 `docs/` 참조.

## 역할 및 핵심 설계

`analyze`가 채운 `TB_ANALYZE_RESULT`를 워터마크 기준으로 집계하여 레거시 SMS 게이트웨이(TR/TCP 소켓)로 통보.

**핵심 결정:**
- 스케줄당 TCP 커넥션 1개 공유: `bind(01)` 1회 → 수신자마다 `submit(03)` 1회 (fire-and-forget)
- 집계는 `NOTIFY_YN` 필터 없이 `TB_NOTIFY_HISTORY.AGGREGATE_TO` 워터마크 시간구간만 사용 — 정상 건수까지 포함해야 하기 때문
- 통보 성공 후 해당 구간 전체 행 `NOTIFY_YN='Y'` 갱신 (Dashboard 알림 아이콘 표시용). `NOTIFY_STATUS=FAIL`이면 이 UPDATE 실행 안 함
- **단일 스레드** (`@Async`/`@Retryable` 없음) — 워터마크·`NOTIFY_YN` 갱신 경합 방지. `collect`/`analyze`의 비동기 모델과 의도적으로 다름
- TCP write 성공이 유일한 성공 기준 (레거시 게이트웨이 ack 없음)
- 수신자 목록: `PreCheck_NotifyTarget_List.conf` (평문 파일, DB 아님)
- 스케줄 conf: `PreCheck_NotifyLogs_Schedule.conf` (`collect`/`analyze`와 동일 포맷)

**상세 정의서** (해당 코드 수정 전 먼저 읽을 것):
- `docs/통보_DB정의서.md` — `TB_NOTIFY_HISTORY` DDL, 워터마크/crash-safety 로직 전체
- `docs/통보_스케쥴러정의서.md` — 스케줄 conf + 수신자 파일 포맷, 중복 실행 방지
- `docs/통보_TR연동정의서.md` — `BISUB_HEADER`/`SMSSUBMIT_BODY` 바이트 레이아웃, 소켓 라이프사이클

---

## 명령어

Gradle wrapper 사용(Java 17). Windows:

```bash
# 빌드
gradlew.bat build

# 전체 테스트
gradlew.bat test

# 특정 테스트
gradlew.bat test --tests "com.sks.precheck.notify.NotifyApplicationTests"

# 앱 실행 (기본 프로파일: test → PostgreSQL)
gradlew.bat bootRun

# 운영 전환
gradlew.bat bootRun --args="--spring.profiles.active=prod"
```

`application-local.yaml` 없음 — 로컬 개발은 기본 `test` 프로파일 그대로 사용.

## 스택 참고

- Java 17, Spring Boot 4.0.7, MyBatis (XML 매퍼)
- PostgreSQL (test) / Altibase (prod)
- Lombok, Log4j2
