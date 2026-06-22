-- ------------------------------------------------------------
-- [A] 통보가 가려면 충족해야 하는 조건 2가지 (컬럼 기준)
-- ------------------------------------------------------------
--   1) ANALYZE_LEVEL   : 같은 SERVER_ID 안에 '경고' 또는 '에러' 행이 최소 1건 있어야 함
--                         ('정상'만 있으면 그 서버는 조용히 스킵됨 -- TB_NOTIFY_HISTORY 행도 안 생김)
--   2) ANALYZE_DATETIME : 그 '경고'/'에러' 행의 시각이 "이번 스케쥴 집계구간" 안에 들어와야 함
--                         (구간 정의는 [B] 참고 -- 테스트 실패의 90%는 여기서 어긋남)
--
-- ------------------------------------------------------------
-- [B] "이번 스케쥴 집계구간" = (AGGREGATE_FROM, AGGREGATE_TO] 정의
-- ------------------------------------------------------------
--   AGGREGATE_TO   = schedule_sample\PreCheck_NotifyLogs_Schedule.conf 에 적힌 "슬롯 시각" 그대로
--                    (notify를 실행한 실제 시계 시각이 아니라 conf 문자열에 박힌 시각을 그대로 씀)
--                      배치 [배치|요일|시작시간]              -> 그 날의 시작시간
--                      주기 [주기|요일|시작시간|간격분|종료시간] -> 시작시간 + N*간격분(지금 폴링중인 슬롯)
--   AGGREGATE_FROM = TB_NOTIFY_HISTORY에서 이 SERVER_ID의 마지막 SUCCESS/PARTIAL 행의 AGGREGATE_TO
--                    그 SERVER_ID로 이력이 전혀 없으면(=한 번도 안 써본 새 SERVER_ID) 기본값 사용:
--                      배치 스케쥴 -> 당일 00:00:00                (구간이 넓음, 하루치 전부 포함)
--                      주기 스케쥴 -> 이번 슬롯 시각 - 간격(분)     (구간이 좁음, 방금 넣은 데이터만 포함)
--
-- ------------------------------------------------------------
-- [C] schedule_sample\PreCheck_NotifyLogs_Schedule.conf 와 맞춰서 테스트하는 법
-- ------------------------------------------------------------
--   그 conf 파일을 열어서 #(skip) 안 붙은 줄이 배치인지 주기인지 먼저 확인한다.
--
--   배치 [배치|*|HHmmss] 로 테스트할 때
--     -> HHmmss를 지금 시각보다 1~2분 뒤로 수정해둔다 (예: 지금 14:32면 143401)
--     -> 새 SERVER_ID라면 구간이 "당일 00:00:00 ~ HHmmss"로 넓으므로, 아래 TEST-LOCAL-01/02처럼
--        ANALYZE_DATETIME을 오늘 날짜의 임의 시각(00:00~설정한 HHmmss 이전)으로 넣으면 됨
--     -> 단, 같은 [배치|...|HHmmss] 줄은 하루에 한 번만 실행됨(중복실행 방지) -- 재테스트하려면
--        HHmmss를 또 다른 시각으로 바꿔야 함
--
--   주기 [주기|요일|시작시간|간격분|종료시간] 로 테스트할 때 (계속 자동으로 도니까 더 편함)
--     -> 적힌 요일에 해당하는 날에만 도니까(예: 1-5=월~금), 요일이 안 맞으면 절대 안 잡힘
--     -> 구간이 "간격분" 만큼만 좁게 잡히므로, ANALYZE_DATETIME을 고정된 과거 시각(예: 09:10)으로
--        넣으면 거의 항상 구간 밖으로 새버림 -- 이 경로로 테스트할 땐 ANALYZE_DATETIME을
--        CURRENT_TIMESTAMP(지금) 으로 넣고, 다음 슬롯이 돌 때까지(최대 간격분 + 폴링지연 60초)
--        기다리는 게 제일 안전함 -- 아래 TEST-LOCAL-03 참고
--
-- ------------------------------------------------------------
-- [D] 이 파일에서 실제로 채워야 하는 컬럼 (나머지는 통보 여부에 영향 없음)
-- ------------------------------------------------------------
--   SERVER_ID         : 자유롭게 정함(예: TEST-LOCAL-04). 처음 쓰는 값이면 "신규 서버"로 취급되어
--                        [B]의 기본 AGGREGATE_FROM이 적용됨
--   ANALYZE_LEVEL      : 최소 1행은 '경고' 또는 '에러'로 ([A]-1)
--   ANALYZE_DATETIME   : [B]/[C]에서 정한 구간 안의 시각으로 ([A]-2)
--   ANALYZE_DATE / COLLECT_DATE : ANALYZE_DATETIME과 같은 날짜의 yyyyMMdd
--                        (아래 예시들은 to_char(..., 'YYYYMMDD')로 자동 계산해서 넣음)
--   NOTIFY_YN          : 통보 트리거 조건이 아님 -- 그냥 'N'으로 넣으면 됨
--                        (의미는 "아직 통보 처리 전" 표시일 뿐, 집계 쿼리는 이 컬럼을 안 봄)
--   나머지(COLLECT_LOG_ID, SERVER_IP, LOG_TYPE, LOG_ID, LOG_CONTENT, ANALYZE_MESSAGE, THRESHOLD_*) :
--     TB_ANALYZE_RESULT의 NOT NULL 제약만 채우면 됨, 값 자체는 통보 여부와 무관함
--
-- ------------------------------------------------------------
-- [E] 같은 SERVER_ID로 두 번째 테스트할 때 주의
-- ------------------------------------------------------------
--   한 번 SUCCESS/PARTIAL로 끝난 SERVER_ID는 다음부터 AGGREGATE_FROM이 그 결과의 AGGREGATE_TO로
--   전진해 있음. 같은 SERVER_ID를 재사용하려면 새 행의 ANALYZE_DATETIME이
--     SELECT MAX(AGGREGATE_TO) FROM TB_NOTIFY_HISTORY WHERE SERVER_ID='...'
--   보다 이후 시각이어야 함. 헷갈리면 그냥 테스트마다 새 SERVER_ID를 쓰는 게 제일 간단함
--   (TEST-LOCAL-01, -02, -03, -04 ... 처럼 번호만 올려서).
--

