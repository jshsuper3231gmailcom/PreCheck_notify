# PreCheck 로그 분석 DB 정의서 v1.0

---

## 1. 문서 목적

이 문서는 PreCheck 로그 분석 서버에서 사용하는 DB 테이블 구조를 정의한다.  
분석 서버는 **수집 서버가 저장한 TB_COLLECT_LOG를 읽어서 분석**하고,  
분석 결과를 이 문서에서 정의한 테이블에 저장한다.  
저장된 분석 결과는 **결과 통보 서버**와 **Dashboard**에서 활용한다.

---

## 2. DB 호환성 원칙 (수집서버 DB 정의서와 동일)

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

## 3. 분석 서버 동작 흐름 (DB 관점)

```
[분석 서버 스케줄 실행]
        |
        ↓
① TB_COLLECT_LOG 조회          ← 수집서버 DB 테이블 (읽기 전용)
   (오늘 날짜, 미분석 로그)
        |
        ↓
② 로그 타입별 분석 수행
   - 문구: 에러 키워드 포함 여부 → 정상/에러
   - 수치: 임계치 비교          → 정상/경고/에러
   - 날짜: 오늘 날짜 비교       → 정상/에러
   - 존재: 로그 자체가 에러     → 에러
   - 정보: 분석 없이 저장       → 저장만
   - 비교: 두 수치 A==B 비교    → 정상/에러 (경고 없음)
   - 시간: HH:mm → 분 변환 후 임계치 비교 → 정상/에러 (경고 없음)
   - 정책 미등록: LOG_ID가 정책 파일에 없으면 → 미분석
        |
        ↓
③ TB_ANALYZE_RESULT INSERT     ← 분석 결과 저장 (이 문서)
        |
        ↓
④ TB_ANALYZE_HISTORY INSERT    ← 분석 실행 이력 저장 (이 문서)
        |
        ↓
⑤ 결과 통보 서버 → TB_ANALYZE_RESULT 조회 (에러/경고만)
   Dashboard    → TB_ANALYZE_RESULT 조회 (전체)
```

---

## 4. SEQUENCE 설계

```sql
-- ============================================================
-- SEQUENCE 정의
-- PostgreSQL과 Altibase 모두 동일한 SEQUENCE 문법 지원
-- ============================================================

-- 분석 결과 테이블용 시퀀스
-- NOCACHE : 캐시 미사용 (소규모 시스템, 데이터 손실 방지)
-- NOCYCLE : 최대값 도달 시 오류 발생 (시퀀스 재사용 방지)
CREATE SEQUENCE SEQ_ANALYZE_RESULT
    START WITH 1
    INCREMENT BY 1;

-- 분석 실행 이력 테이블용 시퀀스
CREATE SEQUENCE SEQ_ANALYZE_HISTORY
    START WITH 1
    INCREMENT BY 1;
```

---

## 5. 테이블 설계

### 5-1. TB_ANALYZE_RESULT (분석 결과 테이블)

**역할**: 로그 분석 서버가 수행한 분석 결과를 1건씩 저장하는 핵심 테이블  
**INSERT 주체**: 로그 분석 서버  
**SELECT 주체**: 결과 통보 서버 (에러/경고만), Dashboard (전체)  

