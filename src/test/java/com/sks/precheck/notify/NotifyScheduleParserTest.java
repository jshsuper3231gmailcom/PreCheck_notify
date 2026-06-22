package com.sks.precheck.notify;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.precheck.notify.parser.NotifyScheduleParser;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifyScheduleParserTest {

    private final NotifyScheduleParser parser = new NotifyScheduleParser();

    @TempDir
    Path tempDir;

    @Test
    void parse_batch() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyLogs_Schedule.conf");
        Files.writeString(file, "[배치|1-5|180001]\n", StandardCharsets.UTF_8);

        List<NotifyScheduleVo> schedules = parser.parseScheduleFile(file.toString());
        assertEquals(1, schedules.size());

        NotifyScheduleVo vo = schedules.get(0);
        assertEquals("배치", vo.getScheduleType());
        assertEquals("1-5", vo.getDayOfWeek());
        assertEquals("180001", vo.getStartTime());
        assertNull(vo.getIntervalMinutes());
        assertNull(vo.getEndTime());
    }

    @Test
    void parse_periodic() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyLogs_Schedule.conf");
        Files.writeString(file, "[주기|*|090001|30|180001]\n", StandardCharsets.UTF_8);

        List<NotifyScheduleVo> schedules = parser.parseScheduleFile(file.toString());
        assertEquals(1, schedules.size());

        NotifyScheduleVo vo = schedules.get(0);
        assertEquals("주기", vo.getScheduleType());
        assertEquals("*", vo.getDayOfWeek());
        assertEquals("090001", vo.getStartTime());
        assertEquals(30, vo.getIntervalMinutes());
        assertEquals("180001", vo.getEndTime());
    }

    @Test
    void parse_skipLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyLogs_Schedule.conf");
        Files.writeString(file, "#[배치|*|090001]\n", StandardCharsets.UTF_8);

        List<NotifyScheduleVo> schedules = parser.parseScheduleFile(file.toString());
        assertTrue(schedules.isEmpty());
    }

    @Test
    void parse_blankLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyLogs_Schedule.conf");
        Files.writeString(file, "\n[배치|*|090001]\n\n", StandardCharsets.UTF_8);

        List<NotifyScheduleVo> schedules = parser.parseScheduleFile(file.toString());
        assertEquals(1, schedules.size());
    }

    @Test
    void parse_invalidLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyLogs_Schedule.conf");
        // 주기는 5개 토큰이 필요한데 4개만 있음 (종료시각 누락)
        Files.writeString(file, "[주기|*|090001|10]\n", StandardCharsets.UTF_8);

        List<NotifyScheduleVo> schedules = parser.parseScheduleFile(file.toString());
        assertTrue(schedules.isEmpty());
    }

    @Test
    void parse_multipleLines_allCollected() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyLogs_Schedule.conf");
        Files.writeString(file, "[배치|1-5|180001]\n[주기|*|090001|30|180001]\n", StandardCharsets.UTF_8);

        List<NotifyScheduleVo> schedules = parser.parseScheduleFile(file.toString());
        assertEquals(2, schedules.size());
    }
}