-- ------------------------------------------------------------
-- TEST-LOCAL-01 : 정상2 + 경고1 + 에러2 -> 통보 대상
-- ------------------------------------------------------------

-- 정상 - 수치(home디스크)
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990001, 'TEST-LOCAL-01', '192.168.99.101', '수치', 'DISK_HOME',
    CURRENT_DATE + TIME '09:10:00', 'home disk usage = 80%', 80.000000, '정상', '[정상] home디스크 80 < 90(임계치)',
    90.000000, '<', NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:10:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:10:00'
);

-- 정상 - 수치(프로세스수)
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990002, 'TEST-LOCAL-01', '192.168.99.101', '수치', 'PROC_COUNT',
    CURRENT_DATE + TIME '09:12:00', 'process count = 25', 25.000000, '정상', '[정상] 프로세스수 25 > 20(임계치)',
    20.000000, '>', NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:12:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:12:00'
);

-- 경고 - 수치(종목수, 임계치 20% 근접)
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990003, 'TEST-LOCAL-01', '192.168.99.101', '수치', 'CAP_REG_COUNT',
    CURRENT_DATE + TIME '09:15:00', 'registered symbol count = 90', 90.000000, '경고', '[경고] 종목수 90 < 100(임계치 대비 20% 근접)',
    100.000000, '<', 20.00, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:15:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:15:00'
);

