package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class AiContextLogServiceTest {

    @TempDir
    Path logDir;

    @Test
    void concurrentWritesAlwaysUseDifferentFiles() throws Exception {
        AiContextLogService service = new AiContextLogService(new ObjectMapper(), logDir.toString());

        IntStream.range(0, 20).parallel()
                .forEach(index -> service.write("unit-test", java.util.Map.of("batch", index), "prompt"));

        try (var files = Files.list(logDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith("-unit-test.log")).count())
                    .isEqualTo(20);
        }
    }
}
