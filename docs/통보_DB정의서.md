# PreCheck 통보 DB 정의서 v1.0

---

## 1. 문서 목적

이 문서는 PreCheck 결과 통보 서버(`notify`)에서 사용하는 DB 테이블 구조를 정의한다.
통보 서버는 **분석 서버가 저장한 TB_ANALYZE_RESULT를 읽어서 SMS 통보 TR을 생성/전송**하고,
전송 실행 이력을 이 문서에서 정의한 TB_NOTIFY_HISTORY에 저장한다.

---

## 2. DB 호환성 원칙 (수집/분석 서버 DB 정의서와 동일)

| 원칙 | 내용 |
|---|---|
| PK 생성 방식 | SEQUENCE 객체 사용 (PostgreSQL/Altibase 공통 지원) |
| 문자열 타입 | VARCHAR(n) 사용, TEXT 사용 금지 |
| 날짜/시간 타입 | TIMESTAMP 사용 |
| 실수 타입 | NUMERIC(p,s) 사용 |
| 논리값 타입 | CHAR(1) 사용 ('Y'/'N'), BOOLEAN 사용 금지 |
| 대용량 문자열 | VARCHAR(4000) 이하로 설계, CLOB 사용 금지 |
| FK 제약 | 사용 안함, 애플리케이션 레벨에서 정합성 관리 |

---

## 3. 통보 서버 동작 흐름 (DB 관점)

```
[통보 스케쥴 도래] (별도 conf 파일, [주기|요일|시작|간격|종료] 패턴)
        |
        ↓
① 서버(SERVER_ID) 목록별로 워터마크 조회
   SELECT AGGREGATE_TO FROM TB_NOTIFY_HISTORY
   WHERE SERVER_ID=? AND NOTIFY_STATUS IN ('SUCCESS','PARTIAL')
   ORDER BY NOTIFY_START_AT DESC FETCH FIRST 1 ROWS ONLY
   → 없으면(신규 서버) AGGREGATE_FROM 기본값 사용:
      - 주기 스케쥴: 이번 실행 시각 - 1주기(통보간격분)
      - 배치 스케쥴: 당일 00:00:00
     (과거 누적분이 한꺼번에 집계/통보되는 것을 방지하기 위함)
        |
        ↓
② TB_ANALYZE_RESULT 집계 (서버별, AGGREGATE_FROM ~ 이번 스케쥴 기준시각)
   - ANALYZE_LEVEL IN ('정상','경고','에러') 만 집계 (정보/미분석 제외)
   - 정상/경고/에러 건수 집계
        |
        ↓
③ 경고+에러 합계가 0이면 → 그 서버는 통보 대상 아님, 스킵 (TB_NOTIFY_HISTORY 행도 생성 안함)
   경고+에러 합계가 1건 이상이면 → ④로 진행
        |
        ↓
④ 통보 메시지 생성: "[서버명] 정상N 경고N 에러N (HH:mm~HH:mm)"
        |
        ↓
⑤ TCP 소켓 연결 (전체 스케쥴 실행당 1개, 통보 대상 서버 전체가 이 connection 하나를 공유), bind(01) TR 전송
   - bind 자체가 실패 → 통보 대상 서버 전원 NOTIFY_STATUS='FAIL'(0건 시도)로 즉시 기록, 런 종료
        |
        ↓
⑥ 통보 대상 서버를 한 서버씩 순서대로 처리 (서버 간 connection은 그대로 재사용, 새로 열지 않음):
   전송대상 파일의 수신자 전화번호 순회하며 submit(03) TR 전송 (recv_phn에 1건씩)
   - 이 서버의 수신자 전원 성공 → NOTIFY_STATUS='SUCCESS'
   - 처리 중 connection이 끊김 → 그때까지 성공 건수 기준 'PARTIAL'(1건↑) 또는 'FAIL'(0건)
     → 이 시점에서 **아직 처리 안 한 나머지 서버도 전부 'FAIL'(0건 시도)** 로 즉시 기록하고 런 종료(재연결 안 함)
   - 한 서버 처리가 끝나는 즉시(⑦로 가서) 그 서버의 TB_NOTIFY_HISTORY를 INSERT하고 다음 서버로 진행
     (전체 서버를 다 처리한 뒤 한꺼번에 INSERT하지 않음 → crash-safe: 프로세스가 중간에 죽어도
      이미 끝낸 서버의 SUCCESS/워터마크 전진은 보존됨)
        |
        ↓
⑦ 그 서버의 TB_NOTIFY_HISTORY INSERT (집계구간, 건수, 전송결과 기록) — 서버 처리 완료 직후 즉시 실행
        |
        ↓
⑧ NOTIFY_STATUS가 SUCCESS/PARTIAL인 경우에만, 이번 집계에 포함된 그 서버의 행(정상/경고/에러 전부) → NOTIFY_YN='Y' UPDATE
   (Dashboard 🔔 아이콘 정합성 유지 목적, FAIL이면 UPDATE 자체를 실행 안 함)
```

