# PreCheck 통보 서버(notify) PRD

> 본 문서는 2026-06-18 세션에서 `/grill-me`로 진행한 설계 인터뷰 결과를 정리한 PRD다.
> 대상 모듈: `precheck_collect/notify`

---

## Problem Statement

PreCheck 시스템은 각 서브시스템 로그를 수집(`collect`)하고 분석(`analyze`)해서 `TB_ANALYZE_RESULT`에 정상/경고/에러 판정을 쌓아두지만, 이 결과를 능동적으로 알려주는 채널이 없다. 운영자는 `Dashboard`를 직접 열어봐야만 이상 여부를 알 수 있고, 화면을 보지 않는 동안 발생한 에러/경고는 즉시 인지되지 않는다.

또한 1차 프로그램 명세서(`1__프로그램_명세서_20260514.md`)는 통보 서버를 "분석결과를 레거시 SMS 테이블에 INSERT하는 방식"으로 2차 개발 항목으로 남겨두었는데, 실제로는 레거시 시스템이 TR(소켓) 인터페이스를 통해서만 SMS를 받을 수 있다는 제약이 확인되어, 명세서의 DB Insert 방식 그대로는 구현할 수 없다.

## Solution

`notify` 모듈이 별도 스케쥴(conf 파일)에 따라 주기적으로 깨어나, 서버(SERVER_ID)별로 `TB_ANALYZE_RESULT`를 증분 집계(이전 통보 시점 이후 ~ 이번 스케쥴 시점)해서 정상/경고/에러 건수를 센다. 경고 또는 에러가 1건이라도 있는 서버에 대해서만, 레거시 SMS 게이트웨이가 요구하는 TR 바이너리 프로토콜(`BISUB_HEADER` + `SMSSUBMIT_BODY`, bind→submit)로 TCP 소켓을 통해 "서버명 + 건수 + 집계시각" 요약 SMS를 전역 수신자 목록(파일 관리) 전체에 전송한다.

레거시 인터페이스에는 응답(ack) 페이로드가 없으므로 성공 판정은 TCP write 성공 여부로만 하고(fire-and-forget), 통보 시도 이력과 다음 집계 시작점(워터마크)은 신규 테이블 `TB_NOTIFY_HISTORY`로 관리한다.

이 설계는 1차 명세서의 "DB Insert 방식"에서 "TR 소켓 직접 전송 방식"으로의 의도적인 피벗이며, 수신자 관리 및 전송 책임이 레거시 시스템에서 `notify` 모듈 자신으로 이동한다.

## User Stories

