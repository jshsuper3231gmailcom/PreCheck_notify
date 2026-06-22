package com.sks.precheck.notify.scheduler;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.exception.NotifyException;
import com.sks.precheck.notify.parser.NotifyScheduleParser;
import com.sks.precheck.notify.service.NotifyService;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 통보 스케쥴러 — 60초마다 실행, 스케쥴 파일(PreCheck_NotifyLogs_Schedule.conf)을 읽어 통보 실행 시점을 판별
 *
 * collect/analyze의 스케쥴러와 동일한 폴링/캐싱/중복방지 패턴을 따르지만, 통보 스케쥴은 서버/파일 구분이
 * 없는 전역 한 줄([통보주기기술])이라 중복방지 키는 스케쥴 라인 자체(toScheduleExpression())만으로 충분하다.
 * AGGREGATE_TO로 넘기는 시각은 실제 감지된 wall-clock이 아니라 conf에 정의된 슬롯의 명목 시각이다
 * (통보_스케쥴러정의서.md "통보 스케쥴 동작 방식" 참고).
 */
@Component
public class NotifyScheduler {

    private static final Logger log = LogManager.getLogger(NotifyScheduler.class);

    private static final String DEFAULT_SCHEDULE_FILE_RELATIVE_PATH = "/cfg/PreCheck_NotifyLogs_Schedule.conf";
    private static final int POLL_WINDOW_SECONDS = 60;

    private final NotifyService notifyService;
    private final NotifyScheduleParser notifyScheduleParser;

    private final String scheduleFilePath;
    private final long reloadIntervalMillis;
    private volatile long lastReloadAtMillis;
    private volatile List<NotifyScheduleVo> cachedSchedules;

    private final Map<String, String> lastBatchRunDateByKey = new HashMap<>();
    private final Map<String, Long> lastPeriodicRunIndexByKey = new HashMap<>();

