# precheck-notify 개발자 참고 문서

## 1. 프로젝트 개요

### 목적 및 역할

`precheck-notify`는 `precheck-analyze`가 적재한 `TB_ANALYZE_RESULT`를 주기적으로 읽어 경고/에러가 발생한 서버에 SMS 통보 TR을 전송하는 Spring Boot 배치 서버다. HTTP 서버를 구동하지 않고, `@Scheduled(fixedDelay=60000)` 폴링만으로 동작한다.

스케줄 파일(`PreCheck_NotifyLogs_Schedule.conf`)의 단일 라인(`[배치|...]` 또는 `[주기|...]`)을 60초마다 폴링하여 실행 시점이 되면, 분석 결과를 서버 단위로 집계하고, 경고+에러가 1건 이상인 서버에 대해 레거시 TR(Transaction) 바이너리 프로토콜로 SMS를 전송한다. 수신자(전화번호) 목록은 별도 conf 파일(`PreCheck_NotifyTarget_List.conf`)로 관리하며, TCP 연결은 스케줄 실행 1회당 1개만 열고(connectionless 방식), 통보 대상 서버 전체가 이 연결을 공유한다.

전송 결과는 `TB_NOTIFY_HISTORY`에 서버 단위로 즉시(crash-safe) 기록되며, 이 테이블의 `AGGREGATE_TO`가 다음 실행의 워터마크로 쓰인다.

### 기술 스택

| 항목 | 내용 |
|------|------|
| 런타임 | Java 17, Spring Boot 4.0.7 |
| 스케줄링 | `@Scheduled(fixedDelay=60000)` (단일 스레드, `@Async` 없음) |
| ORM | MyBatis Spring Boot Starter 4.0.1 (XML 매퍼) |
| DB | PostgreSQL (test 프로파일) / Altibase 8.1.0.0.1 (prod 프로파일) |
| 로깅 | Log4j2 (`spring-boot-starter-logging` 제외) |
| 기타 | Lombok(`compileOnly`), Spring Boot DevTools(`developmentOnly`) |
| 빌드 | Gradle 9.5.1 (Gradle Wrapper, `gradlew.bat`) |
| 외부 연동 | 레거시 SMS 게이트웨이와 TCP 소켓(non-HTTP) 직접 통신 — TR 바이너리 프로토콜 |

### 실행 방식

HTTP 포트 없음. JVM 프로세스로 기동하면 `NotifyScheduler`가 매 60초 스케줄 파일을 읽어 실행 시점을 판별하고, 매칭되면 `NotifyService.runNotify()`를 동기적으로(같은 스레드에서) 호출한다. 재시도/비동기 처리는 없다 — TR 전송 실패는 그 자리에서 FAIL/PARTIAL로 기록되고 다음 스케쥴 실행이 재시도 역할을 겸한다.

---

## 2. 데이터 흐름

### 전체 흐름도