---

## 4. SEQUENCE 설계

```sql
-- 통보 이력 테이블용 시퀀스
CREATE SEQUENCE SEQ_NOTIFY_HISTORY
    START WITH 1
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- TR seq_no(8자리) 전용 시퀀스
-- SMSSUBMIT_BODY.seq_no가 char[8]이라 한도를 넘으면 TR 자체가 깨지므로 순환시킴
-- (다른 시퀀스와 달리 NOCYCLE이 아닌 CYCLE을 사용하는 점에 유의)
CREATE SEQUENCE SEQ_NOTIFY_TR_SEQNO
    START WITH 1
    INCREMENT BY 1
    MAXVALUE 99999999
    NOCACHE
    CYCLE;
```

---

## 5. 테이블 설계

### 5-1. TB_NOTIFY_HISTORY (통보 실행 이력 테이블)

**역할**: 통보 서버가 스케쥴을 실행할 때마다 서버 단위로 집계/전송한 결과를 기록하는 테이블
**INSERT 주체**: 통보 서버
**SELECT 주체**: 통보 서버(다음 실행 시 워터마크 조회), 운영자(장애 추적)

```sql
-- ============================================================
-- TB_NOTIFY_HISTORY : 통보(SMS TR 전송) 실행 이력 테이블
-- ============================================================
-- [설계 의도]
--   - 통보 스케쥴이 도래할 때마다, 서버(SERVER_ID) 단위로 1행 INSERT
--   - 다음 스케쥴 실행 시 "어디까지 집계했는지" 판단 기준(워터마크)을
--     이 테이블의 AGGREGATE_TO 값으로 관리 (분석서버의 LAST_ANALYZE_LOG_ID와 동일 패턴)
--   - NOTIFY_STATUS:
--       'SUCCESS' : connect/bind 성공, 대상 수신자 전원 submit 성공
--                   (전송대상 파일이 비어있어 RECV_TOTAL_COUNT=0인 경우도 SUCCESS로 취급함 →
--                    수신자 파일을 비워두는 것을 "통보 끄기" 용도로 의도적으로 사용할 수 있게 함)
--       'PARTIAL' : connect/bind 성공, 이 서버의 수신자 중 일부만 submit 성공(처리 중 연결 끊김 등)
--       'FAIL'    : 이 서버에 대해 submit을 0건 시도함. 원인은 두 가지:
--                   (a) bind 자체가 실패 → 이번 런의 통보 대상 서버 전원이 FAIL
--                   (b) connection이 이전 서버 처리 중 끊겨서, 이 서버는 차례가 오기 전에 런이 종료됨
--     SUCCESS/PARTIAL은 "시도했음"으로 보고 워터마크를 전진시키고,
--     FAIL은 워터마크를 전진시키지 않아 다음 스케쥴이 같은 구간을 재집계함
--   - 한 서버의 처리가 끝나는 즉시(전체 런이 끝나기 전에) 그 서버의 행을 INSERT함 (crash-safe,
--     전체 서버를 다 처리한 뒤 일괄 INSERT하지 않음) → 통보_TR연동정의서.md 5절 참고
--   - 경고+에러가 0건인 서버는 통보 대상이 아니므로 이 테이블에 행이 생성되지 않음
--     (다음 스케쥴의 AGGREGATE_FROM 계산 시 이 점을 함께 고려해야 함 → 7-1 참고)
-- ============================================================

CREATE TABLE TB_NOTIFY_HISTORY (

    -- --------------------------------------------------------
    -- PK
    -- --------------------------------------------------------
    NOTIFY_HISTORY_ID    NUMERIC(19, 0)      NOT NULL,
    -- 통보 이력 고유 ID (SEQUENCE로 자동 생성)

    -- --------------------------------------------------------
    -- 통보 대상 / 집계 구간
    -- --------------------------------------------------------
    SERVER_ID            VARCHAR(100)        NOT NULL,
    -- 통보 대상 서버 구분명 (TB_ANALYZE_RESULT.SERVER_ID와 동일 값)
    -- 서버별로 1스케쥴당 1행 생성 (경고/에러 있는 서버만)

    AGGREGATE_FROM        TIMESTAMP           NOT NULL,
    -- 이번 집계 구간의 시작 시각 (= 이 서버의 이전 SUCCESS/PARTIAL 행의 AGGREGATE_TO)
    -- 이 서버의 이전 이력이 전혀 없으면(신규 서버) 기본값 사용:
    --   주기 스케쥴 → 이번 실행 시각 - 1주기(통보간격분)
    --   배치 스케쥴 → 당일 00:00:00

    AGGREGATE_TO          TIMESTAMP           NOT NULL,
    -- 이번 집계 구간의 종료 시각 = 통보 스케쥴 conf에 정의된 슬롯 시각(고정값)
    -- 스케쥴러가 실제로 폴링을 감지한 wall-clock 시각이 아니라, 매칭된 슬롯의 명목 시각을 사용
    -- (폴링 지연이 누적돼도 구간 경계가 흔들리지 않도록 함, collect/analyze 폴링 패턴과 동일 원칙)
    -- NOTIFY_STATUS가 SUCCESS/PARTIAL일 때만 다음 스케쥴의 AGGREGATE_FROM으로 사용됨

    -- --------------------------------------------------------
    -- 집계 결과 (TB_ANALYZE_RESULT 기준 카운트)
    -- --------------------------------------------------------
    NORMAL_COUNT          NUMERIC(10, 0)      DEFAULT 0,
    -- 집계 구간 내 ANALYZE_LEVEL='정상' 건수

    WARNING_COUNT         NUMERIC(10, 0)      DEFAULT 0,
    -- 집계 구간 내 ANALYZE_LEVEL='경고' 건수

    ERROR_COUNT           NUMERIC(10, 0)      DEFAULT 0,
    -- 집계 구간 내 ANALYZE_LEVEL='에러' 건수
    -- WARNING_COUNT + ERROR_COUNT > 0 인 경우에만 이 행이 생성됨

    -- --------------------------------------------------------
    -- TR 전송 결과 (수신자 단위)
    -- --------------------------------------------------------
    RECV_TOTAL_COUNT       NUMERIC(10, 0)      DEFAULT 0,
    -- 이번에 전송 시도한 전체 수신자 수 (전송대상 파일의 전화번호 수)

    RECV_SUCCESS_COUNT     NUMERIC(10, 0)      DEFAULT 0,
    -- submit TR write가 성공한 수신자 수 (응답(ack)이 없으므로 TCP write 성공 기준)

    RECV_FAIL_COUNT         NUMERIC(10, 0)      DEFAULT 0,
    -- submit 중 예외(연결 끊김 등)로 실패한 수신자 수

    -- --------------------------------------------------------
    -- 전송 내용
    -- --------------------------------------------------------
    NOTIFY_MESSAGE          VARCHAR(2000)       NOT NULL,
    -- 실제 전송한 메시지 본문
    -- 예) "[dlprem01-테스트개발] 정상5 경고2 에러1 (09:00~10:00)"
    -- TR의 message[80] 필드에는 EUC-KR 인코딩 후 80바이트로 자른 값이 들어가지만,
    -- 이 컬럼에는 자르기 전 원문을 보관해 감사/재현에 활용

    -- --------------------------------------------------------
    -- 실행 결과
    -- --------------------------------------------------------
    NOTIFY_STATUS           VARCHAR(10)         NOT NULL,
    -- 'SUCCESS' / 'PARTIAL' / 'FAIL'

    FAIL_REASON              VARCHAR(1000),
    -- 실패 사유 (FAIL 또는 PARTIAL일 때 기록)
    -- 예) "connect refused: 130.2.4.12:9000"
    -- 예) "submit 3/10건 실패: SocketException at idx=4,7,9"

    NOTIFY_START_AT           TIMESTAMP           NOT NULL,
    -- 이 서버에 대한 통보 처리(connect~submit 루프) 시작 시각

    NOTIFY_END_AT             TIMESTAMP,
    -- 통보 처리 종료 시각

    NOTIFY_DATE               VARCHAR(8)          NOT NULL,
    -- 통보 스케쥴 실행 날짜 (yyyyMMdd), 날짜별 조회용

    -- --------------------------------------------------------
    -- 공통 관리 컬럼
    -- --------------------------------------------------------
    CREATED_AT                TIMESTAMP           NOT NULL,
    UPDATED_AT                TIMESTAMP,

    -- --------------------------------------------------------
    -- 제약 조건
    -- --------------------------------------------------------
    CONSTRAINT PK_NOTIFY_HISTORY PRIMARY KEY (NOTIFY_HISTORY_ID),

    CONSTRAINT CK_NOTIFY_STATUS CHECK (
        NOTIFY_STATUS IN ('SUCCESS', 'FAIL', 'PARTIAL')
    )

);

-- ============================================================
-- TB_NOTIFY_HISTORY 인덱스
-- ============================================================

-- IDX_NH_01: 서버별 최신 워터마크 조회 (핵심)
-- 사용 케이스: "이 서버의 마지막 성공/부분성공 집계가 어디까지였는지"
CREATE INDEX IDX_NH_01 ON TB_NOTIFY_HISTORY (
    SERVER_ID,
    NOTIFY_STATUS,
    NOTIFY_START_AT DESC
);

-- IDX_NH_02: 날짜별 운영 현황 조회
CREATE INDEX IDX_NH_02 ON TB_NOTIFY_HISTORY (
    NOTIFY_DATE,
    NOTIFY_STATUS
);
```