1. As an 운영자, I want to receive an SMS when a monitored server logs at least one error, so that I can react without having to watch the Dashboard.
2. As an 운영자, I want to receive an SMS when a monitored server logs at least one warning, so that I can take preventive action before it becomes an error.
3. As an 운영자, I want the SMS to show which server triggered it, so that I know where to look first.
4. As an 운영자, I want the SMS to show the count of normal/warning/error events in the window, so that I can gauge severity at a glance without opening the Dashboard.
5. As an 운영자, I want the SMS to show the aggregation time window (HH:mm~HH:mm), so that I know exactly which period the counts cover.
6. As an 운영자, I do not want to receive an SMS for a server that had zero warnings and zero errors in the window, so that I'm not spammed with noise on healthy servers.
7. As any 운영자 listed in the recipient file, I want to receive every triggered alert SMS, so that the whole on-call group stays equally informed.
8. As an 운영자, I want to be added or removed from the SMS recipient list by editing a plain file, so that on-call rotation changes don't require a deployment.
9. As a system maintainer, I want each notify run to aggregate only the analysis results since the last successfully-attempted run per server, so that the same incident is never double-counted in two consecutive SMS digests.
10. As a system maintainer, I want a persistent record of every notify attempt (server, window, counts, status, failure reason), so that I can audit missed or failed notifications after the fact.
11. As a system maintainer, I want a completely failed connection attempt (connect/bind itself fails) to leave the watermark untouched, so that the same incident window is retried at the next schedule instead of being silently lost.
12. As a system maintainer, I want a partially-failed send (some recipients fail mid-loop after bind succeeded) to still advance the watermark, so that one bad phone number doesn't block all future notifications for that server.
13. As a Dashboard user, I want the 🔔 "미통보" icon on a `TB_ANALYZE_RESULT` row to clear once that row has been included in a sent (or attempted-and-counted) SMS digest, so that the dashboard's notification status stays accurate.
14. As a system maintainer, I want the notify schedule (times the aggregation job runs) to be configured via a conf file using the same `[주기|요일|시작|간격|종료]` pattern as `collect`/`analyze`, so that ops can change cadence without a redeploy and without learning a new format.
15. As a system maintainer, I want the TR bytes sent to the legacy SMS gateway to conform exactly to the `BISUB_HEADER`/`SMSSUBMIT_BODY` struct layout (5-byte header + 353-byte body), so that the legacy gateway accepts the request without any protocol mismatch.
16. As a system maintainer, I want a `bind(01)` TR sent once per opened connection before any `submit(03)` TRs, so that the legacy gateway's session/registration expectation is satisfied.
17. As a system maintainer, I want one TCP connection opened, bound, and used for every submit in a single schedule run (rather than one connection per message), so that we don't pay a connect/bind cost per recipient.
18. As a system maintainer, I want all finance-specific TR fields (account number, order number, settlement date, etc.) left blank, so that we don't send garbage into fields that don't apply to a monitoring alert.
19. As a system maintainer, I want unused/blank fixed-width fields padded with ASCII space, so that the byte layout matches what the legacy gateway expects from other space-padded clients.
20. As a system maintainer, I want the body's `sms_code` fixed to `02`(개별발송), so that the legacy gateway classifies these as individual sends rather than campaign or account-opening notices.
21. As a system maintainer, I want `sender_info` and `term_ip` fixed to a known, environment-independent value (`130.2.4.12`), so that the legacy gateway can identify the caller consistently regardless of which box notify runs on.
22. As a system maintainer, I want the TR `seq_no` generated from a dedicated DB sequence that wraps within 8 digits, so that the fixed-width field never overflows even under long-running continuous use.
23. As a system maintainer, I want the message text encoded in EUC-KR before being packed into the TR, so that Korean server names render correctly on the recipient's phone.
24. As an auditor, I want the un-truncated, original message text preserved in `TB_NOTIFY_HISTORY` even though the wire format only carries 80 bytes, so that I can reconstruct exactly what was communicated regardless of wire-level truncation.
25. As a system maintainer, I want the notify scheduler to run single-threaded with no `@Async`/`@Retryable`, so that watermark advancement and `NOTIFY_YN` updates are never subject to concurrent-update races, consistent with the original spec's recommendation for this server.
26. As a system maintainer, I want `notify` to use a single PreCheck DataSource only (no second legacy-SMS DataSource), so that the module stays as simple as the direct-TR-socket design requires — the multi-DataSource plan in the 1st-phase spec is no longer needed.
27. As a system maintainer, I want a brand-new server's first notify run to only look back one cycle (or to the start of today for a batch schedule), so that a flood of historical errors doesn't get bundled into the very first SMS digest.
28. As an operator, I want to be able to silence all SMS notifications by emptying the recipient file, so that I have a simple, code-free way to mute alerts during planned maintenance without touching the database.
29. As a system maintainer, I want the recipient file parser to strip non-digit characters and de-duplicate phone numbers, so that formatting inconsistencies (dashes) or accidental duplicate entries don't cause malformed TRs or repeated SMS to the same number.
30. As a system maintainer, I want the notify scheduler to use the same duplicate-execution-prevention pattern as `collect`/`analyze` (a tracked key per matched schedule slot), so that a single poll window never triggers the same aggregation twice.
31. As a system maintainer, I want each server's `TB_NOTIFY_HISTORY` row written immediately after that server finishes (not batched at the end of the run), and any server not yet reached when the shared connection dies marked `FAIL`, so that a crash or dropped connection mid-run never silently loses an already-completed server's success or leaves a not-yet-attempted server's status undefined.

## Implementation Decisions

**모듈/패키지 구성** (collect/analyze의 기존 레이아웃 재사용, `notify`는 독립 Gradle 프로젝트이므로 클래스 공유 없이 자체 구현)