```
[PreCheck_NotifyLogs_Schedule.conf]  ([배치|...] 또는 [주기|...] 단일 라인)
        │  (60초마다 폴링, 60초 캐시)
        ▼
[NotifyScheduler.run()] ── 슬롯 매칭 (POLL_WINDOW_SECONDS=60) ──┐
                                                                  │
        ┌─────────────────────────────────────────────────────┘
        ▼  (같은 스레드, 동기 호출)
[NotifyService.runNotify(schedule, aggregateTo)]
        │
        ├─① [NotifyAggregationService.aggregate()]
        │     TB_ANALYZE_RESULT의 SERVER_ID 목록 순회
        │     → 서버별 워터마크 조회 (TB_NOTIFY_HISTORY.AGGREGATE_TO, 없으면 기본값)
        │     → 정상/경고/에러 건수 집계
        │     → 경고+에러=0인 서버는 제외
        │     → 대상 없으면 여기서 종료 (TB_NOTIFY_HISTORY 행도 생성 안 함)
        │
        ├─② [NotifyTargetParser.parseTargetFile()] 수신대상(전화번호) 파일 읽기
        │     → 파일이 비어있으면: 소켓 연결 자체를 열지 않고 대상 서버 전부 SUCCESS(0건)로 즉시 기록,
        │       NOTIFY_YN 갱신 후 종료 ("수신자 파일 비우기 = 통보 끄기" 용도)
        │
        ├─③ [SmsTrSocketClientFactory.connect()] TCP 소켓 1개 연결 (스케줄 실행당 1개, 서버 전체 공유)
        │     → connect 실패 시: 대상 서버 전원 FAIL(0건)로 기록, 종료
        │
        ├─④ [SmsTrSocketClient.sendBind()] bind(01) TR 전송 (헤더만, 바디 없음)
        │     → bind 실패 시: 대상 서버 전원 FAIL(0건)로 기록, 소켓 닫고 종료
        │
        └─⑤ 대상 서버를 순서대로 처리 (connectionDead=true가 되면 이후 서버는 즉시 FAIL 0건 기록)
              [SmsTrSocketClient.submitAll(recipients, message)]
                수신자 순회하며 submit(03) TR 전송 (recv_phn 1건씩, 응답 없음=fire-and-forget)
                중간에 IOException → 그 지점에서 멈추고 PARTIAL/FAIL 판정, connectionDead=true
              │
              ├─ [NotifyHistoryMapper.insert()] 서버 처리 완료 즉시 TB_NOTIFY_HISTORY INSERT (crash-safe)
              └─ [AnalyzeResultMapper.updateNotifyYn()] SUCCESS/PARTIAL일 때만 TB_ANALYZE_RESULT.NOTIFY_YN='Y' 갱신
```

### 주요 시나리오별 흐름

#### A. 정상 처리 (전 서버 SUCCESS)

```
1. 대상 서버 N개 모두 경고/에러 1건 이상
2. 수신대상 파일에 전화번호 1건 이상
3. connect 성공 → bind 성공
4. 서버 1~N 순서대로 submitAll() 전원 성공
5. 서버마다 TB_NOTIFY_HISTORY INSERT(SUCCESS), NOTIFY_YN UPDATE
6. 소켓 close
```

#### B. 수신대상 파일이 비어있음 ("통보 끄기")

```
1. 집계 결과 대상 서버 N개 존재
2. NotifyTargetParser.parseTargetFile() → 빈 리스트
3. 소켓을 열지 않고 즉시 모든 대상 서버를 SUCCESS, RECV_TOTAL_COUNT=0 으로 기록
4. NOTIFY_YN UPDATE (SUCCESS이므로 워터마크 전진)
```

#### C. connect 실패 (게이트웨이 다운 등)

```
1. socketClientFactory.connect() → IOException
2. 모든 대상 서버를 FAIL(0건 시도)로 즉시 기록, FAIL_REASON="connect 실패: ..."
3. NOTIFY_YN UPDATE 안 함 (FAIL이므로) → 다음 스케쥴이 같은 구간 재집계
```

#### D. bind 실패

```
1. connect는 성공했지만 sendBind() → IOException
2. 모든 대상 서버를 FAIL(0건 시도)로 즉시 기록, 소켓 close
3. NOTIFY_YN UPDATE 안 함
```

#### E. 서버 처리 중 연결 끊김 (cascade FAIL)

```
1. 서버 1(srv01) submitAll() 진행 중 일부 성공 후 IOException
   → srv01: PARTIAL(성공한 수신자 수만큼), NOTIFY_YN UPDATE (시도했으므로 워터마크 전진)
2. connectionDead=true로 전환, 재연결 시도 안 함
3. 남은 서버(srv02, srv03, ...)는 submitAll() 호출 자체를 하지 않고
   즉시 FAIL(0건 시도, "이전 서버 처리 중 연결이 끊겨 시도하지 못함")로 기록
4. NOTIFY_YN UPDATE 안 함 (FAIL인 서버들) → 다음 스케쥴이 같은 구간 재집계
```

---

## 3. 디렉토리 및 파일 구조

### 디렉토리 역할

