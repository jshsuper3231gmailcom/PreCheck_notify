package com.sks.precheck.notify.tr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.sks.precheck.notify.common.util.SequenceHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * submitAll()의 성공/부분실패/전실패 분기를 실제 소켓 없이 가짜 OutputStream으로 검증.
 * (실 소켓 타이밍에 의존하지 않게 분리 — 실 소켓 바이트 검증은 SmsTrSocketClientIntegrationTest에서)
 */
@ExtendWith(MockitoExtension.class)
class SmsTrSocketClientLoopTest {

    @Mock
    private SequenceHelper sequenceHelper;

    private final SmsTrEncoder encoder = new SmsTrEncoder();

    @BeforeEach
    void setUp() {
        lenient().when(sequenceHelper.nextval("seq_notify_tr_seqno")).thenReturn(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void submitAll_allSucceed() {
        OutputStream alwaysSucceeds = new ByteArrayOutputStream();
        SmsTrSocketClient client = new SmsTrSocketClient(alwaysSucceeds, () -> {}, encoder, sequenceHelper);

        SmsTrSocketClient.SubmitResult result = client.submitAll(List.of("01011112222", "01033334444", "01055556666"), "test");

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getFailure()).isNull();
    }

    @Test
    void submitAll_failsOnSecondRecipient_partialResult() {
        OutputStream failsOnSecondWrite = new FailingAfterNWritesOutputStream(1);
        SmsTrSocketClient client = new SmsTrSocketClient(failsOnSecondWrite, () -> {}, encoder, sequenceHelper);

        SmsTrSocketClient.SubmitResult result = client.submitAll(List.of("01011112222", "01033334444", "01055556666"), "test");

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.anySucceeded()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getFailure()).isNotNull();
    }

    @Test
    void submitAll_failsImmediately_zeroSuccess() {
        OutputStream failsImmediately = new FailingAfterNWritesOutputStream(0);
        SmsTrSocketClient client = new SmsTrSocketClient(failsImmediately, () -> {}, encoder, sequenceHelper);

        SmsTrSocketClient.SubmitResult result = client.submitAll(List.of("01011112222", "01033334444"), "test");

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.anySucceeded()).isFalse();
        assertThat(result.getSuccessCount()).isEqualTo(0);
    }

    @Test
    void submitAll_emptyRecipients_treatedAsAllSucceeded() {
        OutputStream stream = new ByteArrayOutputStream();
        SmsTrSocketClient client = new SmsTrSocketClient(stream, () -> {}, encoder, sequenceHelper);

        SmsTrSocketClient.SubmitResult result = client.submitAll(List.of(), "test");

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getTotalCount()).isEqualTo(0);
    }

    /** write() 호출을 N번까지는 허용하고 그 다음부터 IOException을 던지는 가짜 스트림. */
    private static class FailingAfterNWritesOutputStream extends OutputStream {
        private final int allowedWrites;
        private int writeCalls = 0;

        private FailingAfterNWritesOutputStream(int allowedWrites) {
            this.allowedWrites = allowedWrites;
        }

        @Override
        public void write(int b) throws IOException {
            throwIfExceeded();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throwIfExceeded();
        }

        private void throwIfExceeded() throws IOException {
            if (writeCalls >= allowedWrites) {
                throw new IOException("simulated connection failure");
            }
            writeCalls++;
        }
    }
}
