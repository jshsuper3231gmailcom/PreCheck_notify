package com.sks.precheck.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.util.SequenceHelper;
import com.sks.precheck.notify.domain.NotifyHistory;
import com.sks.precheck.notify.mapper.NotifyHistoryMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * TB_NOTIFY_HISTORY 매퍼를 실제 로컬 PostgreSQL(test 프로파일)에 대해 검증.
 * 신규 도입 패턴이라(통보서버_PRD.md Testing Decisions 참고) SQL 자체가 실제 스키마와 맞는지 직접 확인한다.
 */
@SpringBootTest
@Transactional
@DirtiesContext
class NotifyHistoryMapperTest {

    @Autowired
    private NotifyHistoryMapper notifyHistoryMapper;

    @Autowired
    private SequenceHelper sequenceHelper;

    @Test
    void insertThenFindLastWatermark_returnsAggregateTo() {
        String serverId = "test-notify-mapper-server";
        LocalDateTime aggregateFrom = LocalDateTime.of(2026, 6, 18, 9, 0, 0);
        LocalDateTime aggregateTo = LocalDateTime.of(2026, 6, 18, 9, 30, 0);
        LocalDateTime now = LocalDateTime.now();

        NotifyHistory history = new NotifyHistory();
        history.setNotifyHistoryId(sequenceHelper.nextval("seq_notify_history"));
        history.setServerId(serverId);
        history.setAggregateFrom(aggregateFrom);
        history.setAggregateTo(aggregateTo);
        history.setNormalCount(5L);
        history.setWarningCount(2L);
        history.setErrorCount(1L);
        history.setRecvTotalCount(1L);
        history.setRecvSuccessCount(1L);
        history.setRecvFailCount(0L);
        history.setNotifyMessage("[" + serverId + "] 정상5 경고2 에러1 (09:00~09:30)");
        history.setNotifyStatus(NotifyConstants.STATUS_SUCCESS);
        history.setNotifyStartAt(now);
        history.setNotifyEndAt(now);
        history.setNotifyDate("20260618");
        history.setCreatedAt(now);

        int inserted = notifyHistoryMapper.insert(history);
        assertThat(inserted).isEqualTo(1);

        LocalDateTime watermark = notifyHistoryMapper.findLastWatermark(serverId);
        assertThat(watermark).isEqualTo(aggregateTo);
    }

    @Test
    void findLastWatermark_noHistory_returnsNull() {
        LocalDateTime watermark = notifyHistoryMapper.findLastWatermark("no-such-server-xyz");
        assertThat(watermark).isNull();
    }

    @Test
    void findLastWatermark_failStatusOnly_returnsNull() {
        String serverId = "test-notify-mapper-fail-server";
        LocalDateTime now = LocalDateTime.now();

        NotifyHistory history = new NotifyHistory();
        history.setNotifyHistoryId(sequenceHelper.nextval("seq_notify_history"));
        history.setServerId(serverId);
        history.setAggregateFrom(now.minusHours(1));
        history.setAggregateTo(now);
        history.setNormalCount(0L);
        history.setWarningCount(1L);
        history.setErrorCount(0L);
        history.setRecvTotalCount(0L);
        history.setRecvSuccessCount(0L);
        history.setRecvFailCount(0L);
        history.setNotifyMessage("[" + serverId + "] 정상0 경고1 에러0");
        history.setNotifyStatus(NotifyConstants.STATUS_FAIL);
        history.setFailReason("connect refused");
        history.setNotifyStartAt(now);
        history.setNotifyEndAt(now);
        history.setNotifyDate("20260618");
        history.setCreatedAt(now);

        notifyHistoryMapper.insert(history);

        // FAIL만 있는 서버는 워터마크가 전진하지 않아야 함 (통보_DB정의서.md 참고)
        LocalDateTime watermark = notifyHistoryMapper.findLastWatermark(serverId);
        assertThat(watermark).isNull();
    }
}
