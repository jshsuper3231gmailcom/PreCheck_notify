package com.sks.precheck.notify.tr;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.util.SequenceHelper;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SMS TR을 보내는 TCP 소켓 클라이언트 — 스케쥴 1회 실행당 1개만 생성/연결한다(통보_TR연동정의서.md 5절).
 * bind/submit 전송 후 둘 다 SmsAckPacket(7바이트) 응답을 읽는다: bind는 result가 "00"이어야 성공,
 * submit은 result 값을 검증하지 않고 7바이트가 정상 수신되기만 하면 성공(읽기 실패/타임아웃/연결종료만 실패로 판정).
 */
public class SmsTrSocketClient implements Closeable {

    private static final Logger log = LogManager.getLogger(SmsTrSocketClient.class);

    private static final String TR_SEQUENCE_NAME = "seq_notify_tr_seqno";
    private static final Charset EUC_KR = Charset.forName(NotifyConstants.TR_ENCODING);

    private final Closeable connection;
    private final OutputStream out;
    private final InputStream in;
    private final SmsTrEncoder encoder;
    private final SequenceHelper sequenceHelper;

    public SmsTrSocketClient(
            String host,
            int port,
            int connectTimeoutMs,
            SmsTrEncoder encoder,
            SequenceHelper sequenceHelper
    ) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(connectTimeoutMs); // ack read timeout도 connect timeout과 동일 값 재사용
        this.connection = socket;
        this.out = socket.getOutputStream();
        this.in = socket.getInputStream();
        this.encoder = encoder;
        this.sequenceHelper = sequenceHelper;
    }

    /** 테스트/고급 용도 — 이미 만들어진 OutputStream/InputStream을 직접 사용한다. */
    public SmsTrSocketClient(OutputStream out, InputStream in, Closeable connection, SmsTrEncoder encoder, SequenceHelper sequenceHelper) {
        this.out = out;
        this.in = in;
        this.connection = connection;
        this.encoder = encoder;
        this.sequenceHelper = sequenceHelper;
    }

    /**
     * bind 전송 후 SmsAckPacket을 읽어 result가 "00"인지 검증한다.
     * 실패(IOException)는 호출자가 잡아서 "이번 런의 통보 대상 서버 전원 FAIL"로 처리한다.
     */
    public void sendBind() throws IOException {
        out.write(encoder.encodeBind());
        out.flush();
        byte[] ack = readAck();
        String result = new String(ack, NotifyConstants.HEADER_TOTAL_LEN, NotifyConstants.ACK_RESULT_LEN, EUC_KR);
        if (!NotifyConstants.ACK_RESULT_SUCCESS.equals(result)) {
            throw new IOException("bind ack 실패 - result=" + result);
        }
    }

    /**
     * 한 서버의 수신자 목록에 순서대로 submit. submit마다 SmsAckPacket을 읽되 값은 검증하지 않고
     * 읽기 자체가 성공하면 그 수신자를 성공 처리한다. 중간에 실패하면 그 지점에서 멈추고 결과를 반환한다(재시도 없음).
     * recipients가 비어있으면 아무것도 전송하지 않고 전원 성공(0/0)으로 취급한다.
     */
    public SubmitResult submitAll(List<String> recipients, String message) {
        int successCount = 0;
        for (String recvPhn : recipients) {
            try {
                long seqNo = sequenceHelper.nextval(TR_SEQUENCE_NAME);
                out.write(encoder.encodeSubmit(seqNo, recvPhn, message, LocalDateTime.now()));
                out.flush();
                readAck(); // 값은 검증하지 않음 - 7바이트 수신 자체가 성공 판정 기준
                successCount++;
            } catch (IOException e) {
                log.warn("submit 전송/응답 실패 - recvPhn: {}, 이번 서버 성공 {}/{}", recvPhn, successCount, recipients.size(), e);
                return new SubmitResult(successCount, recipients.size(), e);
            }
        }
        return new SubmitResult(successCount, recipients.size(), null);
    }

    private byte[] readAck() throws IOException {
        byte[] ack = new byte[NotifyConstants.ACK_TOTAL_LEN];
        readFully(ack);
        return ack;
    }

    private void readFully(byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) {
                throw new IOException("ack 수신 중 연결이 끊김 (" + total + "/" + buf.length + "바이트)");
            }
            total += read;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (IOException e) {
            log.warn("SMS TR 소켓 종료 중 예외 발생", e);
        }
    }

    public static class SubmitResult {

        private final int successCount;
        private final int totalCount;
        private final IOException failure;

        public SubmitResult(int successCount, int totalCount, IOException failure) {
            this.successCount = successCount;
            this.totalCount = totalCount;
            this.failure = failure;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public IOException getFailure() {
            return failure;
        }

        public boolean allSucceeded() {
            return failure == null;
        }

        public boolean anySucceeded() {
            return successCount > 0;
        }
    }
}