- `scheduler` — 통보 스케쥴 conf 파일을 폴링하고 실행 시점을 판정하는 진입점 (`collect`의 `CollectScheduler`, `analyze`의 `AnalyzeScheduler`와 동일한 역할)
- `service` — 서버별 집계 로직, TR 바이트 인코딩/소켓 송신 로직
- `mapper` — `TB_ANALYZE_RESULT`(읽기 + `NOTIFY_YN` 갱신)와 `TB_NOTIFY_HISTORY`(읽기/쓰기)용 MyBatis 매퍼. `TB_ANALYZE_RESULT`는 `analyze` 모듈이 "소유"하는 테이블이지만, `analyze`가 `collect`의 `TB_COLLECT_LOG`를 자체 매퍼로 직접 조회하는 기존 패턴과 동일하게 `notify`도 자체 매퍼를 새로 정의한다 (모듈 간 코드 공유 없음, DB만 공유)
- `dto`/`vo` — 통보 스케쥴 표현(`NotifyScheduleVo` 등), 서버별 집계 결과(`ServerAggregateResult` 등)
- `constants` — TR 필드 길이/고정값(아래 참고)
- `config` — 수신자 파일 경로, 통보 스케쥴 파일 경로 등 외부 설정 프로퍼티 바인딩

**TR 프로토콜**

- `BISUB_HEADER`(5바이트: `sms_code`[2] + `body_length`[3]) + `SMSSUBMIT_BODY`(353바이트) = TR 1건 358바이트
- 연결마다 `bind(01)` 1회 → 이후 `submit(03)` N회(N = 경고/에러 있는 서버 수 × 수신자 수) → 연결 종료
- 응답(ack) 페이로드 없음 — TCP write 예외 발생 여부로만 성공/실패 판정 (fire-and-forget)
- 인코딩: EUC-KR
- 빈 필드/수치형 빈 자리 패딩 문자: ASCII 스페이스(0x20)

**TR 필드값 고정 규칙**

| 필드 | 값 |
|---|---|
| `sms_code`(body) | `02` (개별발송) 고정 |
| `sender_info` | `130.2.4.12` 고정, 환경(local/test/prod) 무관 |
| `term_ip` | `130.2.4.12` 고정, 환경 무관 |
| `branch_code` | blank |
| `recv_phn_snd` | 사용 안 함, blank |
| 금융 전용 필드(`accnt_no`, `junin_id`, `che_num`, `order_no`, `origin_no`, `che_date`, `accnt_admin_id`, `admin_id`, `send_phn`, `recall_phn`) | 전부 blank |
| `seq_no` | `SEQ_NOTIFY_TR_SEQNO` (8자리, `MAXVALUE 99999999 CYCLE`) 값을 0-padding |
| `recv_phn` | 전송대상 파일에서 읽은 전화번호 1건 |
| `message` | `[서버명] 정상N 경고N 에러N (HH:mm~HH:mm)`, EUC-KR 인코딩 후 80바이트로 자름 |
| `end` | `0x03` 고정 |

**집계/통보 로직**