---

## 6. 테이블 관계 정리

```
[분석서버]
TB_ANALYZE_RESULT (분석 결과)
    │
    │ SERVER_ID + ANALYZE_DATETIME 구간 + ANALYZE_LEVEL 기준 집계
    ↓
[통보서버]
TB_NOTIFY_HISTORY (통보 실행 이력)
    │
    │ NOTIFY_STATUS, AGGREGATE_TO 기준
    ├──→ 통보서버 자기 자신 (다음 스케쥴의 워터마크 조회)
    └──→ 운영자 (전송 실패 이력 추적)

TB_ANALYZE_RESULT.NOTIFY_YN
    └──→ 집계에 포함된 행(정상/경고/에러 전부) 'Y'로 갱신 (Dashboard 🔔 아이콘 정합성)
```

> 💡 **TB_ANALYZE_RESULT.NOTIFY_YN과 TB_NOTIFY_HISTORY의 역할 분리**
> NOTIFY_YN은 Dashboard가 "이 분석 결과가 통보됐는지" 보여주기 위한 행 단위 플래그이고,
> TB_NOTIFY_HISTORY.AGGREGATE_TO는 통보 서버 자신이 "어디까지 집계했는지" 판단하기 위한
> 서버 단위 워터마크다. 둘은 같은 시점에 함께 갱신되지만 용도가 다르므로 컬럼을 분리했다.

