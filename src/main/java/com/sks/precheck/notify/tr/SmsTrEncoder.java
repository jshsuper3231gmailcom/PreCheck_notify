package com.sks.precheck.notify.tr;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * SMS TR(BISUB_HEADER + SMSBIND_BODY/SMSSUBMIT_BODY) 바이트 인코더 — 통보_TR연동정의서.md 3~6절 참고
 *
 * bind(01)는 헤더(5바이트) + SMSBIND_BODY(30바이트) = 35바이트(system_id 실제 폭은 8절 미확인 항목).
 * submit(03)은 헤더(5바이트) + SMSSUBMIT_BODY(353바이트) = 358바이트.
 * 응답(SmsAckPacket) 읽기/검증은 SmsTrSocketClient가 담당한다(5절 참고).
 */
public class SmsTrEncoder {

    private static final Charset EUC_KR = Charset.forName(NotifyConstants.TR_ENCODING);
    private static final DateTimeFormatter REQ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(NotifyConstants.TR_REQ_DATE_FORMAT);

    public byte[] encodeBind() {
        byte[] body = encodeBindBody();
        byte[] header = encodeHeader(NotifyConstants.HEADER_SMS_CODE_BIND, body.length);
        return concat(header, body);
    }

    private byte[] encodeBindBody() {
        ByteBuffer buf = ByteBuffer.allocate(NotifyConstants.BIND_BODY_TOTAL_LEN);
        buf.put(fixedTextField(NotifyConstants.BIND_SYSTEM_ID_FIXED, NotifyConstants.BIND_SYSTEM_ID_LEN)); // 1 system_id
        buf.put(fixedTextField(NotifyConstants.BIND_VERSION_FIXED, NotifyConstants.BIND_VERSION_LEN));     // 2 version
        return buf.array();
    }

    public byte[] encodeSubmit(long seqNo, String recvPhn, String message, LocalDateTime reqDateTime) {
        byte[] body = encodeBody(seqNo, recvPhn, message, reqDateTime);
        byte[] header = encodeHeader(NotifyConstants.HEADER_SMS_CODE_SUBMIT, body.length);
        return concat(header, body);
    }

    private byte[] encodeHeader(String smsCode, int bodyLength) {
        byte[] smsCodeBytes = fixedTextField(smsCode, NotifyConstants.HEADER_SMS_CODE_LEN);
        byte[] bodyLengthBytes = zeroPadField(bodyLength, NotifyConstants.HEADER_BODY_LENGTH_LEN);
        return concat(smsCodeBytes, bodyLengthBytes);
    }

    private byte[] encodeBody(long seqNo, String recvPhn, String message, LocalDateTime reqDateTime) {
        ByteBuffer buf = ByteBuffer.allocate(NotifyConstants.BODY_TOTAL_LEN);
        buf.put(blankField(NotifyConstants.BODY_BLANK_LEN));                                                  // 1 blank
        buf.put(fixedTextField(NotifyConstants.BODY_SMS_CODE_INDIVIDUAL, NotifyConstants.BODY_SMS_CODE_LEN)); // 2 sms_code
        buf.put(fixedTextField(reqDateTime.format(REQ_DATE_FORMATTER), NotifyConstants.BODY_REQ_DATE_LEN));   // 3 req_date
        buf.put(zeroPadField(seqNo, NotifyConstants.BODY_SEQ_NO_LEN));                                        // 4 seq_no
        buf.put(fixedTextField(NotifyConstants.SENDER_INFO_FIXED, NotifyConstants.BODY_SENDER_INFO_LEN));     // 5 sender_info
        buf.put(blankField(NotifyConstants.BODY_BRANCH_CODE_LEN));                                            // 6 branch_code
        buf.put(fixedTextField(NotifyConstants.TERM_IP_FIXED, NotifyConstants.BODY_TERM_IP_LEN));             // 7 term_ip
        buf.put(fixedTextField(recvPhn, NotifyConstants.BODY_RECV_PHN_LEN));                                  // 8 recv_phn
        buf.put(blankField(NotifyConstants.BODY_RECV_PHN_SND_LEN));                                           // 9 recv_phn_snd
        buf.put(fixedTextField(message, NotifyConstants.BODY_MESSAGE_LEN));                                   // 10 message
        buf.put(blankField(NotifyConstants.BODY_ACCNT_NO_LEN));                                               // 11 accnt_no
        buf.put(blankField(NotifyConstants.BODY_JUNIN_ID_LEN));                                               // 12 junin_id
        buf.put(blankField(NotifyConstants.BODY_RECEIVER_NM_LEN));                                            // 13 receiver_nm
        buf.put(blankField(NotifyConstants.BODY_CHE_NUM_LEN));                                                // 14 che_num
        buf.put(blankField(NotifyConstants.BODY_ORDER_NO_LEN));                                               // 15 order_no
        buf.put(blankField(NotifyConstants.BODY_ORIGIN_NO_LEN));                                              // 16 origin_no
        buf.put(blankField(NotifyConstants.BODY_CHE_DATE_LEN));                                               // 17 che_date
        buf.put(blankField(NotifyConstants.BODY_ACCNT_ADMIN_ID_LEN));                                         // 18 accnt_admin_id
        buf.put(blankField(NotifyConstants.BODY_ADMIN_ID_LEN));                                               // 19 admin_id
        buf.put(fixedTextField(NotifyConstants.SENDER_INFO_FIXED, NotifyConstants.BODY_SEND_PHN_LEN));        // 20 send_phn
        buf.put(fixedTextField(NotifyConstants.SENDER_INFO_FIXED, NotifyConstants.BODY_RECALL_PHN_LEN));      // 21 recall_phn
        buf.put(blankField(NotifyConstants.BODY_BLANK2_LEN));                                                 // 22 blank2
        buf.put(fixedTextField("SMS_TUJA03 00000000     ", NotifyConstants.BODY_FILLER_LEN));          // 23 filler
        buf.put(NotifyConstants.TR_END_MARKER);                                                               // 24 end
        return buf.array();
    }

    private byte[] blankField(int length) {
        return fixedTextField("", length);
    }

    /** 좌측 정렬 + 우측 스페이스 패딩, EUC-KR 바이트 기준 길이를 넘으면 멀티바이트 문자가 잘리지 않게 문자 단위로 줄여서 자른다. */
    private byte[] fixedTextField(String value, int length) {
        byte[] content = encodeTruncated(value == null ? "" : value, length);
        byte[] field = new byte[length];
        Arrays.fill(field, (byte) NotifyConstants.TR_PAD_CHAR);
        System.arraycopy(content, 0, field, 0, content.length);
        return field;
    }

    private byte[] encodeTruncated(String value, int maxBytes) {
        byte[] full = value.getBytes(EUC_KR);
        if (full.length <= maxBytes) {
            return full;
        }
        int end = value.length();
        while (end > 0) {
            byte[] candidate = value.substring(0, end).getBytes(EUC_KR);
            if (candidate.length <= maxBytes) {
                return candidate;
            }
            end--;
        }
        return new byte[0];
    }

    /** 우측 정렬 + 좌측 0 패딩 (seq_no, body_length 등 수치형 필드용). */
    private byte[] zeroPadField(long value, int length) {
        String text = String.format("%0" + length + "d", value);
        return text.getBytes(EUC_KR);
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