- 서버(`SERVER_ID`)별로 독립 처리, 서버당 SMS 1건
- 워터마크: `TB_NOTIFY_HISTORY`에서 해당 서버의 `NOTIFY_STATUS IN ('SUCCESS','PARTIAL')` 최신 행의 `AGGREGATE_TO`를 다음 구간의 시작점으로 사용
- 워터마크가 없는 신규 서버의 `AGGREGATE_FROM` 기본값: 주기 스케쥴은 "이번 실행 시각 - 1주기(통보간격분)", 배치 스케쥴은 "당일 00:00:00" — 과거 누적분이 첫 실행에 한꺼번에 통보되는 것을 방지
- `AGGREGATE_TO`는 스케쥴러가 실제로 감지한 wall-clock이 아니라 conf에 정의된 슬롯 시각(고정값)을 사용 (collect/analyze 폴링 패턴과 동일 원칙, 폴링 지연이 구간 경계에 누적되지 않도록 함)
- 집계 대상: `ANALYZE_LEVEL IN ('정상','경고','에러')` (정보/미분석 제외), `ANALYZE_DATETIME`이 `(AGGREGATE_FROM, AGGREGATE_TO]` 구간
- 경고+에러 합계가 0이면 해당 서버는 통보 대상 제외, `TB_NOTIFY_HISTORY` 행도 생성하지 않음
- 전송대상 파일이 비어있으면(유효 번호 0개) connection을 열지 않고 submit 0건으로 `NOTIFY_STATUS='SUCCESS'`, `RECV_TOTAL_COUNT=0` 처리 — 워터마크는 전진함 (수신자 파일을 비우는 것을 "통보 끄기" 운영 수단으로 사용 가능)
- 전송 시도 후(`SUCCESS`/`PARTIAL`) 이번 집계 구간에 포함된 행 전부(정상/경고/에러)를 `TB_ANALYZE_RESULT.NOTIFY_YN='Y'` 갱신, Dashboard 🔔 아이콘 정합성 목적 (※2026-06-22 결정 변경: 인터뷰 당시엔 경고/에러 행만 갱신하고 정상 행은 대상 아님으로 결정했으나, 통보에 포함된 정상 건수도 "이미 통보됨"으로 표시하는 게 맞다고 재판단해 정상 행도 포함하도록 확장함 — 상세는 `통보_DB정의서.md` 참고)
- `FAIL`(connect/bind 자체 실패)인 경우 워터마크와 `NOTIFY_YN` 모두 갱신하지 않음 — 다음 스케쥴에 동일 구간 재집계
- 중복 실행 방지: collect/analyze의 `서버구분+경로+스케쥴식` 키 패턴을 차용해, notify는 `통보주기기술 문자열 + 매칭된 슬롯 시각`을 키로 사용해 같은 폴링 윈도우 내 중복 실행을 방지
- connection은 한 스케쥴 실행 전체에서 1개만 열며, 통보 대상 서버 전체가 그 connection 하나를 순서대로 공유함(서버별로 새 connection을 열지 않음)
- crash-safety: 서버 하나의 처리(해당 서버 수신자 전원 submit)가 끝나는 즉시 그 서버의 `TB_NOTIFY_HISTORY`를 INSERT하고 다음 서버로 진행 — 전체 서버를 다 처리한 뒤 일괄 INSERT하지 않음. 이렇게 해야 프로세스가 런 중간에 죽어도 이미 끝낸 서버의 SUCCESS/워터마크 전진이 보존됨 (`collect`의 "시작 직전 FAIL/IN_PROGRESS 선INSERT" crash-safe 패턴과 같은 목적)
- bind 실패(런 시작 자체가 실패)는 이번 런의 통보 대상 서버 전원을 FAIL로 기록. 런 중간에 connection이 끊기면: 끊긴 시점에 처리 중이던 서버는 그때까지 성공한 건수 기준 PARTIAL(1건 이상)/FAIL(0건)로, **아직 처리를 시작하지 않은 나머지 서버는 전부 FAIL(0건 시도)**로 기록하고 런을 종료함(재연결해서 이어가지 않음) — 다음 스케쥴이 FAIL난 서버들의 동일 구간을 자동으로 재집계

**DB 스키마**

- 신규 테이블 `TB_NOTIFY_HISTORY` 및 시퀀스 `SEQ_NOTIFY_HISTORY`, `SEQ_NOTIFY_TR_SEQNO` — 상세 컬럼/인덱스/쿼리 패턴은 `notify/docs/통보_DB정의서.md`에 기술됨 (이 PRD에서 중복 기술하지 않음)
- 단일 PreCheck DataSource만 사용 (MyBatis), 레거시 SMS DB용 2차 DataSource는 불필요

**스케쥴/대상 파일**

- 통보 스케쥴: `PreCheck_NotifyLogs_Schedule.conf`, `collect`/`analyze`의 `[주기|요일|시작|간격|종료]` 패턴 재사용. 서버별 항목이 아니라 전송 시각만 정의(스케쥴 도래 시 모든 서버를 한 번에 검사)
- 수신자 목록: `PreCheck_NotifyTarget_List.conf`(UTF-8, TR 전송용 EUC-KR과는 별개), 한 줄에 전화번호 1개(이름 등 추가 컬럼 없음), 서버/`LOG_ID`별 그룹 없음. `#` skip, 빈 줄 무시, 숫자 외 문자(하이픈 등) 제거 후 중복 번호는 합침

**실행 모델**

- 단일 스레드 `@Scheduled`, `@Async`/`@Retryable` 미사용 — `collect`/`analyze`의 비동기+재시도 모델과 의도적으로 다름

## Testing Decisions

좋은 테스트의 기준: 외부에서 관찰 가능한 동작(파싱 결과, 인코딩된 바이트, DB에 기록되는 값, watermark 전진/정지 여부)만 검증하고, 내부 구현 디테일에는 의존하지 않는다.