---

## 7. 통보 서버 주요 쿼리 패턴

### 7-1. 서버별 워터마크(AGGREGATE_FROM) 조회

```sql
-- 이 서버의 마지막 성공/부분성공 집계가 어디까지였는지 조회
-- 결과가 없으면(최초 실행) AGGREGATE_FROM 기본값을 사용:
--   주기 스케쥴 → 이번 실행 시각 - 1주기(통보간격분)
--   배치 스케쥴 → 당일 00:00:00
SELECT AGGREGATE_TO
FROM TB_NOTIFY_HISTORY
WHERE SERVER_ID = ?
  AND NOTIFY_STATUS IN ('SUCCESS', 'PARTIAL')
ORDER BY NOTIFY_START_AT DESC
FETCH FIRST 1 ROWS ONLY;
```

### 7-2. 집계 대상 서버 목록 + 구간별 레벨 카운트

```sql
-- TB_ANALYZE_RESULT에서 서버별로 존재하는 모든 SERVER_ID를 먼저 뽑고,
-- 서버마다 7-1로 구한 AGGREGATE_FROM ~ 이번 스케쥴 기준시각(AGGREGATE_TO) 구간을 집계
SELECT ANALYZE_LEVEL, COUNT(*) AS CNT
FROM TB_ANALYZE_RESULT
WHERE SERVER_ID = ?
  AND ANALYZE_LEVEL IN ('정상', '경고', '에러')
  AND ANALYZE_DATETIME > ?      -- AGGREGATE_FROM (직전 워터마크, exclusive)
  AND ANALYZE_DATETIME <= ?     -- AGGREGATE_TO (이번 스케쥴 기준시각, inclusive)
GROUP BY ANALYZE_LEVEL;
-- WARNING 건수 + ERROR 건수 = 0 이면 이 서버는 통보 대상에서 제외(SKIP)
```

### 7-3. 통보 이력 INSERT

