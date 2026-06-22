package com.sks.precheck.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sks.precheck.notify.domain.AnalyzeLevelCount;
import com.sks.precheck.notify.mapper.AnalyzeResultMapper;
import com.sks.precheck.notify.mapper.NotifyHistoryMapper;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import com.sks.precheck.notify.vo.ServerAggregateResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotifyAggregationServiceTest {

    private AnalyzeResultMapper analyzeResultMapper;
    private NotifyHistoryMapper notifyHistoryMapper;
    private NotifyAggregationService service;

    @BeforeEach
    void setUp() {
        analyzeResultMapper = mock(AnalyzeResultMapper.class);
        notifyHistoryMapper = mock(NotifyHistoryMapper.class);
        service = new NotifyAggregationService(analyzeResultMapper, notifyHistoryMapper);
    }

    private NotifyScheduleVo periodicSchedule(int intervalMinutes) {
        NotifyScheduleVo vo = new NotifyScheduleVo();
        vo.setScheduleType("주기");
        vo.setDayOfWeek("*");
        vo.setStartTime("090001");
        vo.setIntervalMinutes(intervalMinutes);
        vo.setEndTime("180001");
        return vo;
    }

    private NotifyScheduleVo batchSchedule() {
        NotifyScheduleVo vo = new NotifyScheduleVo();
        vo.setScheduleType("배치");
        vo.setDayOfWeek("*");
        vo.setStartTime("180001");
        return vo;
    }

    private AnalyzeLevelCount level(String level, long cnt) {
        AnalyzeLevelCount c = new AnalyzeLevelCount();
        c.setAnalyzeLevel(level);
        c.setCnt(cnt);
        return c;
    }

    @Test
    void serverWithWarningOrError_isIncluded() {
        LocalDateTime aggregateTo = LocalDateTime.of(2026, 6, 18, 9, 30, 1);
        when(analyzeResultMapper.selectDistinctServerIds()).thenReturn(List.of("srv01"));
        when(notifyHistoryMapper.findLastWatermark("srv01")).thenReturn(LocalDateTime.of(2026, 6, 18, 9, 0, 1));
        when(analyzeResultMapper.countByLevelInWindow(eq("srv01"), any(), any()))
                .thenReturn(List.of(level("정상", 5), level("경고", 2), level("에러", 1)));

        List<ServerAggregateResult> results = service.aggregate(periodicSchedule(30), aggregateTo);

        assertThat(results).hasSize(1);
        ServerAggregateResult result = results.get(0);
        assertThat(result.getServerId()).isEqualTo("srv01");
        assertThat(result.getNormalCount()).isEqualTo(5);
        assertThat(result.getWarningCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getAggregateFrom()).isEqualTo(LocalDateTime.of(2026, 6, 18, 9, 0, 1));
        assertThat(result.getAggregateTo()).isEqualTo(aggregateTo);
        assertThat(result.getMessage()).isEqualTo("[srv01] 정상5 경고2 에러1 (09:00~09:30)");
    }

    @Test
    void serverWithOnlyNormal_isExcluded() {
        when(analyzeResultMapper.selectDistinctServerIds()).thenReturn(List.of("srv01"));
        when(notifyHistoryMapper.findLastWatermark("srv01")).thenReturn(LocalDateTime.of(2026, 6, 18, 9, 0, 1));
        when(analyzeResultMapper.countByLevelInWindow(eq("srv01"), any(), any()))
                .thenReturn(List.of(level("정상", 10)));

        List<ServerAggregateResult> results = service.aggregate(periodicSchedule(30), LocalDateTime.of(2026, 6, 18, 9, 30, 1));

        assertThat(results).isEmpty();
    }

    @Test
    void noWatermark_periodicSchedule_defaultsToOneIntervalBack() {
        LocalDateTime aggregateTo = LocalDateTime.of(2026, 6, 18, 9, 30, 1);
        when(analyzeResultMapper.selectDistinctServerIds()).thenReturn(List.of("srv01"));
        when(notifyHistoryMapper.findLastWatermark("srv01")).thenReturn(null);
        when(analyzeResultMapper.countByLevelInWindow(eq("srv01"), any(), any()))
                .thenReturn(List.of(level("에러", 1)));

        List<ServerAggregateResult> results = service.aggregate(periodicSchedule(30), aggregateTo);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAggregateFrom()).isEqualTo(aggregateTo.minusMinutes(30));
    }

    @Test
    void noWatermark_batchSchedule_defaultsToStartOfToday() {
        LocalDateTime aggregateTo = LocalDateTime.of(2026, 6, 18, 18, 0, 1);
        when(analyzeResultMapper.selectDistinctServerIds()).thenReturn(List.of("srv01"));
        when(notifyHistoryMapper.findLastWatermark("srv01")).thenReturn(null);
        when(analyzeResultMapper.countByLevelInWindow(eq("srv01"), any(), any()))
                .thenReturn(List.of(level("경고", 1)));

        List<ServerAggregateResult> results = service.aggregate(batchSchedule(), aggregateTo);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAggregateFrom()).isEqualTo(LocalDateTime.of(2026, 6, 18, 0, 0, 0));
    }

    @Test
    void multipleServers_onlyAlertingOnesIncluded() {
        LocalDateTime aggregateTo = LocalDateTime.of(2026, 6, 18, 9, 30, 1);
        when(analyzeResultMapper.selectDistinctServerIds()).thenReturn(List.of("srv01", "srv02", "srv03"));
        when(notifyHistoryMapper.findLastWatermark(any())).thenReturn(LocalDateTime.of(2026, 6, 18, 9, 0, 1));
        when(analyzeResultMapper.countByLevelInWindow(eq("srv01"), any(), any()))
                .thenReturn(List.of(level("정상", 1)));
        when(analyzeResultMapper.countByLevelInWindow(eq("srv02"), any(), any()))
                .thenReturn(List.of(level("경고", 1)));
        when(analyzeResultMapper.countByLevelInWindow(eq("srv03"), any(), any()))
                .thenReturn(List.of(level("에러", 1)));

        List<ServerAggregateResult> results = service.aggregate(periodicSchedule(30), aggregateTo);

        assertThat(results).extracting(ServerAggregateResult::getServerId).containsExactly("srv02", "srv03");
    }
}