```
notify/
├── src/main/java/com/sks/precheck/notify/
│   ├── NotifyApplication.java            메인 클래스 (@EnableScheduling)
│   ├── scheduler/                        스케줄 폴링 및 실행 트리거
│   ├── service/                          집계 + 오케스트레이션 비즈니스 로직
│   ├── parser/                           스케쥴/수신대상 conf 파일 파싱
│   ├── tr/                               SMS TR 바이너리 인코딩 + TCP 소켓 클라이언트
│   ├── mapper/                           MyBatis 매퍼 인터페이스
│   ├── domain/                           MyBatis 결과/파라미터 객체
│   ├── vo/                               스케쥴/집계결과 값 객체
│   ├── common/constants/                 TR 규격 상수, 상태값 상수
│   ├── common/exception/                 NotifyException
│   ├── common/util/                      SequenceHelper (DB 방언 무관 시퀀스 조회)
│   └── config/                           DataSource/MyBatis 설정
├── src/main/resources/
│   ├── application.yaml                  공통 설정 (active profile=test)
│   ├── application-test.yaml             테스트 (PostgreSQL, 로컬 경로)
│   ├── application-prod.yaml             운영 (Altibase, /home/precheck 경로)
│   ├── mapper/                           MyBatis XML 매퍼
│   └── log4j2-spring.xml                 로그 설정
├── schedule_sample/
│   ├── PreCheck_NotifyLogs_Schedule.conf 통보 스케쥴 샘플
│   └── PreCheck_NotifyTarget_List.conf   수신대상 샘플
├── docs/                                 DB정의서/TR연동정의서/스케쥴러정의서/PRD/명세서 (아래 참고)
└── build.gradle
```

> `docs/` 안의 4개 문서(`통보_DB정의서.md`, `통보_TR연동정의서.md`, `통보_스케쥴러정의서.md`, `통보서버_PRD.md`)가 설계 의도와 결정 배경의 1차 출처다. 이 FLOW.md는 "지금 코드가 실제로 어떻게 동작하는지"를 다루고, 그 문서들은 "왜 이렇게 결정했는지"를 다루므로 내용이 충돌하면 문서 쪽이 의도, 이 파일 쪽이 구현 현황이다.

### 주요 파일 목록

| 파일 | 역할 |
|------|------|
| `NotifyScheduler.java` | 매 60초 스케줄 파일 읽기, 슬롯 매칭, 중복실행 방지, `notifyService.runNotify()` 호출 |
| `NotifyService.java` | 통보 1회 실행의 오케스트레이션 — 집계→소켓 연결→bind→서버별 submit→이력 INSERT→NOTIFY_YN 갱신 |
| `NotifyAggregationService.java` | 서버별 워터마크 조회 + `TB_ANALYZE_RESULT` 레벨별 카운트 집계 + 통보 메시지 생성 |
| `NotifyScheduleParser.java` | `[배치\|...]` / `[주기\|...]` 단일 라인 파싱 |
| `NotifyTargetParser.java` | 수신대상(전화번호) 파일 파싱, 숫자만 추출, 중복 제거 |
| `SmsTrEncoder.java` | `BISUB_HEADER`(5B) + `SMSSUBMIT_BODY`(353B) 바이트 인코딩 |
| `SmsTrSocketClient.java` | TCP 소켓 연결, bind/submit 전송, fire-and-forget 성공판정 |
| `SmsTrSocketClientFactory.java` / `DefaultSmsTrSocketClientFactory.java` | 소켓 생성 DI 시드 (테스트에서 가짜 클라이언트 주입용) |
| `AnalyzeResultMapper.java` / `.xml` | `TB_ANALYZE_RESULT` 조회(서버목록/레벨카운트) + `NOTIFY_YN` UPDATE |
| `NotifyHistoryMapper.java` / `.xml` | `TB_NOTIFY_HISTORY` 워터마크 조회 + INSERT |
| `SequenceHelper.java` | PostgreSQL(`nextval`)/Altibase(`.NEXTVAL FROM DUAL`) 방언 분기 시퀀스 조회 |
| `NotifyConstants.java` | TR 필드 길이/고정값, 상태값(SUCCESS/FAIL/PARTIAL), 스케쥴 타입 상수 |
| `DataSourceConfig.java` | `test`/`prod` 프로파일별 `DataSource` 빈 (Postgres/Altibase) |
| `MyBatisConfig.java` | `@MapperScan("com.sks.precheck.notify.mapper")` |

---

## 4. 소스별 주요 함수/메서드

