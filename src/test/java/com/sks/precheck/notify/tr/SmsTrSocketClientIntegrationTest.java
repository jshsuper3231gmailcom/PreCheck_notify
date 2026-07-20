package com.sks.precheck.notify.tr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

import com.sks.precheck.notify.common.util.SequenceHelper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 실제 로컬 ServerSocket을 띄워 bind/submit이 진짜 TCP로 정확한 바이트 수만큼 전송되는지,
 * bind/submit 응답(SmsAckPacket)을 정상 처리하는지, 연결이 중간에 끊기거나 응답이 안 오면
 * 실패를 감지하는지 검증한다 (통보_TR연동정의서.md 5~7절).
 */
@ExtendWith(MockitoExtension.class)
class SmsTrSocketClientIntegrationTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    private static final int BIND_TR_LEN = 35;
    private static final int SUBMIT_TR_LEN = 358;

    @Mock
    private SequenceHelper sequenceHelper;

    private final SmsTrEncoder encoder = new SmsTrEncoder();

    private ServerSocket serverSocket;

    @BeforeEach
    void setUp() throws IOException {
        lenient().when(sequenceHelper.nextval("seq_notify_tr_seqno")).thenReturn(1L, 2L, 3L, 4L, 5L);
        serverSocket = new ServerSocket(0); // 0 = OS가 빈 포트 자동 할당
    }

    @AfterEach
    void tearDown() throws IOException {
        serverSocket.close();
    }

    @Test
    void bindThenSubmit_realSocket_serverReceivesExpectedByteCounts() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept()) {
                InputStream in = accepted.getInputStream();
                OutputStream out = accepted.getOutputStream();

                byte[] buf = new byte[BIND_TR_LEN + SUBMIT_TR_LEN];
                readFully(in, buf, 0, BIND_TR_LEN);
                out.write(buildAck("01", "00"));
                out.flush();

                readFully(in, buf, BIND_TR_LEN, SUBMIT_TR_LEN);
                out.write(buildAck("03", "00"));
                out.flush();

                received.set(buf);
            } catch (IOException ignored) {
                // 테스트 종료 시 소켓 정리 과정에서 발생 가능, 무시
            } finally {
                serverDone.countDown();
            }
        });
        serverThread.start();

        try (SmsTrSocketClient client = new SmsTrSocketClient(
                "127.0.0.1", serverSocket.getLocalPort(), 2000, encoder, sequenceHelper)) {
            client.sendBind();
            SmsTrSocketClient.SubmitResult result = client.submitAll(List.of("01012345678"), "hello");
            assertThat(result.allSucceeded()).isTrue();
        }

        assertThat(serverDone.await(3, TimeUnit.SECONDS)).isTrue();
        byte[] bytes = received.get();
        assertThat(bytes).isNotNull();

        // bind 헤더: sms_code="01", body_length="030"
        assertThat(new String(bytes, 0, 2, EUC_KR)).isEqualTo("01");
        assertThat(new String(bytes, 2, 3, EUC_KR)).isEqualTo("030");
        // submit 헤더(bind TR 35바이트 다음부터): sms_code="03", body_length="353"
        assertThat(new String(bytes, BIND_TR_LEN, 2, EUC_KR)).isEqualTo("03");
        assertThat(new String(bytes, BIND_TR_LEN + 2, 3, EUC_KR)).isEqualTo("353");
    }

    @Test
    void connectionRefused_throwsIOException() throws IOException {
        int unusedPort = serverSocket.getLocalPort();
        serverSocket.close(); // 닫아서 아무도 듣지 않는 포트로 만듦

        assertThrows(IOException.class,
                () -> new SmsTrSocketClient("127.0.0.1", unusedPort, 500, encoder, sequenceHelper));
    }

    @Test
    void connectionDiesMidLoop_submitAllReportsIncompleteSuccess() throws Exception {
        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept()) {
                InputStream in = accepted.getInputStream();
                OutputStream out = accepted.getOutputStream();

                readFully(in, new byte[BIND_TR_LEN], 0, BIND_TR_LEN);
                out.write(buildAck("01", "00"));
                out.flush();

                readFully(in, new byte[SUBMIT_TR_LEN], 0, SUBMIT_TR_LEN); // 첫 submit 1건만 정상 응답
                out.write(buildAck("03", "00"));
                out.flush();

                accepted.setSoLinger(true, 0); // RST로 강제 종료 — 이후 클라이언트 write/read가 실패하도록 유도
            } catch (IOException ignored) {
                // 정상 종료 경로
            }
        });
        serverThread.start();

        try (SmsTrSocketClient client = new SmsTrSocketClient(
                "127.0.0.1", serverSocket.getLocalPort(), 2000, encoder, sequenceHelper)) {
            client.sendBind();
            // 수신자 5명 중 서버가 1건만 정상 응답하고 연결을 끊으므로, 전원 성공하지는 못해야 한다
            SmsTrSocketClient.SubmitResult result = client.submitAll(
                    List.of("01011111111", "01022222222", "01033333333", "01044444444", "01055555555"),
                    "hello");

            assertThat(result.allSucceeded()).isFalse();
            assertThat(result.getSuccessCount()).isLessThan(result.getTotalCount());
        }
    }

    @Test
    void bindAckNeverArrives_readTimesOut_throwsIOException() throws Exception {
        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept()) {
                readFully(accepted.getInputStream(), new byte[BIND_TR_LEN], 0, BIND_TR_LEN);
                Thread.sleep(1000); // ack 응답을 보내지 않음 — 클라이언트가 read timeout으로 실패해야 함
            } catch (IOException | InterruptedException ignored) {
                // 테스트 종료 시 소켓 정리 과정에서 발생 가능, 무시
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (SmsTrSocketClient client = new SmsTrSocketClient(
                "127.0.0.1", serverSocket.getLocalPort(), 200, encoder, sequenceHelper)) {
            assertThrows(IOException.class, client::sendBind);
        }
    }

    @Test
    void bindAckResultNotOk_throwsIOException() throws Exception {
        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept()) {
                InputStream in = accepted.getInputStream();
                OutputStream out = accepted.getOutputStream();
                readFully(in, new byte[BIND_TR_LEN], 0, BIND_TR_LEN);
                out.write(buildAck("01", "99"));
                out.flush();
            } catch (IOException ignored) {
                // 테스트 종료 시 소켓 정리 과정에서 발생 가능, 무시
            }
        });
        serverThread.start();

        try (SmsTrSocketClient client = new SmsTrSocketClient(
                "127.0.0.1", serverSocket.getLocalPort(), 2000, encoder, sequenceHelper)) {
            assertThrows(IOException.class, client::sendBind);
        }
    }

    @Test
    void submitAckNeverArrives_reportsFailure() throws Exception {
        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept()) {
                InputStream in = accepted.getInputStream();
                OutputStream out = accepted.getOutputStream();

                readFully(in, new byte[BIND_TR_LEN], 0, BIND_TR_LEN);
                out.write(buildAck("01", "00"));
                out.flush();

                readFully(in, new byte[SUBMIT_TR_LEN], 0, SUBMIT_TR_LEN);
                Thread.sleep(1000); // submit ack를 보내지 않음
            } catch (IOException | InterruptedException ignored) {
                // 테스트 종료 시 소켓 정리 과정에서 발생 가능, 무시
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (SmsTrSocketClient client = new SmsTrSocketClient(
                "127.0.0.1", serverSocket.getLocalPort(), 200, encoder, sequenceHelper)) {
            client.sendBind();
            SmsTrSocketClient.SubmitResult result = client.submitAll(List.of("01012345678"), "hello");

            assertThat(result.allSucceeded()).isFalse();
            assertThat(result.getSuccessCount()).isEqualTo(0);
        }
    }

    /** BISUB_HEADER(sms_code + body_length="002") + result 2바이트 = 7바이트 SmsAckPacket. */
    private byte[] buildAck(String smsCode, String result) {
        String text = smsCode + "002" + result;
        return text.getBytes(EUC_KR);
    }

    private void readFully(InputStream in, byte[] buf, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buf, offset + total, length - total);
            if (read < 0) {
                throw new IOException("스트림이 예상보다 일찍 종료됨 (read " + total + "/" + length + ")");
            }
            total += read;
        }
    }
}
