package com.sks.precheck.notify.tr;

import com.sks.precheck.notify.common.util.SequenceHelper;
import java.io.IOException;

/** NotifyService가 실제 소켓 연결을 직접 만들지 않고 이 시드를 통해 생성하게 해서, 테스트에서 가짜 클라이언트를 주입할 수 있게 한다. */
public interface SmsTrSocketClientFactory {

    SmsTrSocketClient connect(String host, int port, int connectTimeoutMs, SmsTrEncoder encoder, SequenceHelper sequenceHelper)
            throws IOException;
}