-- 에러 - 수치(프로세스수 미달)
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990004, 'TEST-LOCAL-01', '192.168.99.101', '수치', 'PROC_COUNT',
    CURRENT_DATE + TIME '09:20:00', 'process count = 10', 10.000000, '에러', '[에러] 프로세스수 10 < 20(임계치)',
    20.000000, '<', NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:20:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:20:00'
);

-- 에러 - 존재(파일 없음)
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990005, 'TEST-LOCAL-01', '192.168.99.101', '존재', 'FILE_EXIST',
    CURRENT_DATE + TIME '09:25:00', 'test.txt not found', NULL, '에러', '[에러] test.txt 파일이 존재하지 않음',
    NULL, NULL, NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:25:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:25:00'
);


-- ------------------------------------------------------------
-- TEST-LOCAL-02 : 정상2만 -> 경고+에러=0, 통보 대상에서 제외(스킵)
-- ------------------------------------------------------------

INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990006, 'TEST-LOCAL-02', '192.168.99.102', '수치', 'DISK_HOME',
    CURRENT_DATE + TIME '09:10:00', 'home disk usage = 70%', 70.000000, '정상', '[정상] home디스크 70 < 90(임계치)',
    90.000000, '<', NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:10:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:10:00'
);

INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990007, 'TEST-LOCAL-02', '192.168.99.102', '수치', 'PROC_COUNT',
    CURRENT_DATE + TIME '09:14:00', 'process count = 30', 30.000000, '정상', '[정상] 프로세스수 30 > 20(임계치)',
    20.000000, '>', NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:14:00', to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_DATE + TIME '09:14:00'
);


-- ------------------------------------------------------------
-- TEST-LOCAL-03 : 에러1건 -> 주기 스케쥴(구간이 짧음) 경로로 빠르게 테스트하고 싶을 때
-- ------------------------------------------------------------
-- ANALYZE_DATETIME을 고정 시각이 아니라 "방금"(CURRENT_TIMESTAMP)으로 넣는다 -- 신규 서버의
-- 주기 스케쥴 기본 AGGREGATE_FROM(이번 슬롯 시각 - 간격분)은 구간이 짧아서, 09:10 같은 고정
-- 시각을 넣으면 슬롯이 돌 때쯔음엔 이미 구간 밖으로 새버리는 경우가 대부분이다.
-- 이 INSERT를 실행한 직후 -> 다음 주기 슬롯이 돌 때까지(최대 간격분 + 폴링지연 60초) 기다리면 됨.
INSERT INTO TB_ANALYZE_RESULT (
    ANALYZE_RESULT_ID, COLLECT_LOG_ID, SERVER_ID, SERVER_IP, LOG_TYPE, LOG_ID,
    LOG_TIMESTAMP, LOG_CONTENT, LOG_VALUE, ANALYZE_LEVEL, ANALYZE_MESSAGE,
    THRESHOLD_VALUE, THRESHOLD_OPERATOR, WARNING_RATIO, NOTIFY_YN, NOTIFY_AT,
    ANALYZE_DATE, ANALYZE_DATETIME, COLLECT_DATE, CREATED_AT
) VALUES (
    nextval('SEQ_ANALYZE_RESULT'), 990008, 'TEST-LOCAL-03', '192.168.99.103', '존재', 'FILE_EXIST',
    CURRENT_TIMESTAMP, 'quick.txt not found', NULL, '에러', '[에러] quick.txt 파일이 존재하지 않음',
    NULL, NULL, NULL, 'N', NULL,
    to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_TIMESTAMP, to_char(CURRENT_DATE, 'YYYYMMDD'), CURRENT_TIMESTAMP
);


-- ============================================================
-- 정리(테스트 후 직접 실행) — 기본은 주석 처리되어 있음
-- ============================================================
-- DELETE FROM TB_ANALYZE_RESULT WHERE SERVER_ID LIKE 'TEST-LOCAL-%';
-- DELETE FROM TB_NOTIFY_HISTORY WHERE SERVER_ID LIKE 'TEST-LOCAL-%';
