package com.sks.precheck.notify.tr;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 로컬 가짜 레거시 SMS 게이트웨이 — bind(01)/submit(03) TR을 수신해 전부 로그에 남기기만 하는 수동 통합 테스트용 도구.
 *
 * notify 서버가 실제로 보내는 바이트가 통보_TR연동정의서.md 규격과 일치하는지 눈으로 확인하기 위한 용도이며,
 * 운영 코드가 아니므로 메인 소스셋이 아닌 test 소스셋에 둔다(빌드 결과물에 포함되지 않음).
 * 실행: `gradlew.bat runLocalLegacyServer` (포트 변경: `-Pport=19000`)
 */
public final class LocalLegacyServerTool {

    private static final Charset EUC_KR = Charset.forName(NotifyConstants.TR_ENCODING);
    private static final int DEFAULT_PORT = 9000;

    // SmsTrEncoder.encodeBody()와 동일한 순서/길이 — 통보_TR연동정의서.md 4절
    private static final String[] FIELD_NAMES = {
            "blank", "sms_code", "req_date", "seq_no", "sender_info", "branch_code", "term_ip",
            "recv_phn", "recv_phn_snd", "message", "accnt_no", "junin_id", "receiver_nm", "che_num",
            "order_no", "origin_no", "che_date", "accnt_admin_id", "admin_id", "send_phn",
            "recall_phn", "blank2", "filler", "end"
    };
    private static final int[] FIELD_LENGTHS = {
            NotifyConstants.BODY_BLANK_LEN, NotifyConstants.BODY_SMS_CODE_LEN, NotifyConstants.BODY_REQ_DATE_LEN,
            NotifyConstants.BODY_SEQ_NO_LEN, NotifyConstants.BODY_SENDER_INFO_LEN, NotifyConstants.BODY_BRANCH_CODE_LEN,
            NotifyConstants.BODY_TERM_IP_LEN, NotifyConstants.BODY_RECV_PHN_LEN, NotifyConstants.BODY_RECV_PHN_SND_LEN,
            NotifyConstants.BODY_MESSAGE_LEN, NotifyConstants.BODY_ACCNT_NO_LEN, NotifyConstants.BODY_JUNIN_ID_LEN,
            NotifyConstants.BODY_RECEIVER_NM_LEN, NotifyConstants.BODY_CHE_NUM_LEN, NotifyConstants.BODY_ORDER_NO_LEN,
            NotifyConstants.BODY_ORIGIN_NO_LEN, NotifyConstants.BODY_CHE_DATE_LEN, NotifyConstants.BODY_ACCNT_ADMIN_ID_LEN,
            NotifyConstants.BODY_ADMIN_ID_LEN, NotifyConstants.BODY_SEND_PHN_LEN, NotifyConstants.BODY_RECALL_PHN_LEN,
            NotifyConstants.BODY_BLANK2_LEN, NotifyConstants.BODY_FILLER_LEN, NotifyConstants.BODY_END_LEN
    };

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path logPath = Path.of("logs", "local-legacy-server.log");
        Files.createDirectories(logPath.getParent());

        try (ServerSocket serverSocket = new ServerSocket(port);
             PrintWriter logWriter = new PrintWriter(new FileWriter(logPath.toFile(), StandardCharsets.UTF_8, true), true)) {
            log(logWriter, "로컬 레거시 SMS 게이트웨이 시작 - port: " + port + ", log: " + logPath.toAbsolutePath());
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    log(logWriter, "연결 수락 - " + client.getRemoteSocketAddress());
                    handleConnection(client, logWriter);
                    log(logWriter, "연결 종료 - " + client.getRemoteSocketAddress());
                } catch (IOException e) {
                    log(logWriter, "연결 처리 중 오류: " + e);
                }
            }
        }
    }

    private static void handleConnection(Socket client, PrintWriter logWriter) throws IOException {
        InputStream in = client.getInputStream();
        byte[] header = new byte[NotifyConstants.HEADER_TOTAL_LEN];

        while (true) {
            int headerRead = readFully(in, header);
            if (headerRead == 0) {
                log(logWriter, "클라이언트가 연결을 정상 종료함(다음 메시지 없음)");
                return;
            }
            if (headerRead < header.length) {
                log(logWriter, "[오류] header 수신 중 연결 끊김 (" + headerRead + "/" + header.length + "바이트)");
                return;
            }

            String smsCode = new String(header, 0, NotifyConstants.HEADER_SMS_CODE_LEN, EUC_KR);
            int bodyLength = Integer.parseInt(
                    new String(header, NotifyConstants.HEADER_SMS_CODE_LEN, NotifyConstants.HEADER_BODY_LENGTH_LEN, EUC_KR).trim());

            if (NotifyConstants.HEADER_SMS_CODE_BIND.equals(smsCode)) {
                log(logWriter, "[BIND] 수신 - sms_code=" + smsCode + ", body_length=" + bodyLength);
                continue;
            }

            log(logWriter, "[SUBMIT] 수신 - sms_code=" + smsCode + ", body_length=" + bodyLength);
            byte[] body = new byte[bodyLength];
            int bodyRead = readFully(in, body);
            if (bodyRead < bodyLength) {
                log(logWriter, "[오류] body 수신 중 연결 끊김 (" + bodyRead + "/" + bodyLength + "바이트)");
                return;
            }
            logBody(body, logWriter);
        }
    }

    private static void logBody(byte[] body, PrintWriter logWriter) {
        int offset = 0;
        for (int i = 0; i < FIELD_LENGTHS.length; i++) {
            int len = FIELD_LENGTHS[i];
            String name = FIELD_NAMES[i];
            if ("end".equals(name)) {
                boolean marker = body[offset] == NotifyConstants.TR_END_MARKER;
                log(logWriter, "  end_marker=" + (marker ? "OK(0x03)" : "MISMATCH(" + body[offset] + ")"));
            } else {
                String value = new String(body, offset, len, EUC_KR).trim();
                log(logWriter, "  " + name + "=\"" + value + "\"");
            }
            offset += len;
        }
    }

    /** buf를 가득 채울 때까지 읽고 실제로 읽은 바이트 수를 반환한다(EOF면 그때까지 읽은 만큼만). */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static void log(PrintWriter logWriter, String message) {
        String line = "[" + LocalDateTime.now() + "] " + message;
        System.out.println(line);
        logWriter.println(line);
    }

    private LocalLegacyServerTool() {
    }
}