### `NotifyApplication.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `main(args)` | `String[]` | void | Spring Boot 부트스트랩. `@EnableScheduling`으로 스케줄러 활성화 |

---

### `scheduler/NotifyScheduler.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `run()` | 없음 | void | `@Scheduled(fixedDelay=60000)` 진입점. 스케줄 목록 순회하며 슬롯 매칭 후 `notifyService.runNotify()` 호출 |
| `getSchedules()` | 없음 | `List<NotifyScheduleVo>` (private) | 스케줄 파일 로드, `reload-interval-ms`(기본 60000) 동안 캐시 |
| `resolveSlotTimeIfShouldRun(schedule, now)` | `NotifyScheduleVo`, `LocalDateTime` | `LocalDateTime` (private) | 요일 매칭 후 배치/주기 분기하여 실행 여부 판정, 실행할 슬롯의 명목 시각 반환 |
| `resolveBatchSlotTime(...)` | schedule, now, nowSeconds, startSeconds | `LocalDateTime` (private) | 시작시각 ±`POLL_WINDOW_SECONDS`(60초) 윈도우 내, 당일 미실행 시에만 실행 |
| `resolvePeriodicSlotTime(...)` | schedule, now, nowSeconds, startSeconds | `LocalDateTime` (private) | 시작~종료 구간을 간격(분) 단위로 나눠 슬롯 인덱스 계산, 같은 인덱스 재실행 방지 |
| `isTodayMatched(daySpec, date)` | String, `LocalDate` | boolean (private) | `*`/단일 요일숫자/`요일-요일` 범위 매칭 |
| `parseDayDigit(text)` / `toDayDigit(dayOfWeek)` / `parseTime(hhmmss)` | - | - (private) | 요일/시간 문자열 파싱 헬퍼 |

> 중복실행 방지 키는 `schedule.toScheduleExpression()`(스케쥴 라인 원문)만 사용한다. collect/analyze와 달리 서버구분/대상파일명 필드가 없는 전역 단일 라인 스케쥴이기 때문이다.

---

### `service/NotifyService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `runNotify(schedule, aggregateTo)` | `NotifyScheduleVo`, `LocalDateTime` | void | 오케스트레이션 진입점 — 집계→수신대상 로드→소켓연결→bind→서버별 처리 |
| `processOneServer(client, target, recipients, notifyDate)` | `SmsTrSocketClient`, `ServerAggregateResult`, `List<String>`, `String` | boolean (private) | 서버 1건 submit + 이력 INSERT + NOTIFY_YN 갱신. 연결이 끊겨 런을 중단해야 하면 false 반환 |
| `describeFailure(result)` | `SubmitResult` | `String` (private) | FAIL_REASON 문자열 생성 |
| `failAll(targets, notifyDate, failReason)` | `List<ServerAggregateResult>`, String, String | void (private) | connect/bind 실패 시 대상 서버 전원 FAIL(0건) 기록 |
| `insertHistory(...)` | target, notifyDate, status, recv 3종 카운트, failReason, startAt, endAt | void (private) | `TB_NOTIFY_HISTORY` INSERT (시퀀스로 PK 발급) |
| `updateNotifyYn(target)` | `ServerAggregateResult` | void (private) | `TB_ANALYZE_RESULT.NOTIFY_YN` 갱신 |

---

### `service/NotifyAggregationService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `aggregate(schedule, aggregateTo)` | `NotifyScheduleVo`, `LocalDateTime` | `List<ServerAggregateResult>` | 서버별 워터마크 조회 → 레벨별 카운트 집계 → 경고+에러=0 제외 → 통보 대상 목록 생성 |
| `defaultAggregateFrom(schedule, aggregateTo)` | schedule, aggregateTo | `LocalDateTime` (private) | 워터마크 없는 신규 서버 기본값: 주기=`aggregateTo - 통보간격`, 배치=당일 00:00:00 |
| `buildMessage(...)` | serverId, normal/warning/error count, from, to | `String` (private) | `"[서버명] 정상N 경고N 에러N (HH:mm~HH:mm)"` 형식 메시지 생성 |

---

