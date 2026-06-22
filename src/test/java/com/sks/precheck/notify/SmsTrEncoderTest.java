package com.sks.precheck.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.sks.precheck.notify.tr.SmsTrEncoder;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SmsTrEncoderTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private final SmsTrEncoder encoder = new SmsTrEncoder();

    @Test
    void encodeBind_headerOnly_5bytes() {
        byte[] tr = encoder.encodeBind();

        assertThat(tr).hasSize(5);
        assertThat(field(tr, 0, 2)).isEqualTo("01");
        assertThat(field(tr, 2, 5)).isEqualTo("000");
    }

    @Test
    void encodeSubmit_totalLength_is358() {
        byte[] tr = encoder.encodeSubmit(123L, "01012345678", "[srv01] 정상5 경고2 에러1 (09:00~10:00)",
                LocalDateTime.of(2026, 6, 18, 9, 30, 1));

        assertThat(tr).hasSize(358);
    }

    @Test
    void encodeSubmit_header_smsCode03_bodyLength353() {
        byte[] tr = encoder.encodeSubmit(1L, "01012345678", "hello", LocalDateTime.of(2026, 6, 18, 9, 30, 1));

        assertThat(field(tr, 0, 2)).isEqualTo("03");
        assertThat(field(tr, 2, 5)).isEqualTo("353");
    }

    @Test
    void encodeSubmit_bodyFields_atExpectedOffsets() {
        LocalDateTime reqDate = LocalDateTime.of(2026, 6, 18, 9, 30, 1);
        byte[] tr = encoder.encodeSubmit(123L, "01012345678", "hello", reqDate);

        // body는 헤더(5바이트) 다음부터 시작
        assertThat(field(tr, 5, 21)).isEqualTo(" ".repeat(16));      // blank
        assertThat(field(tr, 21, 23)).isEqualTo("02");               // body sms_code = 개별발송 고정
        assertThat(field(tr, 23, 37)).isEqualTo("20260618093001");   // req_date yyyyMMddHHmmss
        assertThat(field(tr, 37, 45)).isEqualTo("00000123");         // seq_no 8자리 0-padding
        assertThat(field(tr, 45, 60)).isEqualTo(padRight("130.2.4.12", 15));  // sender_info 고정값 + 우측 스페이스 패딩
        assertThat(field(tr, 60, 65)).isEqualTo(" ".repeat(5));      // branch_code blank
        assertThat(field(tr, 65, 80)).isEqualTo(padRight("130.2.4.12", 15));  // term_ip 고정값
        assertThat(field(tr, 80, 95)).isEqualTo(padRight("01012345678", 15)); // recv_phn + 우측 패딩
        assertThat(field(tr, 95, 110)).isEqualTo(" ".repeat(15));    // recv_phn_snd 미사용
        assertThat(field(tr, 110, 115)).isEqualTo("hello");          // message 앞부분
        assertThat(field(tr, 115, 190)).isEqualTo(" ".repeat(75));   // message 나머지 패딩(80바이트 중 5바이트만 사용)
        assertThat(tr[tr.length - 1]).isEqualTo((byte) 0x03);        // end 마커
    }

    @Test
    void encodeSubmit_koreanMessage_eucKrEncoded() {
        byte[] tr = encoder.encodeSubmit(1L, "01012345678", "[srv01] 정상5 경고2 에러1",
                LocalDateTime.of(2026, 6, 18, 9, 30, 1));

        String decodedMessageField = field(tr, 110, 190);
        assertThat(decodedMessageField.trim()).isEqualTo("[srv01] 정상5 경고2 에러1");
    }

    @Test
    void encodeSubmit_messageLongerThan80Bytes_isTruncatedWithoutBreakingMultibyteChar() {
        // 한글 1글자 = EUC-KR 2바이트이므로, 79바이트 한도를 넘는 한글로 가득 채워 truncate를 유도
        String longMessage = "가".repeat(60); // 120바이트
        byte[] tr = encoder.encodeSubmit(1L, "01012345678", longMessage, LocalDateTime.of(2026, 6, 18, 9, 30, 1));

        byte[] messageField = new byte[80];
        System.arraycopy(tr, 110, messageField, 0, 80);

        // 잘린 결과를 다시 EUC-KR로 디코딩했을 때 깨진 문자(대체 문자 등) 없이 정상 디코딩되어야 함
        String decoded = new String(messageField, EUC_KR).trim();
        assertThat(decoded).isEqualTo("가".repeat(decoded.length()));
        assertThat(decoded.getBytes(EUC_KR).length).isLessThanOrEqualTo(80);
    }

    private String field(byte[] tr, int from, int to) {
        byte[] slice = new byte[to - from];
        System.arraycopy(tr, from, slice, 0, slice.length);
        return new String(slice, EUC_KR);
    }

    private String padRight(String value, int length) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