```sql
-- ============================================================
-- TB_ANALYZE_RESULT : 분석 결과 저장 테이블
-- ============================================================
-- [설계 의도]
--   - 분석 서버가 TB_COLLECT_LOG 1건을 분석한 결과를 1행으로 저장
--   - TB_COLLECT_LOG.COLLECT_LOG_ID를 참조하여 원본 로그와 연결
--     (FK 제약은 없지만 COLLECT_LOG_ID를 반드시 함께 저장)
--   - 분석 레벨: 정상/경고/에러 (정보 타입은 분석 없이 저장만)
--   - 통보 서버는 ANALYZE_LEVEL = '에러' 또는 '경고' 인 행만 조회
--   - Dashboard는 날짜/서버/타입/레벨 기준으로 조회
--
-- [성능 고려]
--   - 분석 결과도 일일 최대 500건 수준으로 대용량 아님
--   - 통보 서버가 '미통보 에러/경고'를 빠르게 찾는 패턴이 핵심
--   - Dashboard의 날짜+서버+레벨 조회 패턴에 맞는 인덱스 설계
-- ============================================================

CREATE TABLE TB_ANALYZE_RESULT (

    -- --------------------------------------------------------
    -- PK
    -- --------------------------------------------------------
    ANALYZE_RESULT_ID   NUMERIC(19, 0)      NOT NULL,
    -- 분석 결과 고유 ID (SEQUENCE로 자동 생성)
    -- NUMERIC(19,0): PostgreSQL/Altibase 공통 큰 정수 타입
    -- BIGINT 대신 NUMERIC 사용 (Altibase 호환)

    -- --------------------------------------------------------
    -- 원본 로그 참조 정보 (수집서버 TB_COLLECT_LOG 참조)
    -- --------------------------------------------------------
    COLLECT_LOG_ID      NUMERIC(19, 0)      NOT NULL,
    -- 분석한 원본 로그의 ID (TB_COLLECT_LOG.COLLECT_LOG_ID)
    -- FK 제약은 없지만 반드시 실제 존재하는 값을 저장
    -- 이 값으로 원본 로그 내용(RAW_LOG 등)을 조회할 수 있음
    -- Dashboard에서 분석 결과 클릭 시 원본 로그 조회에 활용

    SERVER_ID           VARCHAR(100)        NOT NULL,
    -- 분석 대상 서버 구분명
    -- TB_COLLECT_LOG.SERVER_ID와 동일한 값
    -- Dashboard 서버별 조회 시 JOIN 없이 바로 필터링 가능하도록
    -- 비정규화하여 중복 저장 (조회 성능 우선)

    SERVER_IP           VARCHAR(15)         NOT NULL,
    -- 분석 대상 서버 IP
    -- TB_COLLECT_LOG.SERVER_IP와 동일한 값

    LOG_TYPE            VARCHAR(10)         NOT NULL,
    -- 분석한 로그의 입력 타입
    -- 허용값: '문구', '정보', '날짜', '수치', '존재', '비교', '시간'
    -- 타입별로 분석 방법이 다르므로 반드시 저장

    LOG_ID              VARCHAR(30)         NOT NULL,
    -- 로그 식별 코드 (TB_COLLECT_LOG.LOG_ID)
    -- 형식: 영문 대문자 + 숫자 + 언더스코어, 최대 30자
    -- 예) 'DISK_HOME', 'PROC_COUNT'
    -- 이 컬럼으로 어떤 분석 정책을 적용했는지 추적 가능
    -- Dashboard에서 LOG_ID별 통계 조회 시 활용

    LOG_TIMESTAMP       TIMESTAMP           NOT NULL,
    -- 원본 로그의 timestamp (TB_COLLECT_LOG.LOG_TIMESTAMP)
    -- Dashboard에서 시간순 정렬 조회 시 활용

    LOG_CONTENT         VARCHAR(4000)       NOT NULL,
    -- 원본 로그의 내용 (TB_COLLECT_LOG.LOG_CONTENT)
    -- Dashboard에서 분석 결과 목록 표시 시 조인 없이 바로 표시
    -- 비정규화 저장 (조회 편의성 우선)

    LOG_VALUE           NUMERIC(18, 6),
    -- 수치형 로그의 원본 값 (TB_COLLECT_LOG.LOG_VALUE)
    -- 수치 타입일 때만 값 있음, 나머지는 NULL
    -- Dashboard 그래프 표시 시 활용

    -- --------------------------------------------------------
    -- 분석 결과
    -- --------------------------------------------------------
    ANALYZE_LEVEL       VARCHAR(10)         NOT NULL,
    -- 분석 결과 레벨 (명세서 기준)
    -- 허용값:
    --   '정상'   : 분석 정책에 합당한 경우
    --   '경고'   : 임계치 미만이지만 근접한 경우 (수치 타입만 해당)
    --   '에러'   : 정책 불만족 (존재/수치 조건 미충족/문구 키워드 포함/날짜 불일치)
    --   '정보'   : 분석 없이 저장만 (정보 타입 전용)
    --   '미분석' : 분석 정책 파일에 LOG_ID가 등록되지 않은 경우
    --             → 운영자가 정책 파일 업데이트 필요
    --             → 통보 대상 제외
    -- 통보 서버: '에러', '경고'만 조회
    -- Dashboard: 전체 조회 (미분석도 표시하여 운영자 인지 가능)

    ANALYZE_MESSAGE     VARCHAR(2000)       NOT NULL,
    -- 분석 결과 설명 메시지 (Dashboard 표시 및 SMS 통보 내용)
    -- 명세서 예시 기준:
    --   예) "[에러] 프로세스수 10 < 20(임계치)"
    --   예) "[에러] test.txt 파일이 존재하지 않음"
    --   예) "[경고] 종목수 90 < 100(임계치 대비 20% 근접)"
    --   예) "[정상] home디스크 80 < 90(임계치)"
    -- SMS 통보 시 이 메시지를 그대로 활용
    -- [로그레벨] + 로그내용 활용???

    -- --------------------------------------------------------
    -- 수치형 분석 상세 (수치 타입 전용)
    -- --------------------------------------------------------
    THRESHOLD_VALUE     NUMERIC(18, 6),
    -- 비교 기준값 (임계치)
    -- 수치 타입일 때만 값 있음, 나머지는 NULL
    -- 예) 임계치가 90이면 90.000000 저장
    -- Dashboard 그래프에서 기준선 표시 시 활용

    THRESHOLD_OPERATOR  VARCHAR(5),
    -- 비교 연산자
    -- 허용값: '>', '>=', '<', '<=', '='
    -- 수치 타입일 때만 값 있음, 나머지는 NULL
    -- 예) 프로세스수 > 20 → THRESHOLD_OPERATOR = '>'
    -- 분석 결과 재현 시 참고 가능

    WARNING_RATIO       NUMERIC(5, 2),
    -- 경고 판정에 사용한 임계치 근접 비율 (%)
    -- 경고 레벨일 때만 값 있음, 나머지는 NULL
    -- 예) 임계치 100의 20% 이내 → WARNING_RATIO = 20.00
    -- 분석 서버가 파일에서 읽어온 설정값을 함께 저장

    -- --------------------------------------------------------
    -- 통보 관리 (결과 통보 서버에서 활용)
    -- --------------------------------------------------------
    NOTIFY_YN           CHAR(1)             NOT NULL DEFAULT 'N',
    -- 통보 서버가 이미 SMS 통보 요청을 생성했는지 여부
    -- 'N': 아직 통보 안됨 (통보 서버가 조회 대상으로 인식)
    -- 'Y': 통보 서버가 처리 완료 (재통보 방지)
    -- 통보 서버는 NOTIFY_YN='N' AND ANALYZE_LEVEL IN ('에러','경고') 조회

    NOTIFY_AT           TIMESTAMP,
    -- 통보 처리 완료 일시
    -- NOTIFY_YN을 'Y'로 변경할 때 함께 기록

    -- --------------------------------------------------------
    -- 분석 메타 정보
    -- --------------------------------------------------------
    ANALYZE_DATE        VARCHAR(8)          NOT NULL,
    -- 분석 실행 날짜 (yyyyMMdd)
    -- 예) "20260421"
    -- Dashboard 날짜별 조회 시 핵심 필터 컬럼
    -- VARCHAR(8) 사용 이유: 수집 DB와 동일한 설계 원칙 유지

    ANALYZE_DATETIME    TIMESTAMP           NOT NULL,
    -- 분석 서버가 이 행을 INSERT한 정확한 일시
    -- 분석 지연 파악, 장애 분석 시 활용

    COLLECT_DATE        VARCHAR(8)          NOT NULL,
    -- 원본 로그의 수집 날짜 (TB_COLLECT_LOG.COLLECT_DATE)
    -- 수집일과 분석일이 다를 수 있으므로 별도 저장
    -- 예) 전날 수집 로그를 다음날 분석하는 경우

    -- --------------------------------------------------------
    -- 공통 관리 컬럼
    -- --------------------------------------------------------
    CREATED_AT          TIMESTAMP           NOT NULL,
    -- 행 생성 일시

    UPDATED_AT          TIMESTAMP,
    -- 행 수정 일시 (NOTIFY_YN 변경 시 갱신)

    -- --------------------------------------------------------
    -- 제약 조건
    -- --------------------------------------------------------
    CONSTRAINT PK_ANALYZE_RESULT PRIMARY KEY (ANALYZE_RESULT_ID),

    CONSTRAINT CK_ANALYZE_LEVEL CHECK (
        ANALYZE_LEVEL IN ('정상', '경고', '에러', '정보', '미분석')
    ),
    -- 명세서에 정의된 5가지 레벨만 허용
    -- '정보'는 분석 레벨이 아니지만 저장용으로 포함
    -- '미분석'은 정책 파일에 등록되지 않은 LOG_ID 처리용

    CONSTRAINT CK_AR_LOG_TYPE CHECK (
        LOG_TYPE IN ('문구', '정보', '날짜', '수치', '존재', '비교', '시간')
    ),

    CONSTRAINT CK_NOTIFY_YN CHECK (
        NOTIFY_YN IN ('Y', 'N')
    ),

    CONSTRAINT CK_THRESHOLD_OPERATOR CHECK (
        THRESHOLD_OPERATOR IS NULL OR
        THRESHOLD_OPERATOR IN ('>', '>=', '<', '<=', '=')
    )
    -- 수치 타입 외에는 NULL 허용
    -- 수치 타입일 때는 반드시 위 5가지 중 하나

);

-- ============================================================
-- TB_ANALYZE_RESULT 인덱스
-- ============================================================

-- [인덱스 설계 원칙]
--   통보 서버와 Dashboard의 주요 조회 패턴 기준으로 설계
--   불필요한 인덱스는 INSERT 성능 저하 유발 → 최소화

-- IDX_AR_01: 통보 서버 핵심 인덱스
-- 사용 케이스: "아직 통보 안된 에러/경고 전체 조회"
--   → SELECT * WHERE NOTIFY_YN='N' AND ANALYZE_LEVEL IN ('에러','경고')
-- 선두 컬럼을 NOTIFY_YN으로 설정 → 'N'인 소수 건만 빠르게 필터링
CREATE INDEX IDX_AR_01 ON TB_ANALYZE_RESULT (
    NOTIFY_YN,          -- 선두: 통보 여부로 빠른 필터링
    ANALYZE_LEVEL,      -- 두번째: 에러/경고 필터링
    ANALYZE_DATE        -- 세번째: 날짜 기준 정렬
);

-- IDX_AR_02: Dashboard 날짜+서버+레벨 복합 인덱스
-- 사용 케이스: "오늘 특정 서버의 에러/경고 목록 조회"
--   → SELECT * WHERE ANALYZE_DATE=? AND SERVER_ID=? ORDER BY LOG_TIMESTAMP
CREATE INDEX IDX_AR_02 ON TB_ANALYZE_RESULT (
    ANALYZE_DATE,       -- 선두: 날짜별 조회가 가장 빈번
    SERVER_ID,          -- 두번째: 서버별 필터링
    ANALYZE_LEVEL       -- 세번째: 레벨별 필터링
);

-- IDX_AR_03: 원본 로그 ID 인덱스
-- 사용 케이스: "특정 수집 로그의 분석 결과 조회"
--   → Dashboard에서 원본 로그 ↔ 분석 결과 연결 시
CREATE INDEX IDX_AR_03 ON TB_ANALYZE_RESULT (
    COLLECT_LOG_ID
);

-- IDX_AR_04: 날짜+타입+LOG_ID 인덱스
-- 사용 케이스: "특정 LOG_ID의 분석 결과 시계열 조회" (Dashboard 그래프)
--   → 예) DISK_HOME의 최근 7일 추이 그래프
CREATE INDEX IDX_AR_04 ON TB_ANALYZE_RESULT (
    ANALYZE_DATE,
    LOG_TYPE,
    LOG_ID
);
```

