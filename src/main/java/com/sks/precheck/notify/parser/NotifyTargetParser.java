package com.sks.precheck.notify.parser;

import com.sks.precheck.notify.common.exception.NotifyException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SMS 수신대상(전송대상) 파일(PreCheck_NotifyTarget_List.conf) 파서
 *
 * 한 줄에 전화번호 1개, UTF-8, '#' skip, 빈 줄 무시, 숫자 외 문자 제거 후 중복 번호는 합침
 * (통보_스케쥴러정의서.md "수신자(전송대상) 파일 규칙" 참고)
 */
public class NotifyTargetParser {

    private static final Logger log = LogManager.getLogger(NotifyTargetParser.class);

    public List<String> parseTargetFile(String filePath) {
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            log.warn("수신대상 파일이 존재하지 않음 - filePath: {}, absolutePath: {}", filePath, path.toAbsolutePath());
            return List.of();
        }

        Set<String> phoneNumbers = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                String phoneNumber = parseLine(line);
                if (phoneNumber != null) {
                    phoneNumbers.add(phoneNumber);
                }
            }
        } catch (IOException e) {
            log.error("수신대상 파일 읽기 실패 - filePath: {}, absolutePath: {}, error: {}",
                    filePath, path.toAbsolutePath(), e.getMessage());
            throw new NotifyException("수신대상 파일 읽기 실패: " + filePath, e);
        }

        log.info("수신대상 파일 파싱 완료 - absolutePath: {}, 유효 수신자 수: {}", path.toAbsolutePath(), phoneNumbers.size());
        return new ArrayList<>(phoneNumbers);
    }

    private String parseLine(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        String digitsOnly = trimmed.replaceAll("[^0-9]", "");
        return digitsOnly.isEmpty() ? null : digitsOnly;
    }
}
