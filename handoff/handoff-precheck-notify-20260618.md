# Handoff: precheck_collect/notify module (SMS notification server)

**Date:** 2026-06-18
**Working directory:** `C:\Users\20200161\Desktop\PreCheck_work\Precheck_project\Precheck_SKSCh1\precheck_collect\notify`
**Status:** Implementation complete, all tests passing. No open coding task right now — next session will likely start a *new* request from the user (no arguments were given for this handoff, so scope is unknown).

## What this module is

`notify` is one of several independent Spring Boot 4.0.7 / Java 17 services in `precheck_collect/` (siblings: `collect`, `analyze`, `dashboard`, `test_dataset`), sharing group id `com.sks.precheck` and a common DB. Pipeline: `collect` → `TB_COLLECT_LOG` → `analyze` → `TB_ANALYZE_RESULT` → **`notify`** (this module, reads `TB_ANALYZE_RESULT` on a schedule, sends SMS alerts via a legacy TR socket protocol) → `dashboard` (visualizes).

Full architecture/build commands are in `notify/CLAUDE.md` — read that first, don't duplicate it here.

## Documents already produced (source of truth — read these, don't re-derive)

All in `notify/docs/`:
- `통보_DB정의서.md` — `TB_NOTIFY_HISTORY` schema, DDL, sequences, query patterns.
- `통보_TR연동정의서.md` — full byte layout for the legacy TR protocol (`BISUB_HEADER` 5B + `SMSSUBMIT_BODY` 353B = 358B), bind(01)/submit(03) op codes, EUC-KR encoding, crash-safety semantics for mid-run connection death.
- `통보_스케쥴러정의서.md` — schedule conf format (`[통보주기기술]`), recipient-file rules, duplicate-execution prevention.
- `통보서버_PRD.md` — full PRD (31 user stories, implementation/testing decisions, out of scope, further notes). This is the canonical synthesis of the `/grill-me` interview.
- `1__프로그램_명세서_20260514.md` — **only the copy in `notify/docs/` was edited** to reflect the new design (`[결과 통보 서버]` section, `[공통 시스템간 경계]` line). The copies in `context_org` and other sibling modules were deliberately left untouched per explicit user instruction — propagating this change elsewhere (e.g. via `/context-sync`) was explicitly deferred, not done.

Do not re-interview the user or regenerate these docs — they reflect a fully resolved design. If a future task touches notify, read the PRD first.

## Implementation state (complete, as of last session)

All 13 planned implementation tasks are done. Full `./gradlew.bat build` was green; 44 tests across 10 test classes passed (0 failures/0 errors), including a full `@SpringBootTest` context-load test. A local Postgres DB was used for mapper integration tests (`@Transactional` rollback verified — `tb_notify_history` row count is 0 after test runs).

Key source files (all under `src/main/java/com/sks/precheck/notify/`):
- `config/DataSourceConfig.java`, `config/MyBatisConfig.java`
- `common/constants/NotifyConstants.java`, `common/exception/NotifyException.java`, `common/util/SequenceHelper.java`
- `vo/NotifyScheduleVo.java`, `vo/ServerAggregateResult.java`
- `parser/NotifyScheduleParser.java`, `parser/NotifyTargetParser.java`
- `tr/SmsTrEncoder.java`, `tr/SmsTrSocketClient.java`, `tr/SmsTrSocketClientFactory.java` (+ `DefaultSmsTrSocketClientFactory.java` — DI seam for testability)
- `domain/AnalyzeLevelCount.java`, `domain/NotifyHistory.java`
- `mapper/AnalyzeResultMapper.java`, `mapper/NotifyHistoryMapper.java` (+ XML mappers)
- `service/NotifyAggregationService.java`, `service/NotifyService.java` (core orchestration — connection-sharing-across-servers, crash-safe per-server history insert, cascade-FAIL semantics on mid-run connection death)
- `scheduler/NotifyScheduler.java` (polling/caching/dedup pattern mirroring collect/analyze)
- `NotifyApplication.java` (`@EnableScheduling` added)

Test files mirror each of the above under `src/test/java/...`.

## Known gaps / placeholders that need real-world confirmation before production

These are documented as open risks in the docs (not tasks — just flagged):
1. SMS gateway host/port is a placeholder (`127.0.0.1:9000`) in `application-test.yaml` / `application-prod.yaml`, marked with TODO comments.
2. The bind-TR body structure was implemented as **header-only** (no body) — the legacy team's actual bind body shape (if any) was never confirmed from the source screenshot. Documented explicitly as a defensible default, not a fact.
3. Whether the legacy TR protocol truly has no ACK/response (current implementation is fire-and-forget, success = absence of `IOException` on write) is assumed, not confirmed.
4. Whether `body_length` is always fixed at `"353"` is assumed.

If the next session involves wiring this up to a real SMS gateway, these four points are exactly what to check first.

## Process notes for the next session

- **Recurring bug to avoid**: typing manual `\uXXXX` Unicode escapes in tool-call JSON parameters (e.g. `AskUserQuestion`) corrupts Korean text. Always type literal Hangul characters instead. This happened repeatedly in the prior session and the user flagged it multiple times.
- The user drives design decisions via `/grill-me` (one question at a time, with a recommended default) and expects documentation kept in sync across all 4 docs whenever a decision changes mid-interview.
- When editing shared docs that exist in multiple module copies (e.g. `1__프로그램_명세서_20260514.md`), confirm scope explicitly — the user has been precise about "only this copy, not the others."

## Suggested skills for the next session

- `/grill-me` — if the next task is a new feature/design decision that needs interviewing rather than direct implementation.
- `/to-prd` — if new design decisions emerge that should be written up as a PRD (do not re-run for the existing PRD, which is already complete).
- `caveman` — active per the user's global CLAUDE.md (auto-activate at session start, normal prose only in code/commits/PRs).
- No `/context-sync` was run and none is scheduled — only mention it if the user asks to propagate the `1__프로그램_명세서_20260514.md` change to sibling modules.