---

### 5-2. TB_ANALYZE_HISTORY (분석 실행 이력 테이블)

**역할**: 분석 서버가 스케줄을 실행한 이력을 기록하는 테이블  
**INSERT 주체**: 로그 분석 서버  
**SELECT 주체**: Dashboard (분석 실행 현황 조회)  

```sql
-- ============================================================
-- TB_ANALYZE_HISTORY : 분석 실행 이력 테이블
-- ============================================================
-- [설계 의도]
--   - 분석 서버의 스케줄 실행 결과를 이력으로 기록
--   - 분석 서버가 어느 시점까지 분석했는지 추적 가능
--   - 분석 실패 시 어떤 로그에서 실패했는지 기록
--   - Dashboard에서 "분석 서버가 정상 동작 중인지" 확인 가능
--   - 수집서버의 TB_COLLECT_HISTORY와 동일한 설계 패턴 사용
--
-- [성능 고려]
--   - 스케줄 실행마다 1건 INSERT
--   - 분석 대상이 서버별/타입별로 실행될 수 있으므로
--     한 스케줄 실행에 여러 행이 생길 수 있음
--   - 날짜+상태 기준 조회 패턴에 맞는 인덱스 설계
-- ============================================================

CREATE TABLE TB_ANALYZE_HISTORY (

    -- --------------------------------------------------------
    -- PK
    -- --------------------------------------------------------
    ANALYZE_HISTORY_ID  NUMERIC(19, 0)      NOT NULL,
    -- 분석 이력 고유 ID (SEQUENCE로 자동 생성)

    -- --------------------------------------------------------
    -- 분석 실행 대상 정보
    -- --------------------------------------------------------
    SERVER_ID           VARCHAR(100),
    -- 분석 대상 서버 구분명
    -- 서버 전체를 한번에 분석하면 NULL 가능
    -- 서버별로 분석을 분리 실행하는 경우 해당 SERVER_ID 저장

    ANALYZE_TARGET_DATE VARCHAR(8)          NOT NULL,
    -- 분석 대상 날짜 (어느 날짜의 수집 로그를 분석했는지)
    -- 예) "20260421" → 20260421 수집된 로그를 분석
    -- 수집일과 분석 실행일이 다를 수 있으므로 명시적으로 저장

    SOURCE_FILE_PATH    VARCHAR(500),
    -- 분석 대상 파일 절대경로
    -- 파일 단위 분석 스케줄인 경우 해당 파일 경로 저장
    -- 서버 단위 분석 스케줄인 경우 NULL
    -- 분석 스케줄 정의서의 [대상파일명]과 동일한 값

    LAST_ANALYZE_LOG_ID NUMERIC(19, 0),
    -- 이번 분석에서 마지막으로 분석한 TB_COLLECT_LOG.COLLECT_LOG_ID
    -- 주기 분석에서 핵심 역할:
    --   다음 주기 분석 시 이 ID 이후의 수집 로그만 조회하여 분석
    --   → 중복 분석 방지 및 분석 서버 재시작 후 안전한 이어서 분석 보장
    -- 배치 분석도 마지막 분석한 ID를 기록 (이력 추적 목적)
    -- 분석 대상 로그가 없는 경우 NULL

    -- --------------------------------------------------------
    -- 분석 실행 결과
    -- --------------------------------------------------------
    ANALYZE_STATUS      VARCHAR(10)         NOT NULL,
    -- 분석 실행 결과 상태
    -- 허용값:
    --   'SUCCESS' : 분석 정상 완료
    --   'FAIL'    : 분석 실패 (예외 발생 등)
    --   'PARTIAL' : 일부 성공, 일부 실패 (여러 서버 중 일부 실패)

    TOTAL_COUNT         NUMERIC(10, 0)      DEFAULT 0,
    -- 이번 분석 실행에서 처리한 총 로그 건수
    -- TB_COLLECT_LOG에서 조회한 대상 로그 수

    SUCCESS_COUNT       NUMERIC(10, 0)      DEFAULT 0,
    -- 분석 성공 건수 (정상/경고/에러/정보 판정 완료된 건수)

    FAIL_COUNT          NUMERIC(10, 0)      DEFAULT 0,
    -- 분석 실패 건수 (파싱 오류, 예외 등으로 판정 못한 건수)

    ERROR_COUNT         NUMERIC(10, 0)      DEFAULT 0,
    -- 분석 결과 중 '에러' 레벨로 판정된 건수
    -- Dashboard에서 "오늘 에러 몇 건?" 표시 시 활용

    WARNING_COUNT       NUMERIC(10, 0)      DEFAULT 0,
    -- 분석 결과 중 '경고' 레벨로 판정된 건수

    FAIL_REASON         VARCHAR(1000),
    -- 분석 실패 사유 (FAIL 또는 PARTIAL 상태일 때 기록)
    -- 예) "DB 연결 실패: Altibase connection timeout"
    -- 예) "로그 파싱 오류: SERVER_ID=dlprem01, COLLECT_LOG_ID=123"

    -- --------------------------------------------------------
    -- 분석 실행 시각
    -- --------------------------------------------------------
    ANALYZE_START_AT    TIMESTAMP           NOT NULL,
    -- 분석 시작 일시

    ANALYZE_END_AT      TIMESTAMP,
    -- 분석 종료 일시
    -- 분석 진행 중일 때는 NULL

    ANALYZE_DATE        VARCHAR(8)          NOT NULL,
    -- 분석 실행 날짜 (yyyyMMdd)
    -- ANALYZE_TARGET_DATE(분석 대상 날짜)와 다를 수 있음
    -- 예) 20260422에 실행해서 20260421 로그를 분석하는 경우
    --     ANALYZE_DATE='20260422', ANALYZE_TARGET_DATE='20260421'

    -- --------------------------------------------------------
    -- 공통 관리 컬럼
    -- --------------------------------------------------------
    CREATED_AT          TIMESTAMP           NOT NULL,
    -- 행 생성 일시

    UPDATED_AT          TIMESTAMP,
    -- 행 수정 일시 (분석 종료 후 ANALYZE_END_AT 업데이트 시)

    -- --------------------------------------------------------
    -- 제약 조건
    -- --------------------------------------------------------
    CONSTRAINT PK_ANALYZE_HISTORY PRIMARY KEY (ANALYZE_HISTORY_ID),

    CONSTRAINT CK_ANALYZE_STATUS CHECK (
        ANALYZE_STATUS IN ('SUCCESS', 'FAIL', 'PARTIAL')
    )

);

-- ============================================================
-- TB_ANALYZE_HISTORY 인덱스
-- ============================================================

-- IDX_AH_01: 날짜 + 상태 인덱스
-- 사용 케이스: "오늘 분석 실행 이력 전체 조회" (Dashboard 운영 현황)
CREATE INDEX IDX_AH_01 ON TB_ANALYZE_HISTORY (
    ANALYZE_DATE,
    ANALYZE_STATUS
);

-- IDX_AH_02: 분석 대상 날짜 + 서버 + 파일 인덱스
-- 사용 케이스: "특정 서버/파일의 가장 최근 분석 이력 조회"
--   → 주기 분석 시 LAST_ANALYZE_LOG_ID를 가져오기 위한 핵심 조회
--   → SELECT ... WHERE SERVER_ID=? AND SOURCE_FILE_PATH=? ORDER BY ANALYZE_START_AT DESC LIMIT 1
CREATE INDEX IDX_AH_02 ON TB_ANALYZE_HISTORY (
    SERVER_ID,
    SOURCE_FILE_PATH,
    ANALYZE_START_AT DESC  -- 최신 이력을 빠르게 찾기 위해 내림차순
);
```