    public NotifyScheduler(
            NotifyService notifyService,
            @Value("${precheck.notify.schedule-file-path:}") String scheduleFilePath,
            @Value("${precheck.notify.scheduler.reload-interval-ms:60000}") long reloadIntervalMillis
    ) {
        this.notifyService = notifyService;
        this.notifyScheduleParser = new NotifyScheduleParser();
        this.scheduleFilePath = (scheduleFilePath == null || scheduleFilePath.isBlank())
                ? System.getProperty("user.home") + DEFAULT_SCHEDULE_FILE_RELATIVE_PATH
                : scheduleFilePath;
        this.reloadIntervalMillis = reloadIntervalMillis;
    }

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        List<NotifyScheduleVo> schedules = getSchedules();
        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (NotifyScheduleVo schedule : schedules) {
            try {
                LocalDateTime slotTime = resolveSlotTimeIfShouldRun(schedule, now);
                if (slotTime != null) {
                    notifyService.runNotify(schedule, slotTime);
                }
            } catch (Exception e) {
                log.error("통보 스케쥴 실행 실패 - schedule: {}", schedule.toScheduleExpression(), e);
            }
        }
    }

    private List<NotifyScheduleVo> getSchedules() {
        long nowMillis = System.currentTimeMillis();
        if (cachedSchedules != null && nowMillis - lastReloadAtMillis < reloadIntervalMillis) {
            return cachedSchedules;
        }

        try {
            List<NotifyScheduleVo> schedules = notifyScheduleParser.parseScheduleFile(scheduleFilePath);
            cachedSchedules = schedules;
            lastReloadAtMillis = nowMillis;
            return schedules;
        } catch (NotifyException e) {
            log.error("통보 스케쥴 파일 파싱 실패 - file: {}", scheduleFilePath, e);
            cachedSchedules = List.of();
            lastReloadAtMillis = nowMillis;
            return cachedSchedules;
        }
    }

    /** 이번 폴링에서 실행해야 하면 AGGREGATE_TO로 쓸 슬롯의 명목 시각을 반환, 아니면 null. */
    private LocalDateTime resolveSlotTimeIfShouldRun(NotifyScheduleVo schedule, LocalDateTime now) {
        if (!isTodayMatched(schedule.getDayOfWeek(), now.toLocalDate())) {
            return null;
        }

        int nowSeconds = now.toLocalTime().toSecondOfDay();
        int startSeconds = parseTime(schedule.getStartTime()).toSecondOfDay();

        if (NotifyConstants.SCHEDULE_TYPE_BATCH.equals(schedule.getScheduleType())) {
            return resolveBatchSlotTime(schedule, now, nowSeconds, startSeconds);
        }

        return resolvePeriodicSlotTime(schedule, now, nowSeconds, startSeconds);
    }

    private LocalDateTime resolveBatchSlotTime(NotifyScheduleVo schedule, LocalDateTime now, int nowSeconds, int startSeconds) {
        if (nowSeconds < startSeconds || nowSeconds >= startSeconds + POLL_WINDOW_SECONDS) {
            return null;
        }

        String key = schedule.toScheduleExpression();
        String today = now.toLocalDate().toString();
        if (today.equals(lastBatchRunDateByKey.get(key))) {
            return null;
        }

        lastBatchRunDateByKey.put(key, today);
        LocalDateTime slotTime = LocalDateTime.of(now.toLocalDate(), parseTime(schedule.getStartTime()));
        log.info("[[[배치 통보 실행 결정]]] - schedule: {}, slotTime: {}", key, slotTime);
        return slotTime;
    }

    private LocalDateTime resolvePeriodicSlotTime(NotifyScheduleVo schedule, LocalDateTime now, int nowSeconds, int startSeconds) {
        Integer intervalMinutes = schedule.getIntervalMinutes();
        String endTimeText = schedule.getEndTime();
        if (intervalMinutes == null || endTimeText == null || endTimeText.isBlank()) {
            return null;
        }

        int endSeconds = parseTime(endTimeText).toSecondOfDay();
        if (startSeconds >= endSeconds) {
            log.warn("주기 스케쥴 시간 범위 오류(startTime >= endTime) - schedule: {}", schedule.toScheduleExpression());
            return null;
        }
        if (nowSeconds < startSeconds || nowSeconds > endSeconds) {
            return null;
        }

        long intervalSeconds = (long) intervalMinutes * 60L;
        long offsetSeconds = nowSeconds - startSeconds;

        long runIndex = offsetSeconds / intervalSeconds;
        long remainder = offsetSeconds % intervalSeconds;
        if (remainder < 0 || remainder >= POLL_WINDOW_SECONDS) {
            return null;
        }

        String key = schedule.toScheduleExpression();
        Long lastIndex = lastPeriodicRunIndexByKey.get(key);
        if (lastIndex != null && lastIndex == runIndex) {
            return null;
        }

        lastPeriodicRunIndexByKey.put(key, runIndex);
        LocalDateTime slotTime = LocalDateTime.of(now.toLocalDate(), parseTime(schedule.getStartTime()))
                .plusSeconds(runIndex * intervalSeconds);
        log.info("[[[주기 통보 실행 결정]]] - schedule: {}, {}번째 실행, slotTime: {}", key, runIndex + 1, slotTime);
        return slotTime;
    }

    private boolean isTodayMatched(String daySpec, LocalDate date) {
        if ("*".equals(daySpec)) {
            return true;
        }

        int today = toDayDigit(date.getDayOfWeek());
        if (daySpec != null && daySpec.contains("-")) {
            String[] range = daySpec.split("-", -1);
            if (range.length != 2) {
                return false;
            }
            Integer start = parseDayDigit(range[0].trim());
            Integer end = parseDayDigit(range[1].trim());
            if (start == null || end == null) {
                return false;
            }
            if (start == 0 && end == 6) {
                return true;
            }
            return today >= start && today <= end;
        }

        Integer day = parseDayDigit(daySpec != null ? daySpec.trim() : null);
        return day != null && day == today;
    }

    private Integer parseDayDigit(String text) {
        if (text == null || text.length() != 1) {
            return null;
        }
        char c = text.charAt(0);
        if (c < '0' || c > '6') {
            return null;
        }
        return c - '0';
    }

    private int toDayDigit(DayOfWeek dayOfWeek) {
        int value = dayOfWeek.getValue();
        return value % 7;
    }

    private LocalTime parseTime(String hhmmss) {
        if (hhmmss == null || hhmmss.length() != 6) {
            throw new NotifyException("시간 포맷 오류(HHmmss): " + hhmmss);
        }

        int hh;
        int mm;
        int ss;
        try {
            hh = Integer.parseInt(hhmmss.substring(0, 2));
            mm = Integer.parseInt(hhmmss.substring(2, 4));
            ss = Integer.parseInt(hhmmss.substring(4, 6));
        } catch (NumberFormatException e) {
            throw new NotifyException("시간 포맷 오류(HHmmss): " + hhmmss);
        }

        return LocalTime.of(hh, mm, ss);
    }
}
