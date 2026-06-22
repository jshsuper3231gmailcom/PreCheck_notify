package com.sks.precheck.notify.common.constants;

public final class NotifyConstants {

    // TB_ANALYZE_RESULT.ANALYZE_LEVEL (집계 대상 3종, 정보/미분석은 집계하지 않음)
    public static final String LEVEL_NORMAL = "정상";
    public static final String LEVEL_WARNING = "경고";
    public static final String LEVEL_ERROR = "에러";

    // TB_NOTIFY_HISTORY.NOTIFY_STATUS
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_PARTIAL = "PARTIAL";

    // 통보 스케쥴 conf의 통보주기기술 타입 ([배치|...] / [주기|...])
    public static final String SCHEDULE_TYPE_BATCH = "배치";
    public static final String SCHEDULE_TYPE_PERIODIC = "주기";

    public static final String NOTIFY_DATE_FORMAT = "yyyyMMdd";

    public static final int SMS_CONNECT_TIMEOUT_MS = 5000;

    // ============================================================
    // TR(BISUB_HEADER/SMSSUBMIT_BODY) 규격 — 통보_TR연동정의서.md 참고
    // ============================================================

    public static final String TR_ENCODING = "EUC-KR";
    public static final char TR_PAD_CHAR = ' ';
    public static final byte TR_END_MARKER = 0x03;
    public static final String TR_REQ_DATE_FORMAT = "yyyyMMddHHmmss";

    // BISUB_HEADER 필드
    public static final int HEADER_SMS_CODE_LEN = 2;
    public static final int HEADER_BODY_LENGTH_LEN = 3;
    public static final int HEADER_TOTAL_LEN = HEADER_SMS_CODE_LEN + HEADER_BODY_LENGTH_LEN;

    public static final String HEADER_SMS_CODE_BIND = "01";
    public static final String HEADER_SMS_CODE_SUBMIT = "03";

    // SMSSUBMIT_BODY 필드 길이 (24개 필드, 순서대로)
    public static final int BODY_BLANK_LEN = 16;
    public static final int BODY_SMS_CODE_LEN = 2;
    public static final int BODY_REQ_DATE_LEN = 14;
    public static final int BODY_SEQ_NO_LEN = 8;
    public static final int BODY_SENDER_INFO_LEN = 15;
    public static final int BODY_BRANCH_CODE_LEN = 5;
    public static final int BODY_TERM_IP_LEN = 15;
    public static final int BODY_RECV_PHN_LEN = 15;
    public static final int BODY_RECV_PHN_SND_LEN = 15;
    public static final int BODY_MESSAGE_LEN = 80;
    public static final int BODY_ACCNT_NO_LEN = 12;
    public static final int BODY_JUNIN_ID_LEN = 13;
    public static final int BODY_RECEIVER_NM_LEN = 20;
    public static final int BODY_CHE_NUM_LEN = 8;
    public static final int BODY_ORDER_NO_LEN = 6;
    public static final int BODY_ORIGIN_NO_LEN = 6;
    public static final int BODY_CHE_DATE_LEN = 14;
    public static final int BODY_ACCNT_ADMIN_ID_LEN = 10;
    public static final int BODY_ADMIN_ID_LEN = 10;
    public static final int BODY_SEND_PHN_LEN = 15;
    public static final int BODY_RECALL_PHN_LEN = 15;
    public static final int BODY_BLANK2_LEN = 14;
    public static final int BODY_FILLER_LEN = 24;
    public static final int BODY_END_LEN = 1;

    public static final int BODY_TOTAL_LEN = BODY_BLANK_LEN + BODY_SMS_CODE_LEN + BODY_REQ_DATE_LEN + BODY_SEQ_NO_LEN
            + BODY_SENDER_INFO_LEN + BODY_BRANCH_CODE_LEN + BODY_TERM_IP_LEN + BODY_RECV_PHN_LEN + BODY_RECV_PHN_SND_LEN
            + BODY_MESSAGE_LEN + BODY_ACCNT_NO_LEN + BODY_JUNIN_ID_LEN + BODY_RECEIVER_NM_LEN + BODY_CHE_NUM_LEN
            + BODY_ORDER_NO_LEN + BODY_ORIGIN_NO_LEN + BODY_CHE_DATE_LEN + BODY_ACCNT_ADMIN_ID_LEN + BODY_ADMIN_ID_LEN
            + BODY_SEND_PHN_LEN + BODY_RECALL_PHN_LEN + BODY_BLANK2_LEN + BODY_FILLER_LEN + BODY_END_LEN; // = 353

    public static final int TR_TOTAL_LEN = HEADER_TOTAL_LEN + BODY_TOTAL_LEN; // = 358

    // SMSSUBMIT_BODY.sms_code 값 (body_length 헤더와는 별개의 필드)
    public static final String BODY_SMS_CODE_INDIVIDUAL = "02"; // 개별발송 — 통보 서버가 항상 사용하는 값

    // 통보 서버 고정 채움값 (환경 무관)
    public static final String SENDER_INFO_FIXED = "130.2.4.12";
    public static final String TERM_IP_FIXED = "130.2.4.12";

    private NotifyConstants() {
    }
}