---

## 6. 테이블 관계 정리

```
[수집서버]
TB_COLLECT_LOG (수집 로그)
    │
    │ COLLECT_LOG_ID 참조 (FK 없음, 앱 레벨 관리)
    ↓
[분석서버]
TB_ANALYZE_RESULT (분석 결과)
    │
    │ ANALYZE_RESULT_ID, NOTIFY_YN 기준
    ├──→ 결과 통보 서버 (NOTIFY_YN='N', ANALYZE_LEVEL='에러'/'경고')
    └──→ Dashboard (날짜/서버/레벨별 전체 조회)

TB_ANALYZE_HISTORY (분석 실행 이력)
    └──→ Dashboard (분석 서버 실행 현황 조회)
```

> 💡 **비정규화 설계 이유**  
> TB_ANALYZE_RESULT에 SERVER_ID, LOG_CONTENT 등을 TB_COLLECT_LOG에서  
> 복사해서 저장하는 이유는 **Dashboard 조회 시 JOIN을 없애기 위해서야.**  
> 일일 500건 수준이라 중복 저장해도 공간 부담이 없고,  
> 조회 성능과 코드 단순성이 훨씬 좋아져.

---

## 7. 분석 서버 주요 쿼리 패턴

### 7-1. 분석 대상 로그 조회 (TB_COLLECT_LOG에서 읽기)