### `parser/NotifyScheduleParser.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `parseScheduleFile(filePath)` | String | `List<NotifyScheduleVo>` | 파일 전체 라인 파싱, `#`/빈 줄 무시 |
| `parseLine(line, lineNumber)` | String, int | `NotifyScheduleVo` (private) | 1라인 → VO, 포맷 오류 시 경고 로그 후 null |
| `extractSingleBracketToken(text)` | String | `String` (private) | `[...]` 안의 원문 추출 |
| `parseScheduleExpression(expr)` | String | `ScheduleParts` (private) | `\|` 분리, 배치(3필드)/주기(5필드) 분기 검증 |
| `isValidDaySpec` / `parseDay` / `isValidTimeHhmmss` / `isValidIntervalMinutes` | - | - (private) | 요일/시간/간격 형식 검증 헬퍼 |
| `ScheduleParts` (내부 클래스) | - | - | type/daySpec/startTime/intervalMinutes/endTime 보관용 |

---

### `parser/NotifyTargetParser.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `parseTargetFile(filePath)` | String | `List<String>` | 파일 없으면 빈 리스트, 있으면 라인별 파싱 후 `LinkedHashSet`으로 중복 제거 |
| `parseLine(line)` | String | `String` (private) | `#`/빈 줄 무시, 숫자 외 문자(`-` 등) 제거 |

---

### `tr/SmsTrEncoder.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `encodeBind()` | 없음 | `byte[]` | `BISUB_HEADER`만(5B), `sms_code="01"`, `body_length="000"` |
| `encodeSubmit(seqNo, recvPhn, message, reqDateTime)` | long, String, String, `LocalDateTime` | `byte[]` | 헤더(5B, `sms_code="03"`) + `SMSSUBMIT_BODY`(353B) = 358B |
| `encodeHeader(smsCode, bodyLength)` | String, int | `byte[]` (private) | sms_code(2B) + body_length(3B, zero-pad) |
| `encodeBody(seqNo, recvPhn, message, reqDateTime)` | - | `byte[]` (private) | 24개 필드를 순서대로 `ByteBuffer`에 채움 (필드 목록은 `통보_TR연동정의서.md` 참고) |
| `blankField(length)` / `fixedTextField(value, length)` / `encodeTruncated(value, maxBytes)` | - | `byte[]` (private) | 좌측 정렬 + 우측 스페이스 패딩, EUC-KR 멀티바이트 안전 절단 |
| `zeroPadField(value, length)` | long, int | `byte[]` (private) | 우측 정렬 + 좌측 0 패딩 (seq_no 등 수치 필드) |
| `concat(first, second)` | `byte[]`, `byte[]` | `byte[]` (private) | 바이트 배열 연결 |

---

### `tr/SmsTrSocketClient.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `SmsTrSocketClient(host, port, connectTimeoutMs, encoder, sequenceHelper)` | - | (생성자) | 실제 `Socket` 연결 |
| `SmsTrSocketClient(out, connection, encoder, sequenceHelper)` | `OutputStream`, `Closeable`, ... | (생성자) | 테스트/고급 용도, 이미 만들어진 스트림 직접 사용 |
| `sendBind()` | 없음 | void (throws IOException) | bind TR 전송, 실패는 호출자가 잡아 전 서버 FAIL 처리 |
| `submitAll(recipients, message)` | `List<String>`, String | `SubmitResult` | 수신자 순회 submit, 중간 실패 시 그 지점에서 멈추고 결과 반환 (재시도 없음) |
| `close()` | 없음 | void | 내부 `Closeable` 종료, 예외는 로그만 남기고 무시 |
| `SubmitResult` (내부 클래스) | successCount, totalCount, failure | - | `allSucceeded()`/`anySucceeded()` 판정 메서드 포함 |

---

### `tr/SmsTrSocketClientFactory.java` / `DefaultSmsTrSocketClientFactory.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `connect(host, port, connectTimeoutMs, encoder, sequenceHelper)` | - | `SmsTrSocketClient` (throws IOException) | (인터페이스) `NotifyService`가 직접 `new` 하지 않고 이 시드로 생성 → 테스트에서 가짜 구현 주입 가능. 기본 구현(`DefaultSmsTrSocketClientFactory`)은 그대로 `new SmsTrSocketClient(...)` 위임 |

---