```sql
INSERT INTO TB_NOTIFY_HISTORY (
    NOTIFY_HISTORY_ID,
    SERVER_ID,
    AGGREGATE_FROM,
    AGGREGATE_TO,
    NORMAL_COUNT,
    WARNING_COUNT,
    ERROR_COUNT,
    RECV_TOTAL_COUNT,
    RECV_SUCCESS_COUNT,
    RECV_FAIL_COUNT,
    NOTIFY_MESSAGE,
    NOTIFY_STATUS,
    FAIL_REASON,
    NOTIFY_START_AT,
    NOTIFY_END_AT,
    NOTIFY_DATE,
    CREATED_AT
) VALUES (
    ?,          -- SEQ_NOTIFY_HISTORY.NEXTVAL
    ?,          -- SERVER_ID
    ?,          -- AGGREGATE_FROM
    ?,          -- AGGREGATE_TO
    ?,          -- NORMAL_COUNT
    ?,          -- WARNING_COUNT
    ?,          -- ERROR_COUNT
    ?,          -- RECV_TOTAL_COUNT
    ?,          -- RECV_SUCCESS_COUNT
    ?,          -- RECV_FAIL_COUNT
    ?,          -- NOTIFY_MESSAGE
    ?,          -- NOTIFY_STATUS ('SUCCESS'/'PARTIAL'/'FAIL')
    ?,          -- FAIL_REASON (FAIL/PARTIAL일 때만)
    ?,          -- NOTIFY_START_AT
    ?,          -- NOTIFY_END_AT
    ?,          -- NOTIFY_DATE (yyyyMMdd)
    ?           -- CREATED_AT
);
```

### 7-4. 통보 완료된 분석 결과 NOTIFY_YN UPDATE

```sql
-- 이번 집계 구간에 포함된 행 전부 갱신 (정상/경고/에러 모두 대상)
UPDATE TB_ANALYZE_RESULT
SET NOTIFY_YN  = 'Y',
    NOTIFY_AT  = ?,            -- 현재 시각
    UPDATED_AT = ?             -- 현재 시각
WHERE SERVER_ID = ?
  AND ANALYZE_LEVEL IN ('정상', '경고', '에러')
  AND ANALYZE_DATETIME > ?     -- AGGREGATE_FROM
  AND ANALYZE_DATETIME <= ?;   -- AGGREGATE_TO
-- NOTIFY_STATUS가 FAIL이면 이 UPDATE 자체를 실행하지 않음 (시도 자체가 안됐으므로)
```

---

## 8. seq_no(TR) 발급 규칙

```
SMSSUBMIT_BODY.seq_no (char[8])는 SEQ_NOTIFY_TR_SEQNO.NEXTVAL을
8자리 0-padding(LPAD)한 문자열을 사용한다.
예) NEXTVAL=123 → "00000123"
시퀀스가 99999999를 넘으면 1로 순환(CYCLE)하므로,
운영 중 seq_no가 짧은 주기로 재사용될 수 있다는 점을 감안한다.
```

---

## 9. DDL 전체 실행 순서 (요약)

```sql
-- Step 1: SEQUENCE 생성
CREATE SEQUENCE SEQ_NOTIFY_HISTORY ...
CREATE SEQUENCE SEQ_NOTIFY_TR_SEQNO ...

-- Step 2: 테이블 생성
CREATE TABLE TB_NOTIFY_HISTORY ...

-- Step 3: 인덱스 생성
CREATE INDEX IDX_NH_01 ON TB_NOTIFY_HISTORY ...  -- 워터마크 조회 핵심
CREATE INDEX IDX_NH_02 ON TB_NOTIFY_HISTORY ...  -- 날짜별 운영 현황
```

---

## 10. 전체 DB 테이블 목록 (수집 + 분석 + 통보 통합)

| 테이블명 | 소속 | 역할 |
|---|---|---|
| TB_COLLECT_LOG | 수집서버 | 정규화 로그 저장 |
| TB_COLLECT_HISTORY | 수집서버 | 수집 실행 이력 |
| TB_COLLECT_EXCLUDE | 수집서버 | 영구 제외 대상 |
| TB_ANALYZE_RESULT | 분석서버 | 분석 결과 저장 |
| TB_ANALYZE_HISTORY | 분석서버 | 분석 실행 이력 |
| TB_NOTIFY_HISTORY | **통보서버** | **통보(SMS TR 전송) 실행 이력** |

---

## 11. 이 문서에서 다루지 않는 것

- SMS TR(BISUB_HEADER/SMSSUBMIT_BODY) 바이트 레이아웃, bind/submit 소켓 처리 절차, 필드별 채움값
  → `통보_TR연동정의서.md`에서 다룸 (이 문서는 DB 테이블 구조만 다룸)
- 통보 스케쥴 conf 파일 포맷
  → `통보_스케쥴러정의서.md`에서 다룸 (collect/analyze의 3__/7__ 문서와 동일 패턴)

---

## 12. 데이터 보관 정책

| 항목 | 정책 |
|---|---|
| TB_NOTIFY_HISTORY | DB 별도 보관 정책 따름 (수동 삭제 예정, 수집/분석 테이블과 동일 정책) |