```sql
-- [배치 분석] 분석 서버가 수집 서버 DB에서 분석할 로그를 가져오는 쿼리
-- 이미 TB_ANALYZE_RESULT에 있는 로그는 제외 (중복 분석 방지)
SELECT CL.*
FROM TB_COLLECT_LOG CL
WHERE CL.COLLECT_DATE = ?          -- 분석 대상 날짜
  AND CL.SERVER_ID = ?             -- 서버 단위 분석 시
  -- AND CL.SOURCE_FILE_PATH = ?   -- 파일 단위 분석 시 추가
  AND NOT EXISTS (
      SELECT 1
      FROM TB_ANALYZE_RESULT AR
      WHERE AR.COLLECT_LOG_ID = CL.COLLECT_LOG_ID
  )
ORDER BY CL.COLLECT_LOG_ID ASC;

-- [주기 분석] LAST_ANALYZE_LOG_ID 이후의 수집 로그만 조회
-- TB_ANALYZE_HISTORY에서 마지막으로 분석한 COLLECT_LOG_ID 조회 후 사용
SELECT CL.*
FROM TB_COLLECT_LOG CL
WHERE CL.SERVER_ID = ?
  -- AND CL.SOURCE_FILE_PATH = ?   -- 파일 단위 분석 시 추가
  AND CL.COLLECT_LOG_ID > ?        -- LAST_ANALYZE_LOG_ID 이후만 조회
ORDER BY CL.COLLECT_LOG_ID ASC;
```