### `common/util/SequenceHelper.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `nextval(sequenceName)` | String | `Long` | DB 방언에 따라 쿼리 분기 후 시퀀스 다음 값 조회 |
| `buildNextvalSql(dbProductName, sequenceName)` | String, String | `String` (private) | PostgreSQL: `select nextval('...')`, 그 외(Altibase/Oracle 방언): `SELECT ...NEXTVAL FROM DUAL` |

---

### `mapper/AnalyzeResultMapper.java` (인터페이스, `TB_ANALYZE_RESULT` 대상)

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `selectDistinctServerIds()` | 없음 | `List<String>` | 분석결과가 존재하는 전체 `SERVER_ID` 목록 |
| `countByLevelInWindow(serverId, from, to)` | String, `LocalDateTime`×2 | `List<AnalyzeLevelCount>` | 구간(from exclusive ~ to inclusive) 내 레벨별 카운트 |
| `updateNotifyYn(serverId, from, to, notifyAt)` | - | int | 구간 내 행(정상/경고/에러) 전부 `NOTIFY_YN='Y'` 갱신 |

### `mapper/NotifyHistoryMapper.java` (인터페이스, `TB_NOTIFY_HISTORY` 대상)

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `findLastWatermark(serverId)` | String | `LocalDateTime` | 이 서버의 마지막 SUCCESS/PARTIAL 행의 `AGGREGATE_TO`, 없으면 null |
| `insert(notifyHistory)` | `NotifyHistory` | int | 서버 1건 처리 완료 직후 즉시 INSERT |

---

## 5. 리소스 및 DB 환경

### DB 연결 정보

| 프로파일 | DB | JDBC URL | 비고 |
|----------|-----|---------|------|
| `test` (기본 활성) | PostgreSQL | `jdbc:postgresql://localhost:5432/postgres` | 로컬 개발/통합테스트용, `DataSourceConfig.testDataSource()` |
| `prod` | Altibase 8.1.0.0.1 | `jdbc:Altibase://192.168.0.1:20300/precheck` | 운영, `DataSourceConfig.dataSource()` |

활성 프로파일은 `application.yaml`의 `spring.profiles.active: test`로 고정되어 있으며, `--spring.profiles.active=prod`로 덮어써야 운영 DB로 전환된다.

### 사용 테이블/시퀀스

| 대상 | 소속 | notify의 역할 |
|------|------|----------------|
| `TB_ANALYZE_RESULT` | 분석서버 소유 | 읽기(서버목록/레벨카운트) + `NOTIFY_YN`/`NOTIFY_AT` 갱신만 수행 (행 생성/삭제 없음) |
| `TB_NOTIFY_HISTORY` | **통보서버(notify) 소유** | INSERT(서버별 실행 이력) + SELECT(워터마크 조회) |
| `SEQ_NOTIFY_HISTORY` | 통보서버 소유 | `TB_NOTIFY_HISTORY.NOTIFY_HISTORY_ID` PK 발급 |
| `SEQ_NOTIFY_TR_SEQNO` | 통보서버 소유 | TR `seq_no`(8자리) 발급, `MAXVALUE 99999999`에서 `CYCLE`(순환) |

테이블 DDL/인덱스/쿼리 패턴 전체는 `docs/통보_DB정의서.md` 4~9절 참고.

### 외부 리소스

| 리소스 | 설정 키 | 비고 |
|--------|---------|------|
| SMS TR 게이트웨이 (TCP 소켓) | `precheck.notify.sms.host` / `precheck.notify.sms.port` | 양쪽 프로파일 모두 `127.0.0.1:9000` placeholder, TODO로 표시됨 — 실제 게이트웨이 주소 미확정 |
| 통보 스케쥴 conf 파일 | `precheck.notify.schedule-file-path` | test: 로컬 `schedule_sample/...` 절대경로, prod: `/home/precheck/cfg/...` |
| 수신대상 conf 파일 | `precheck.notify.target-file-path` | test: 로컬 `schedule_sample/...` 절대경로, prod: `/home/precheck/cfg/...` |

### 네트워크/포트

이 앱은 자체 HTTP 포트를 열지 않는다(컨트롤러 없음, 순수 배치). 아웃바운드 연결만 발생한다: ① DB(PostgreSQL 5432 / Altibase 20300), ② SMS TR 게이트웨이(설정된 host:port로 매 스케쥴 실행 1회 connect).

