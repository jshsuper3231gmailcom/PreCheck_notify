package com.sks.precheck.notify.parser;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.exception.NotifyException;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 통보 스케쥴 파일(PreCheck_NotifyLogs_Schedule.conf) 파서
 *
 * 포맷: [통보주기기술] 한 줄 — 수집/분석 정의서와 달리 서버구분/대상파일명 필드가 없음 (통보_스케쥴러정의서.md 참고)
 */
public class NotifyScheduleParser {

    private static final Logger log = LogManager.getLogger(NotifyScheduleParser.class);

    public List<NotifyScheduleVo> parseScheduleFile(String filePath) {
        Path path = Path.of(filePath);
        log.info("통보 스케쥴 파일 파싱 시작 - filePath: {}, absolutePath: {}", filePath, path.toAbsolutePath());

        List<NotifyScheduleVo> result = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                NotifyScheduleVo schedule = parseLine(lines.get(i), i + 1);
                if (schedule != null) {
                    result.add(schedule);
                }
            }
        } catch (IOException e) {
            log.error("통보 스케쥴 파일 읽기 실패 - filePath: {}, absolutePath: {}, error: {}",
                    filePath, path.toAbsolutePath(), e.getMessage());
            throw new NotifyException("통보 스케쥴 파일 읽기 실패: " + filePath, e);
        }

        log.info("통보 스케쥴 파일 파싱 완료 - absolutePath: {}, 유효 스케쥴 건수: {}", path.toAbsolutePath(), result.size());
        return result;
    }

    private NotifyScheduleVo parseLine(String line, int lineNumber) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        String scheduleExpression = extractSingleBracketToken(trimmed);
        if (scheduleExpression == null || scheduleExpression.isEmpty()) {
            log.warn("통보 스케쥴 라인 포맷 오류로 무시 - lineNumber: {}, line: {}", lineNumber, trimmed);
            return null;
        }

        ScheduleParts parts = parseScheduleExpression(scheduleExpression);
        if (parts == null) {
            log.warn("통보주기기술 포맷 오류로 무시 - lineNumber: {}, schedule: {}", lineNumber, scheduleExpression);
            return null;
        }

        NotifyScheduleVo vo = new NotifyScheduleVo();
        vo.setScheduleType(parts.type);
        vo.setDayOfWeek(parts.daySpec);
        vo.setStartTime(parts.startTime);
        vo.setIntervalMinutes(parts.intervalMinutes);
        vo.setEndTime(parts.endTime);
        return vo;
    }

    private String extractSingleBracketToken(String text) {
        int start = text.indexOf('[');
        int end = text.indexOf(']', start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        return text.substring(start + 1, end).trim();
    }

    private ScheduleParts parseScheduleExpression(String scheduleExpression) {
        String[] parts = scheduleExpression.split("\\|", -1);
        if (parts.length == 0) {
            return null;
        }

        String type = parts[0].trim();
        if (NotifyConstants.SCHEDULE_TYPE_BATCH.equals(type)) {
            if (parts.length != 3) {
                return null;
            }
            String daySpec = parts[1].trim();
            String startTime = parts[2].trim();
            if (!isValidDaySpec(daySpec) || !isValidTimeHhmmss(startTime, true)) {
                return null;
            }
            return new ScheduleParts(type, daySpec, startTime, null, null);
        }

        if (NotifyConstants.SCHEDULE_TYPE_PERIODIC.equals(type)) {
            if (parts.length != 5) {
                return null;
            }

            String daySpec = parts[1].trim();
            String startTime = parts[2].trim();
            String intervalMinutesText = parts[3].trim();
            String endTime = parts[4].trim();

            if (!isValidDaySpec(daySpec) || !isValidTimeHhmmss(startTime, true) || !isValidIntervalMinutes(intervalMinutesText)
                    || !isValidTimeHhmmss(endTime, false)) {
                return null;
            }

            if (Integer.parseInt(startTime) >= Integer.parseInt(endTime)) {
                return null;
            }

            return new ScheduleParts(type, daySpec, startTime, Integer.parseInt(intervalMinutesText), endTime);
        }

        return null;
    }

    private boolean isValidDaySpec(String daySpec) {
        if (daySpec == null || daySpec.isEmpty()) {
            return false;
        }
        if (daySpec.contains(",") || daySpec.contains(" ")) {
            return false;
        }
        if ("*".equals(daySpec)) {
            return true;
        }
        if (daySpec.contains("-")) {
            String[] range = daySpec.split("-", -1);
            if (range.length != 2) {
                return false;
            }
            Integer start = parseDay(range[0]);
            Integer end = parseDay(range[1]);
            if (start == null || end == null) {
                return false;
            }
            return start <= end;
        }
        return parseDay(daySpec) != null;
    }

    private Integer parseDay(String text) {
        if (text == null || text.length() != 1) {
            return null;
        }
        char c = text.charAt(0);
        if (c < '0' || c > '6') {
            return null;
        }
        return c - '0';
    }

    private boolean isValidTimeHhmmss(String timeText, boolean isStartTime) {
        if (timeText == null || timeText.length() != 6) {
            return false;
        }

        int time;
        try {
            time = Integer.parseInt(timeText);
        } catch (NumberFormatException e) {
            return false;
        }

        int hh = time / 10000;
        int mm = (time / 100) % 100;
        int ss = time % 100;
        if (hh < 0 || hh > 23) {
            return false;
        }
        if (mm < 0 || mm > 59) {
            return false;
        }
        if (ss < 0 || ss > 59) {
            return false;
        }

        if (isStartTime) {
            return time >= 1;
        }
        return time <= 235959;
    }

    private boolean isValidIntervalMinutes(String minutesText) {
        if (minutesText == null || minutesText.isEmpty()) {
            return false;
        }
        try {
            int minutes = Integer.parseInt(minutesText);
            return minutes > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static class ScheduleParts {
        private final String type;
        private final String daySpec;
        private final String startTime;
        private final Integer intervalMinutes;
        private final String endTime;

        private ScheduleParts(String type, String daySpec, String startTime, Integer intervalMinutes, String endTime) {
            this.type = type;
            this.daySpec = daySpec;
            this.startTime = startTime;
            this.intervalMinutes = intervalMinutes;
            this.endTime = endTime;
        }
    }
}