### 7-1-1. 주기 분석 시 마지막 분석 ID 조회

```sql
-- 주기 분석에서 핵심 쿼리
-- 이전 성공 분석의 마지막 COLLECT_LOG_ID를 가져와서
-- 그 다음 ID부터 분석 시작 (중복 분석 방지)
-- IDX_AH_02 인덱스 활용

-- 서버 단위 분석 시
SELECT LAST_ANALYZE_LOG_ID
FROM TB_ANALYZE_HISTORY
WHERE SERVER_ID = ?
  AND SOURCE_FILE_PATH IS NULL      -- 서버 단위는 파일경로 NULL
  AND ANALYZE_STATUS = 'SUCCESS'
ORDER BY ANALYZE_START_AT DESC
LIMIT 1;

-- 파일 단위 분석 시
SELECT LAST_ANALYZE_LOG_ID
FROM TB_ANALYZE_HISTORY
WHERE SERVER_ID = ?
  AND SOURCE_FILE_PATH = ?
  AND ANALYZE_STATUS = 'SUCCESS'
ORDER BY ANALYZE_START_AT DESC
LIMIT 1;
-- → 결과가 없으면 (최초 실행) → TB_COLLECT_LOG 전체 조회
-- → 결과가 있으면 → COLLECT_LOG_ID > LAST_ANALYZE_LOG_ID 조건으로 조회
```

### 7-2. 분석 결과 INSERT

```sql
-- 분석 결과 1건 INSERT
-- SEQUENCE 값은 애플리케이션에서 nextval로 미리 조회 후 사용
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID,
    COLLECT_LOG_ID,
    SERVER_ID,
    SERVER_IP,
    LOG_TYPE,
    LOG_ID,
    LOG_TIMESTAMP,
    LOG_CONTENT,
    LOG_VALUE,
    ANALYZE_LEVEL,
    ANALYZE_MESSAGE,
    THRESHOLD_VALUE,
    THRESHOLD_OPERATOR,
    WARNING_RATIO,
    NOTIFY_YN,
    ANALYZE_DATE,
    ANALYZE_DATETIME,
    COLLECT_DATE,
    CREATED_AT
) VALUES (
    ?,          -- SEQ_ANALYZE_RESULT.NEXTVAL
    ?,          -- COLLECT_LOG_ID (원본 로그 ID)
    ?,          -- SERVER_ID
    ?,          -- SERVER_IP
    ?,          -- LOG_TYPE ('문구','정보','날짜','수치','존재','비교','시간')
    ?,          -- LOG_ID (예: 'DISK_HOME')
    ?,          -- LOG_TIMESTAMP (원본 로그 timestamp)
    ?,          -- LOG_CONTENT (원본 로그 내용)
    ?,          -- LOG_VALUE (수치 타입이 아니면 NULL)
    ?,          -- ANALYZE_LEVEL ('정상','경고','에러','정보')
    ?,          -- ANALYZE_MESSAGE (분석 결과 설명)
    ?,          -- THRESHOLD_VALUE (수치 타입이 아니면 NULL)
    ?,          -- THRESHOLD_OPERATOR (수치 타입이 아니면 NULL)
    ?,          -- WARNING_RATIO (경고 레벨이 아니면 NULL)
    'N',        -- NOTIFY_YN (기본값 'N', 통보 서버가 처리 후 'Y'로 변경)
    ?,          -- ANALYZE_DATE (yyyyMMdd)
    ?,          -- ANALYZE_DATETIME (현재 시각)
    ?,          -- COLLECT_DATE (원본 로그 수집 날짜)
    ?           -- CREATED_AT (현재 시각)
);
```