---

## 6. 설정 파일 분석

### `application.yaml` (공통)

| 항목 | 기본값 | 설명 |
|------|--------|------|
| `spring.application.name` | `notify` | 앱 이름 |
| `spring.profiles.active` | `test` | 기본 활성 프로파일 |
| `mybatis.mapper-locations` | `classpath:/mapper/*.xml` | XML 매퍼 위치 |
| `mybatis.type-aliases-package` | `com.sks.precheck.notify.domain` | 타입 별칭 패키지 |
| `mybatis.configuration.map-underscore-to-camel-case` | `true` | 스네이크 → 카멜 자동 변환 |

### `application-test.yaml` (로컬/테스트)

| 항목 | 값 | 설명 |
|------|-----|------|
| `spring.datasource.*` | PostgreSQL `localhost:5432/postgres` | 로컬 Postgres, `hikari.initialization-fail-timeout: -1`(DB 미기동 시에도 부팅 허용) |
| `precheck.notify.schedule-file-path` | 로컬 절대경로 `schedule_sample/PreCheck_NotifyLogs_Schedule.conf` | 개발자 PC 경로 하드코딩 |
| `precheck.notify.target-file-path` | 로컬 절대경로 `schedule_sample/PreCheck_NotifyTarget_List.conf` | 개발자 PC 경로 하드코딩 |
| `precheck.notify.sms.host` / `.port` | `127.0.0.1` / `9000` | **TODO: 실제 SMS 게이트웨이로 교체 필요** |

### `application-prod.yaml` (운영)

| 항목 | 값 | 설명 |
|------|-----|------|
| `spring.datasource.*` | Altibase `192.168.0.1:20300/precheck` | 운영 DB |
| `precheck.notify.schedule-file-path` | `/home/precheck/cfg/PreCheck_NotifyLogs_Schedule.conf` | 운영 서버 경로 |
| `precheck.notify.target-file-path` | `/home/precheck/cfg/PreCheck_NotifyTarget_List.conf` | 운영 서버 경로 |
| `precheck.notify.sms.host` / `.port` | `127.0.0.1` / `9000` | **TODO: 실제 SMS 게이트웨이로 교체 필요** (test와 동일 placeholder) |

### `log4j2-spring.xml`

| 항목 | 값 | 설명 |
|------|-----|------|
| Console/RollingFile 패턴 | `%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{1} - %msg%n`, UTF-8 | 콘솔+파일 동시 출력 |
| 파일 경로 | `logs/precheck-notify.log`, 일별 롤오버 `logs/precheck-notify-%d{yyyy-MM-dd}.log.gz` | `max=30`(최대 30개 보관) |
| `sys_out`/`sys_err` 로거 | `OFF` | Spring Boot가 System.out/err를 브릿징하는 노이즈 로그 차단 |
| `com.sks.precheck` 로거 | `INFO`, `additivity=false` | 앱 패키지만 별도 레벨, 중복 출력 방지 |
| `Root` | `ERROR` | 그 외 패키지(프레임워크 등)는 ERROR만 |

### `PreCheck_NotifyLogs_Schedule.conf` (스케쥴 샘플)

```
#[배치|*|090001]
[주기|1-5|000501|30|230501]
```

| 유형 | 형식 | 현재 활성 라인 의미 |
|------|------|----------------------|
| 배치 | `배치\|요일\|시작시각` | (주석 처리됨, 비활성) |
| 주기 | `주기\|요일\|시작시각\|간격(분)\|종료시각` | 월~금(`1-5`), 00:05:01부터 30분 간격으로 23:05:01까지 실행 |

collect/analyze의 스케쥴 conf와 달리 `serverId`/`serverIp`/대상 파일 경로 필드가 없다 — 통보는 서버 구분 없이 전역 한 줄로 동작하며, 서버별 분기는 `TB_ANALYZE_RESULT`의 `SERVER_ID` 목록을 런타임에 조회해서 수행한다.

### `PreCheck_NotifyTarget_List.conf` (수신대상 샘플)

