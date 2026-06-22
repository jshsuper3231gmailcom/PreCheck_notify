package com.sks.precheck.notify.tr;

import com.sks.precheck.notify.common.util.SequenceHelper;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SMS TR을 보내는 TCP 소켓 클라이언트 — 스케쥴 1회 실행당 1개만 생성/연결한다(통보_TR연동정의서.md 5절).
 * 응답(ack)이 없으므로 성공 판정은 write()가 예외 없이 끝났는지로만 한다(fire-and-forget).
 */
public class SmsTrSocketClient implements Closeable {

    private static final Logger log = LogManager.getLogger(SmsTrSocketClient.class);

    private static final String TR_SEQUENCE_NAME = "seq_notify_tr_seqno";

    private final Closeable connection;
    private final OutputStream out;
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
        this.connection = socket;
        this.out = socket.getOutputStream();
        this.encoder = encoder;
        this.sequenceHelper = sequenceHelper;
    }

    /** 테스트/고급 용도 — 이미 만들어진 OutputStream을 직접 사용한다. */
    public SmsTrSocketClient(OutputStream out, Closeable connection, SmsTrEncoder encoder, SequenceHelper sequenceHelper) {
        this.out = out;
        this.connection = connection;
        this.encoder = encoder;
        this.sequenceHelper = sequenceHelper;
    }

    /** bind 실패(IOException)는 호출자가 잡아서 "이번 런의 통보 대상 서버 전원 FAIL"로 처리한다. */
    public void sendBind() throws IOException {
        out.write(encoder.encodeBind());
        out.flush();
    }

    /**
     * 한 서버의 수신자 목록에 순서대로 submit. 중간에 실패하면 그 지점에서 멈추고 결과를 반환한다(재시도 없음).
     * recipients가 비어있으면 아무것도 전송하지 않고 전원 성공(0/0)으로 취급한다.
     */
    public SubmitResult submitAll(List<String> recipients, String message) {
        int successCount = 0;
        for (String recvPhn : recipients) {
            try {
                long seqNo = sequenceHelper.nextval(TR_SEQUENCE_NAME);
                out.write(encoder.encodeSubmit(seqNo, recvPhn, message, LocalDateTime.now()));
                out.flush();
                successCount++;
            } catch (IOException e) {
                log.warn("submit 전송 실패 - recvPhn: {}, 이번 서버 성공 {}/{}", recvPhn, successCount, recipients.size(), e);
                return new SubmitResult(successCount, recipients.size(), e);
            }
        }
        return new SubmitResult(successCount, recipients.size(), null);
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
