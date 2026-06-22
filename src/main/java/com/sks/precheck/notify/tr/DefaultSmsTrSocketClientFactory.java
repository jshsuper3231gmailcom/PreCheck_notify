package com.sks.precheck.notify.tr;

import com.sks.precheck.notify.common.util.SequenceHelper;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class DefaultSmsTrSocketClientFactory implements SmsTrSocketClientFactory {

    @Override
    public SmsTrSocketClient connect(String host, int port, int connectTimeoutMs, SmsTrEncoder encoder, SequenceHelper sequenceHelper)
            throws IOException {
        return new SmsTrSocketClient(host, port, connectTimeoutMs, encoder, sequenceHelper);
    }
}
