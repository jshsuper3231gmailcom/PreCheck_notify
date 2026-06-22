package com.sks.precheck.notify.service;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.exception.NotifyException;
import com.sks.precheck.notify.domain.AnalyzeLevelCount;
import com.sks.precheck.notify.mapper.AnalyzeResultMapper;
import com.sks.precheck.notify.mapper.NotifyHistoryMapper;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import com.sks.precheck.notify.vo.ServerAggregateResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 서버별 정상/경고/에러 집계 — 통보_DB정의서.md 3절(②~③), 통보_스케쥴러정의서.md "통보 스케쥴 동작 방식" 참고
 *
 * 워터마크가 없는 신규 서버는 AGGREGATE_FROM 기본값을 사용한다:
 *   주기 스케쥴 → 이번 실행 시각(aggregateTo) - 1주기(통보간격분)
 *   배치 스케쥴 → 당일 00:00:00
 * 경고+에러 합계가 0인 서버는 결과에서 제외한다(통보 대상 아님).
 */
@Service
public class NotifyAggregationService {

    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final AnalyzeResultMapper analyzeResultMapper;
    private final NotifyHistoryMapper notifyHistoryMapper;

    public NotifyAggregationService(AnalyzeResultMapper analyzeResultMapper, NotifyHistoryMapper notifyHistoryMapper) {
        this.analyzeResultMapper = analyzeResultMapper;
        this.notifyHistoryMapper = notifyHistoryMapper;
    }

    public List<ServerAggregateResult> aggregate(NotifyScheduleVo schedule, LocalDateTime aggregateTo) {
        List<ServerAggregateResult> targets = new ArrayList<>();

        for (String serverId : analyzeResultMapper.selectDistinctServerIds()) {
            LocalDateTime watermark = notifyHistoryMapper.findLastWatermark(serverId);
            LocalDateTime aggregateFrom = watermark != null ? watermark : defaultAggregateFrom(schedule, aggregateTo);

            long normalCount = 0;
            long warningCount = 0;
            long errorCount = 0;
            for (AnalyzeLevelCount levelCount : analyzeResultMapper.countByLevelInWindow(serverId, aggregateFrom, aggregateTo)) {
                switch (levelCount.getAnalyzeLevel()) {
                    case NotifyConstants.LEVEL_NORMAL -> normalCount = levelCount.getCnt();
                    case NotifyConstants.LEVEL_WARNING -> warningCount = levelCount.getCnt();
                    case NotifyConstants.LEVEL_ERROR -> errorCount = levelCount.getCnt();
                    default -> { /* 정보/미분석은 집계 대상이 아님 */ }
                }
            }

            if (warningCount + errorCount == 0) {
                continue;
            }

            ServerAggregateResult result = new ServerAggregateResult();
            result.setServerId(serverId);
            result.setAggregateFrom(aggregateFrom);
            result.setAggregateTo(aggregateTo);
            result.setNormalCount(normalCount);
            result.setWarningCount(warningCount);
            result.setErrorCount(errorCount);
            result.setMessage(buildMessage(serverId, normalCount, warningCount, errorCount, aggregateFrom, aggregateTo));
            targets.add(result);
        }

        return targets;
    }

    private LocalDateTime defaultAggregateFrom(NotifyScheduleVo schedule, LocalDateTime aggregateTo) {
        if (NotifyConstants.SCHEDULE_TYPE_PERIODIC.equals(schedule.getScheduleType())) {
            Integer intervalMinutes = schedule.getIntervalMinutes();
            if (intervalMinutes == null) {
                throw new NotifyException("주기 스케쥴인데 통보간격(intervalMinutes)이 없다");
            }
            return aggregateTo.minusMinutes(intervalMinutes);
        }
        return aggregateTo.toLocalDate().atStartOfDay();
    }

    private String buildMessage(String serverId, long normalCount, long warningCount, long errorCount,
                                 LocalDateTime aggregateFrom, LocalDateTime aggregateTo) {
        return "[" + serverId + "] 정상" + normalCount + " 경고" + warningCount + " 에러" + errorCount
                + " (" + aggregateFrom.format(MESSAGE_TIME_FORMATTER) + "~" + aggregateTo.format(MESSAGE_TIME_FORMATTER) + ")";
    }
}
