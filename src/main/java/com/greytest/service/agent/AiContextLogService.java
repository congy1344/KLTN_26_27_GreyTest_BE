package com.greytest.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
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
    private final boolean consoleEnabled;

    @Autowired
    public AiContextLogService(
            ObjectMapper objectMapper,
            @Value("${greytest.ai-context-log-path:../log}") String logPath,
            @Value("${greytest.ai-context-log-enabled:false}") boolean enabled,
            @Value("${greytest.ai-context-log-console-enabled:false}") boolean consoleEnabled) {
        this.objectMapper = objectMapper;
        this.logDir = resolveLogDir(logPath, Path.of("").toAbsolutePath());
        this.enabled = enabled;
        this.consoleEnabled = consoleEnabled;
        if (enabled) {
            log.info("AI context log dir: {}", logDir.toAbsolutePath());
        }
    }

    AiContextLogService(ObjectMapper objectMapper, String logPath) {
        this(objectMapper, logPath, true, false);
    }

    public void write(String promptName, Object context, String prompt) {
        if (!enabled) return;
        try {
            String contextJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
            Files.createDirectories(logDir);
            Path file = logDir.resolve(FILE_TIME.format(LocalDateTime.now()) + "-" + safeName(promptName) + ".log");
            Files.writeString(file, content(promptName, contextJson, prompt), StandardCharsets.UTF_8);
            log.info("AI context/prompt saved to {} ({} bytes)", file.toAbsolutePath(), Files.size(file));
            if (consoleEnabled) {
                log.info("AI context payload for {}:\n{}", promptName, contextJson);
            }
        } catch (JsonProcessingException exception) {
            log.warn("Khong serialize duoc AI context cho {}", promptName, exception);
        } catch (IOException exception) {
            log.warn("Khong ghi duoc AI context log cho {}", promptName, exception);
        }
    }

    public void writeResponse(String promptName, int attempt, String response) {
        if (!enabled) return;
        try {
            Files.createDirectories(logDir);
            Path file = logDir.resolve(FILE_TIME.format(LocalDateTime.now()) + "-"
                    + safeName(promptName) + "-response-" + attempt + ".log");
            Files.writeString(file, response == null ? "" : response, StandardCharsets.UTF_8);
            log.info("AI response saved to {} ({} bytes)", file.toAbsolutePath(), Files.size(file));
        } catch (IOException exception) {
            log.warn("Khong ghi duoc AI response log cho {}", promptName, exception);
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

    static Path resolveLogDir(String logPath, Path cwd) {
        String value = logPath == null || logPath.isBlank() ? "../log" : logPath.trim();
        Path configured = Path.of(value);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path projectRoot = findProjectRoot(cwd);
        if (projectRoot != null && isFallbackLogPath(value)) {
            return projectRoot.resolve("log").normalize();
        }
        return cwd.resolve(configured).normalize();
    }

    private static Path findProjectRoot(Path cwd) {
        Path current = cwd.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("backend")) && Files.isDirectory(current.resolve("frontend"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isFallbackLogPath(String value) {
        String normalized = value.replace('\\', '/');
        return "../log".equals(normalized);
    }
}