### 7-3. 통보 서버용 미통보 에러/경고 조회

```sql
-- 결과 통보 서버가 이 쿼리로 SMS 통보 대상을 조회함
-- IDX_AR_01 인덱스 활용
SELECT *
FROM TB_ANALYZE_RESULT
WHERE NOTIFY_YN = 'N'
  AND ANALYZE_LEVEL IN ('에러', '경고')
  AND ANALYZE_DATE = ?              -- 오늘 날짜
ORDER BY ANALYZE_DATETIME ASC;
-- → NOTIFY_YN='N' 인 것만 조회 → 중복 통보 방지
-- → 통보 서버가 INSERT 후 NOTIFY_YN='Y'로 UPDATE
```

### 7-4. 통보 완료 UPDATE

```sql
-- 통보 서버가 SMS 요청 INSERT 완료 후 이 쿼리 실행
UPDATE TB_ANALYZE_RESULT
SET NOTIFY_YN   = 'Y',
    NOTIFY_AT   = ?,            -- 현재 시각
    UPDATED_AT  = ?             -- 현재 시각
WHERE ANALYZE_RESULT_ID = ?;
-- → 1건씩 처리하여 누락 방지
```

### 7-5. Dashboard 날짜별 분석 결과 조회

```sql
-- Dashboard 메인 화면: 오늘 분석 결과 전체 조회
-- IDX_AR_02 인덱스 활용
SELECT *
FROM TB_ANALYZE_RESULT
WHERE ANALYZE_DATE = ?
  AND SERVER_ID = ?              -- 서버 필터 (선택)
ORDER BY LOG_TIMESTAMP ASC;

-- Dashboard 그래프: 오늘 수치형 로그 값 조회
-- IDX_AR_04 인덱스 활용
SELECT SERVER_ID,
       LOG_CONTENT,
       LOG_VALUE,
       THRESHOLD_VALUE,
       ANALYZE_LEVEL,
       LOG_TIMESTAMP
FROM TB_ANALYZE_RESULT
WHERE ANALYZE_DATE = ?
  AND LOG_TYPE = '수치'
ORDER BY LOG_TIMESTAMP ASC;
```

---

## 8. 로그 타입별 ANALYZE_MESSAGE 작성 기준

```
분석 서버가 TB_ANALYZE_RESULT.ANALYZE_MESSAGE를 작성할 때의 형식 기준
모든 메시지는 [레벨][LOG_ID]로 시작하여 어떤 항목의 분석인지 명확히 표시

[문구 타입]
  정상: "[정상][{LOG_ID}] {LOG_CONTENT}"
  에러: "[에러][{LOG_ID}] {LOG_CONTENT} (키워드: {매칭된 키워드})"
  예)   "[에러][PUSH_STATUS] PUSH 전송 실패(PUSH 서버 실패 O) (키워드: 실패)"

[수치 타입]
  정상: "[정상][{LOG_ID}] {LOG_CONTENT} {LOG_VALUE} {OPERATOR} {THRESHOLD_VALUE}(임계치)"
  경고: "[경고][{LOG_ID}] {LOG_CONTENT} {LOG_VALUE} {OPERATOR} {THRESHOLD_VALUE}(임계치 대비 {RATIO}% 근접)"
  에러: "[에러][{LOG_ID}] {LOG_CONTENT} {LOG_VALUE} {반대OPERATOR} {THRESHOLD_VALUE}(임계치)"
  예)   "[에러][PROC_COUNT] 프로세스수 10 < 20(임계치)"
  예)   "[경고][STOCK_COUNT] 종목수 90 < 100(임계치 대비 20% 근접)"

[날짜 타입]
  정상: "[정상][{LOG_ID}] {LOG_CONTENT} (오늘 날짜 일치)"
  에러: "[에러][{LOG_ID}] {LOG_CONTENT} (날짜 불일치: 기대={오늘}, 실제={로그내날짜})"

[존재 타입]
  에러: "[에러][{LOG_ID}] {LOG_CONTENT}"
  예)   "[에러][MEM_MBSOSI] mbsosi메모리 없음"

[정보 타입]
  정보: "[정보][{LOG_ID}] {LOG_CONTENT}"

[비교 타입]
  정상: "[정상][{LOG_ID}] {LOG_CONTENT} (A={수치A}, B={수치B}, 일치)"
  에러: "[에러][{LOG_ID}] {LOG_CONTENT} (A={수치A}, B={수치B}, 불일치)"
  예)   "[에러][JUCHE_DIFF_01] 실시간정산 #1 수신$123$ 처리$120$ (A=123, B=120, 불일치)"

[시간 타입]
  정상: "[정상][{LOG_ID}] {LOG_CONTENT} {시간값} {OPERATOR} {임계치시간}(임계치)"
  에러: "[에러][{LOG_ID}] {LOG_CONTENT} {시간값} {반대OPERATOR} {임계치시간}(임계치)"
  예)   "[에러][DATE_BTIME] 처리시간 $09:10$ >= 08:00(임계치)"

[미분석 - 정책 미등록 LOG_ID]
  미분석: "[미분석][{LOG_ID}] 분석 정책 미등록"
  예)     "[미분석][NEW_LOG_ID] 분석 정책 미등록"
  -> 운영자가 정책 파일에 해당 LOG_ID 추가 필요
  -> 통보 서버 대상에서 제외됨
```