- **집계 로직** (`Server별 정상/경고/에러 카운트 + 워터마크 윈도우 계산`): 순수 로직 단위 테스트, 매퍼를 Mockito로 모킹. Prior art: `CollectRetryServiceTest`(매퍼/서비스 모킹 + `assertThat`/AssertJ로 결과 검증).
- **TR 바이트 인코딩** (`BISUB_HEADER`/`SMSSUBMIT_BODY` 필드 패킹, 길이 계산, EUC-KR 인코딩, 스페이스 패딩, 80바이트 truncation): 순수 함수 단위 테스트로, 정확한 바이트 시퀀스(`byte[]`) assertion. 외부 의존성 없음 — collect의 `LogNormalizeParserTest`처럼 입력→출력 고정 케이스 검증 스타일을 따른다.
- **소켓 전송 (`bind`+`submit` 루프)**: 로컬 `ServerSocket`을 띄운 통합 테스트로, 실제 전송된 바이트가 기대 레이아웃과 일치하는지 확인. 연결 중간 끊김(partial), 연결 자체 실패(fail) 시나리오를 모두 검증해 `NOTIFY_STATUS` 분기(`SUCCESS`/`PARTIAL`/`FAIL`)와 watermark 전진/유지 여부를 함께 확인한다.
- **통보 스케쥴 conf 파서**: `@TempDir`에 임시 conf 파일을 써서 파싱 결과를 검증. Prior art: `AnalyzeScheduleParserTest`(`@TempDir` + `Files.writeString` + 파싱 결과 필드별 assertion, 잘못된 라인은 무시되는 케이스 포함).
- **`NOTIFY_YN` UPDATE / `TB_NOTIFY_HISTORY` INSERT**: MyBatis 매퍼 레벨 통합 테스트(PostgreSQL 테스트 DB 대상)로, `collect`/`analyze`의 매퍼 XML 검증 방식을 따른다 (단, 현재 `collect`/`analyze` 저장소에는 매퍼 자체의 통합 테스트가 아직 없으므로, 신규로 도입하는 패턴이 됨 — `application-test.yml`에 준하는 프로파일을 `notify`에도 만들어야 함).

## Out of Scope

- 실제 코드 구현(서비스/매퍼/스케쥴러 클래스, TR 인코더, 소켓 클라이언트) — 이번 산출물은 설계/문서이며, 구현은 다음 단계
- Dashboard 측 변경 — 통보 이력(`TB_NOTIFY_HISTORY`)을 보여주는 화면 추가는 범위 밖. 기존 🔔 아이콘(`NOTIFY_YN` 기준)이 그대로 정합성을 유지하도록만 한다
- 레거시 SMS 게이트웨이 자체의 실제 발송 성패(통신사 단의 SMS 전달 성공 여부) — `notify`는 TR을 보냈다는 사실까지만 책임지며, 그 이후 단계는 레거시 시스템 책임
- 수신자 목록 관리 UI/관리자 기능 — 1차는 파일 직접 편집, 화면을 통한 추가/삭제 기능 없음
- 통보 정책의 세부 사항(중복방지 외의 휴일 처리, 재전송 횟수 제한, 수신자 그룹별 차등 발송 등) — 원래 1차 명세서에서는 이런 정책을 레거시 시스템이 담당했으나, DB Insert 방식을 버리면서 그 위임 구조도 같이 없어짐. 워터마크 기반 중복방지만 구현하며, 그 외 정책은 이번 범위에 포함하지 않음
- 멀티스레드/대용량 처리 최적화 — 일일 로그 500건 미만 규모 가정(1차 명세서 공통 조건)이므로 불필요

## Further Notes

- 이번 설계는 1차 프로그램 명세서(`1__프로그램_명세서_20260514.md`)가 통보 서버를 "DB Insert 방식 + 정책 판단은 레거시에 위임"으로 정의해둔 것과 의도적으로 다른 방향(TR 소켓 직접 전송 + 전송대상/메시지 생성까지 `notify`가 책임)으로 피벗한 결과다. 명세서 자체는 아직 갱신되지 않았으니, 추후 명세서 문서도 이 PRD 내용에 맞춰 갱신이 필요하다.
- TR 규격(`BISUB_HEADER`/`SMSSUBMIT_BODY`)은 사용자가 제공한 레거시 시스템 화면 캡처에서 가져온 것이며, 그 캡처에는 요청(`bind`/`submit`) 구조체만 있고 응답/ack 구조체가 없었다. 본 PRD는 "응답 없음 = fire-and-forget"으로 해석했으나, 실제 레거시 SMS 운영팀에게 이 가정이 맞는지 구현 전 재확인하는 것을 권장한다.
- `sender_info`/`term_ip`로 고정한 `130.2.4.12`, `branch_code` blank, `sms_code=02` 등은 모두 이번 세션에서 사용자가 직접 확정해준 값이며, 추후 실제 운영 환경에서 레거시 게이트웨이 쪽 화이트리스트/등록 정보와 일치하는지 별도 확인이 필요할 수 있다.