```
# 통보 SMS 수신대상 (전화번호, 한 줄에 1개)
#010-0000-0000
010-1234-5678
```

한 줄에 전화번호 1개, `#`으로 시작하면 무시, 숫자 외 문자(`-` 등)는 파싱 시 제거됨. 이 파일을 비우면(주석/빈 줄만 남기면) 통보가 "꺼진" 것으로 취급되어 소켓 연결 자체를 시도하지 않고 SUCCESS(0건)로 기록된다.

---

## 7. 주요 아키텍처 결정 및 주의사항

### connection 공유 + cascade FAIL

TCP 연결은 스케쥴 실행 1회당 정확히 1개만 연다. 통보 대상 서버가 여러 개여도 같은 연결을 순서대로 공유하며, 처리 중 연결이 끊기면 재연결을 시도하지 않고 그 시점 이후의 모든 미처리 서버를 즉시 FAIL(0건 시도)로 기록한 뒤 런을 종료한다 (`NotifyService.runNotify()`의 `connectionDead` 플래그). 이는 레거시 TR 프로토콜에 ACK가 없어 "연결이 살아있는지"를 write 예외 발생 여부로만 판단할 수 있기 때문이다.

### Crash-safe 이력 INSERT (일괄 INSERT 아님)

서버 1건 처리가 끝나는 즉시 그 서버의 `TB_NOTIFY_HISTORY` 행을 INSERT한다. 전체 서버를 다 처리한 뒤 한꺼번에 INSERT하지 않는 이유는, 프로세스가 중간에 죽어도 이미 끝낸 서버의 SUCCESS/워터마크 전진이 보존되도록 하기 위함이다 (collect의 "INSERT FAIL/IN_PROGRESS 선행" 패턴과 동일한 의도, 다만 notify는 성공 케이스도 즉시 기록한다는 점이 다르다).

### fire-and-forget 성공 판정

레거시 TR 프로토콜에 응답(ack)이 없다고 가정하고 구현했다. `submitAll()`의 성공 판정은 오직 `OutputStream.write()`가 예외 없이 끝났는지로만 이루어진다. 이 가정과 `bind` TR의 바디 구조(현재는 헤더만 전송)는 원본 캡처 자료에 명시되지 않아 추정한 것이므로, 실제 SMS 게이트웨이 연동 전 레거시 담당팀 확인이 필요하다 (`docs/통보_TR연동정의서.md` "확인되지 않은 사항" 절 참고).

### 워터마크 기본값 (신규 서버)

`TB_NOTIFY_HISTORY`에 이력이 없는 서버(최초 등장)는 과거 누적분이 한꺼번에 통보되는 것을 막기 위해 `AGGREGATE_FROM` 기본값을 사용한다: 주기 스케쥴은 `이번 실행 시각 - 1주기(통보간격분)`, 배치 스케쥴은 `당일 00:00:00`.

### 스케줄러 동작 방식

- **폴링 주기**: 매 60초 (`fixedDelay=60000ms`)
- **스케줄 파일 캐시**: 60초 (`reload-interval-ms`, 기본값)
- **시간 매칭 윈도우**: 60초 (`POLL_WINDOW_SECONDS`) — collect(1초 폴링/±2초 윈도우)보다 훨씬 느슨한 이유는 통보가 분 단위 집계이기 때문
- `AGGREGATE_TO`로 쓰이는 시각은 실제 감지된 wall-clock이 아니라 conf에 정의된 슬롯의 명목 시각(예: 매 30분 슬롯이면 정확히 `:00`, `:30`)이다 — 폴링 지연이 누적돼도 집계 구간 경계가 흔들리지 않게 하기 위함

### analyze 서버와의 연계

notify는 `TB_ANALYZE_RESULT`를 자체 매퍼(`AnalyzeResultMapper`)로 직접 SELECT/UPDATE한다 — analyze가 collect의 `TB_COLLECT_LOG`를 자체 매퍼로 직접 조회하는 기존 패턴과 동일하게, 모듈 간 코드 공유 없이 각자 자체 매퍼를 정의한다. 두 서버가 같은 DB를 공유하며, analyze가 INSERT/UPDATE → notify가 SELECT 후 `NOTIFY_YN`만 UPDATE하는 흐름이다.
