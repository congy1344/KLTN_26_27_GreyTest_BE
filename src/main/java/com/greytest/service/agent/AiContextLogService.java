package com.greytest.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Ghi lai context/prompt truoc khi goi LLM de debug va doi chieu khi bao ve.
 */
@Slf4j
@Service
public class AiContextLogService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ObjectMapper objectMapper;
    private final Path logDir;
    private final boolean enabled;

    public AiContextLogService(
            ObjectMapper objectMapper,
            @Value("${greytest.ai-context-log-path:../log}") String logPath,
            @Value("${greytest.ai-context-log-enabled:false}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.logDir = Path.of(logPath).normalize();
        this.enabled = enabled;
    }

    AiContextLogService(ObjectMapper objectMapper, String logPath) {
        this(objectMapper, logPath, true);
    }

    public void write(String promptName, Object context, String prompt) {
        if (!enabled) return;
        try {
            String contextJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
            Files.createDirectories(logDir);
            Path file = logDir.resolve(FILE_TIME.format(LocalDateTime.now()) + "-" + safeName(promptName) + ".log");
            Files.writeString(file, content(promptName, contextJson, prompt), StandardCharsets.UTF_8);
            log.debug("AI context/prompt saved to {}", file.toAbsolutePath());
        } catch (JsonProcessingException exception) {
            log.warn("Khong serialize duoc AI context cho {}", promptName, exception);
        } catch (IOException exception) {
            log.warn("Khong ghi duoc AI context log cho {}", promptName, exception);
        }
    }

    private String content(String promptName, String contextJson, String prompt) {
        return """
                # AI Context Log
                prompt_name: %s

                ## context_json
                ```json
                %s
                ```

                ## rendered_prompt
                ```text
                %s
                ```
                """.formatted(promptName, contextJson, prompt);
    }

    private String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9-]", "-");
    }
}
