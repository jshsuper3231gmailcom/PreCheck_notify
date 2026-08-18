package com.sks.precheck.notify.parser;

import com.sks.precheck.notify.common.exception.NotifyException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 비영업일(휴장일) 목록 파일(PreCheck_NotifyHoliday_List.conf) 파서
 *
 * 한 줄에 날짜 1개(yyyyMMdd), UTF-8, '#' skip, 빈 줄 무시, 포맷에 맞지 않는 줄은
 * 해당 줄만 skip(전체 파싱 실패시키지 않음) (통보_스케쥴러정의서.md "비영업일(휴장일) 제외 규칙" 참고)
 */
public class NotifyHolidayParser {

    private static final Logger log = LogManager.getLogger(NotifyHolidayParser.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    public Set<LocalDate> parseHolidayFile(String filePath) {
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            log.warn("비영업일 목록 파일이 존재하지 않음 - filePath: {}, absolutePath: {}", filePath, path.toAbsolutePath());
            return Set.of();
        }

        Set<LocalDate> holidays = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                LocalDate holiday = parseLine(line);
                if (holiday != null) {
                    holidays.add(holiday);
                }
            }
        } catch (IOException e) {
            log.error("비영업일 목록 파일 읽기 실패 - filePath: {}, absolutePath: {}, error: {}",
                    filePath, path.toAbsolutePath(), e.getMessage());
            throw new NotifyException("비영업일 목록 파일 읽기 실패: " + filePath, e);
        }

        log.info("비영업일 목록 파일 파싱 완료 - absolutePath: {}, 등록된 비영업일 수: {}", path.toAbsolutePath(), holidays.size());
        return holidays;
    }

    private LocalDate parseLine(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        try {
            return LocalDate.parse(trimmed, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("비영업일 날짜 포맷 오류(yyyyMMdd) - line: {}", trimmed);
            return null;
        }
    }
}
