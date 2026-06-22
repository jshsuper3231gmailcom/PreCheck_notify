package com.sks.precheck.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.util.SequenceHelper;
import com.sks.precheck.notify.domain.NotifyHistory;
import com.sks.precheck.notify.mapper.AnalyzeResultMapper;
import com.sks.precheck.notify.mapper.NotifyHistoryMapper;
import com.sks.precheck.notify.tr.SmsTrSocketClient;
import com.sks.precheck.notify.tr.SmsTrSocketClientFactory;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import com.sks.precheck.notify.vo.ServerAggregateResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class NotifyServiceTest {

    private NotifyAggregationService aggregationService;
    private NotifyHistoryMapper notifyHistoryMapper;
    private AnalyzeResultMapper analyzeResultMapper;
    private SequenceHelper sequenceHelper;
    private SmsTrSocketClientFactory socketClientFactory;
    private SmsTrSocketClient socketClient;

    @TempDir
    Path tempDir;

    private NotifyScheduleVo schedule;
    private LocalDateTime aggregateTo;

    @BeforeEach
    void setUp() {
        aggregationService = mock(NotifyAggregationService.class);
        notifyHistoryMapper = mock(NotifyHistoryMapper.class);
        analyzeResultMapper = mock(AnalyzeResultMapper.class);
        sequenceHelper = mock(SequenceHelper.class);
        socketClientFactory = mock(SmsTrSocketClientFactory.class);
        socketClient = mock(SmsTrSocketClient.class);

        when(sequenceHelper.nextval("seq_notify_history")).thenReturn(1L, 2L, 3L, 4L, 5L);

        schedule = new NotifyScheduleVo();
        schedule.setScheduleType("주기");
        schedule.setIntervalMinutes(30);
        aggregateTo = LocalDateTime.of(2026, 6, 18, 9, 30, 1);
    }

    private NotifyService newService(String targetFilePath) throws IOException {
        when(socketClientFactory.connect(eq("127.0.0.1"), eq(9000), anyInt(), any(), any())).thenReturn(socketClient);
        return new NotifyService(aggregationService, notifyHistoryMapper, analyzeResultMapper, sequenceHelper,
                socketClientFactory, targetFilePath, "127.0.0.1", 9000);
    }

    private Path writeTargetFile(String... lines) throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private ServerAggregateResult target(String serverId) {
        ServerAggregateResult result = new ServerAggregateResult();
        result.setServerId(serverId);
        result.setAggregateFrom(aggregateTo.minusMinutes(30));
        result.setAggregateTo(aggregateTo);
        result.setNormalCount(1);
        result.setWarningCount(1);
        result.setErrorCount(0);
        result.setMessage("[" + serverId + "] 정상1 경고1 에러0 (09:00~09:30)");
        return result;
    }

    @Test
    void noTargets_doesNothing() throws Exception {
        when(aggregationService.aggregate(schedule, aggregateTo)).thenReturn(List.of());
        NotifyService service = newService(writeTargetFile("01012345678").toString());

        service.runNotify(schedule, aggregateTo);

        verify(notifyHistoryMapper, never()).insert(any());
        verify(socketClientFactory, never()).connect(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void emptyRecipientFile_recordsSuccessWithZeroRecv_doesNotOpenConnection() throws Exception {
        ServerAggregateResult srv01 = target("srv01");
        when(aggregationService.aggregate(schedule, aggregateTo)).thenReturn(List.of(srv01));
        NotifyService service = newService(writeTargetFile("# 비어있음").toString());

        service.runNotify(schedule, aggregateTo);

        ArgumentCaptor<NotifyHistory> captor = ArgumentCaptor.forClass(NotifyHistory.class);
        verify(notifyHistoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_SUCCESS);
        assertThat(captor.getValue().getRecvTotalCount()).isEqualTo(0L);

        verify(analyzeResultMapper).updateNotifyYn(eq("srv01"), any(), any(), any());
        verify(socketClientFactory, never()).connect(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void connectFails_allTargetsRecordedFail_noNotifyYnUpdate() throws Exception {
        ServerAggregateResult srv01 = target("srv01");
        ServerAggregateResult srv02 = target("srv02");
        when(aggregationService.aggregate(schedule, aggregateTo)).thenReturn(List.of(srv01, srv02));

        NotifyService service = new NotifyService(aggregationService, notifyHistoryMapper, analyzeResultMapper, sequenceHelper,
                socketClientFactory, writeTargetFile("01012345678").toString(), "127.0.0.1", 9000);
        when(socketClientFactory.connect(any(), anyInt(), anyInt(), any(), any())).thenThrow(new IOException("connection refused"));

        service.runNotify(schedule, aggregateTo);

        ArgumentCaptor<NotifyHistory> captor = ArgumentCaptor.forClass(NotifyHistory.class);
        verify(notifyHistoryMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(h -> assertThat(h.getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_FAIL));
        verify(analyzeResultMapper, never()).updateNotifyYn(any(), any(), any(), any());
    }

    @Test
    void bindFails_allTargetsRecordedFail_connectionClosed() throws Exception {
        ServerAggregateResult srv01 = target("srv01");
        when(aggregationService.aggregate(schedule, aggregateTo)).thenReturn(List.of(srv01));
        NotifyService service = newService(writeTargetFile("01012345678").toString());

        org.mockito.Mockito.doThrow(new IOException("bind failed")).when(socketClient).sendBind();

        service.runNotify(schedule, aggregateTo);

        ArgumentCaptor<NotifyHistory> captor = ArgumentCaptor.forClass(NotifyHistory.class);
        verify(notifyHistoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_FAIL);
        verify(socketClient).close();
    }

    @Test
    void allServersSucceed_allRecordedSuccess_notifyYnUpdatedForEach() throws Exception {
        ServerAggregateResult srv01 = target("srv01");
        ServerAggregateResult srv02 = target("srv02");
        when(aggregationService.aggregate(schedule, aggregateTo)).thenReturn(List.of(srv01, srv02));
        NotifyService service = newService(writeTargetFile("01012345678").toString());

        when(socketClient.submitAll(any(), any())).thenReturn(new SmsTrSocketClient.SubmitResult(1, 1, null));

        service.runNotify(schedule, aggregateTo);

        ArgumentCaptor<NotifyHistory> captor = ArgumentCaptor.forClass(NotifyHistory.class);
        verify(notifyHistoryMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(h -> assertThat(h.getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_SUCCESS));
        verify(analyzeResultMapper, times(2)).updateNotifyYn(any(), any(), any(), any());
        verify(socketClient).close();
    }

    @Test
    void firstServerPartial_remainingServersCascadeToFail() throws Exception {
        ServerAggregateResult srv01 = target("srv01");
        ServerAggregateResult srv02 = target("srv02");
        ServerAggregateResult srv03 = target("srv03");
        when(aggregationService.aggregate(schedule, aggregateTo)).thenReturn(List.of(srv01, srv02, srv03));
        NotifyService service = newService(writeTargetFile("01011111111", "01022222222").toString());

        IOException midRunFailure = new IOException("connection reset");
        when(socketClient.submitAll(any(), any())).thenReturn(new SmsTrSocketClient.SubmitResult(1, 2, midRunFailure));

        service.runNotify(schedule, aggregateTo);

        ArgumentCaptor<NotifyHistory> captor = ArgumentCaptor.forClass(NotifyHistory.class);
        verify(notifyHistoryMapper, times(3)).insert(captor.capture());

        List<NotifyHistory> histories = captor.getAllValues();
        assertThat(histories.get(0).getServerId()).isEqualTo("srv01");
        assertThat(histories.get(0).getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_PARTIAL);
        assertThat(histories.get(0).getRecvSuccessCount()).isEqualTo(1L);

        assertThat(histories.get(1).getServerId()).isEqualTo("srv02");
        assertThat(histories.get(1).getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_FAIL);
        assertThat(histories.get(1).getRecvTotalCount()).isEqualTo(0L);

        assertThat(histories.get(2).getServerId()).isEqualTo("srv03");
        assertThat(histories.get(2).getNotifyStatus()).isEqualTo(NotifyConstants.STATUS_FAIL);

        // PARTIAL이었던 srv01만 NOTIFY_YN 갱신, FAIL인 srv02/srv03은 갱신 안 함
        verify(analyzeResultMapper, times(1)).updateNotifyYn(eq("srv01"), any(), any(), any());
        verify(analyzeResultMapper, never()).updateNotifyYn(eq("srv02"), any(), any(), any());
        verify(analyzeResultMapper, never()).updateNotifyYn(eq("srv03"), any(), any(), any());

        // submitAll은 srv01에서만 호출되고, 연결이 끊긴 뒤로는 더 이상 호출되지 않아야 함
        verify(socketClient, times(1)).submitAll(any(), any());
    }
}