---

## 9. Spring Boot JPA 연동 시 고려사항

### 9-1. SEQUENCE 설정 예시 (Java Entity)

```java
// 분석 결과 엔티티 PK 생성 예시
@Id
@GeneratedValue(
    strategy = GenerationType.SEQUENCE,
    generator = "analyzeResultSeq"
)
@SequenceGenerator(
    name = "analyzeResultSeq",
    sequenceName = "SEQ_ANALYZE_RESULT",   // DB SEQUENCE 명
    allocationSize = 1                      // NOCACHE와 동일하게 1씩
)
private Long analyzeResultId;
```

### 9-2. NOTIFY_YN 처리 시 동시성 주의

```java
// 통보 서버가 NOTIFY_YN='N' 조회 → SMS INSERT → NOTIFY_YN='Y' UPDATE 시
// 동시에 두 번 실행되면 중복 통보 발생 가능
// → 1차 버전은 단일 스레드 순차 실행으로 구현 (동시성 문제 없음)
// → 향후 다중 스레드 구성 시 SELECT ... FOR UPDATE 또는 버전 컬럼 도입 검토

// 현재 권장: 통보 서버 스케줄을 단일 스레드로 실행
@Scheduled(fixedDelay = 300000)  // 5분마다 실행 (중첩 실행 방지)
public void notifyResult() { ... }
```

---

## 10. DDL 전체 실행 순서 (요약)

```sql
-- Step 1: SEQUENCE 생성
CREATE SEQUENCE SEQ_ANALYZE_RESULT ...
CREATE SEQUENCE SEQ_ANALYZE_HISTORY ...

-- Step 2: 테이블 생성
CREATE TABLE TB_ANALYZE_RESULT ...
CREATE TABLE TB_ANALYZE_HISTORY ...

-- Step 3: 인덱스 생성
CREATE INDEX IDX_AR_01 ON TB_ANALYZE_RESULT ...   -- 통보서버 핵심
CREATE INDEX IDX_AR_02 ON TB_ANALYZE_RESULT ...   -- Dashboard 핵심
CREATE INDEX IDX_AR_03 ON TB_ANALYZE_RESULT ...   -- 원본 로그 연결
CREATE INDEX IDX_AR_04 ON TB_ANALYZE_RESULT ...   -- 그래프용
CREATE INDEX IDX_AH_01 ON TB_ANALYZE_HISTORY ...  -- 날짜+상태
CREATE INDEX IDX_AH_02 ON TB_ANALYZE_HISTORY ...  -- 대상날짜+서버
```

---

## 11. 전체 DB 테이블 목록 (수집 + 분석 통합)

| 테이블명 | 소속 | 역할 |
|---|---|---|
| TB_COLLECT_LOG | 수집서버 | 정규화 로그 저장 |
| TB_COLLECT_HISTORY | 수집서버 | 수집 실행 이력 |
| TB_COLLECT_EXCLUDE | 수집서버 | 영구 제외 대상 |
| TB_ANALYZE_RESULT | **분석서버** | **분석 결과 저장** |
| TB_ANALYZE_HISTORY | **분석서버** | **분석 실행 이력** |

> 💡 **1차 버전 범위**  
> 통보서버 테이블(TB_NOTIFY_*)은 통보서버 DB 정의서에서 별도 정의 예정  
> Dashboard는 위 테이블을 직접 조회하므로 별도 테이블 없음 (1차 기준)

---

## 12. 데이터 보관 정책

| 항목 | 정책 |
|---|---|
| TB_ANALYZE_RESULT | DB 별도 보관 정책 따름 (수동 삭제 예정) |
| TB_ANALYZE_HISTORY | DB 별도 보관 정책 따름 |
