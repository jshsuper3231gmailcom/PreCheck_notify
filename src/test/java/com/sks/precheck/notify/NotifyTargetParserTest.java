package com.sks.precheck.notify;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.precheck.notify.parser.NotifyTargetParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifyTargetParserTest {

    private final NotifyTargetParser parser = new NotifyTargetParser();

    @TempDir
    Path tempDir;

    @Test
    void parse_plainNumbers() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, "01012345678\n01087654321\n", StandardCharsets.UTF_8);

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertEquals(List.of("01012345678", "01087654321"), numbers);
    }

    @Test
    void parse_hyphensStripped() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, "010-1234-5678\n", StandardCharsets.UTF_8);

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertEquals(List.of("01012345678"), numbers);
    }

    @Test
    void parse_skipLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, "#010-0000-0000\n010-1234-5678\n", StandardCharsets.UTF_8);

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertEquals(List.of("01012345678"), numbers);
    }

    @Test
    void parse_blankLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, "\n010-1234-5678\n\n", StandardCharsets.UTF_8);

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertEquals(List.of("01012345678"), numbers);
    }

    @Test
    void parse_duplicateNumbers_areMerged() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, "010-1234-5678\n01012345678\n", StandardCharsets.UTF_8);

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertEquals(List.of("01012345678"), numbers);
    }

    @Test
    void parse_missingFile_returnsEmptyList() {
        Path file = tempDir.resolve("does-not-exist.conf");

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertTrue(numbers.isEmpty());
    }

    @Test
    void parse_emptyFile_returnsEmptyList() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyTarget_List.conf");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        List<String> numbers = parser.parseTargetFile(file.toString());
        assertTrue(numbers.isEmpty());
    }
}
