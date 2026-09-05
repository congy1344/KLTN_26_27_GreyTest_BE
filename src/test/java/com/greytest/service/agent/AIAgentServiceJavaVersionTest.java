package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.AnalysisSummaryDto;
import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.service.agent.ProjectJavaVersionDetector.JavaVersionInfo;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFrameworkInfo;

import jakarta.validation.Validation;

class AIAgentServiceJavaVersionTest {

    @Test
    void addsDetectedJavaVersionToUnitTestPrompt(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationContextBuilder contextBuilder = mock(GenerationContextBuilder.class);
        when(contextBuilder.buildUnitTestContext(1L)).thenReturn(emptyContext());
        ProjectJavaVersionDetector detector = mock(ProjectJavaVersionDetector.class);
        when(detector.detect(eq(1L), anyList())).thenReturn(Optional.of(new JavaVersionInfo("8", "pom.xml")));
        when(detector.detectTestFramework(eq(1L), anyList()))
                .thenReturn(Optional.of(new TestFrameworkInfo(TestFramework.JUNIT4, "pom.xml")));
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        LlmClient client = prompt -> {
            capturedPrompt.set(prompt);
            return "{\"unit_tests\":[{\"case_id\":1,\"test_class_name\":\"GeneratedServiceTest\",\"test_method_name\":\"case1\",\"package_name\":\"demo\",\"generation_type\":\"NEW_TEST\",\"source_code\":\"package demo; class GeneratedServiceTest {}\"}]}";
        };
        AIAgentService service = new AIAgentService(
                contextBuilder,
                new PromptManager(objectMapper),
                client,
                new GenerationResponseParser(objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()),
                new AiContextLogService(objectMapper, tempDir.toString()),
                detector);

        service.generateUnitTests(1L);

        assertThat(capturedPrompt.get())
                .contains("# Java compatibility detected", "Build file `pom.xml` declares Java 8",
                        "Use only syntax and APIs available in Java 8", "JUnit 4 only",
                        "never import org.junit.jupiter", "try/fail/catch");
    }

    private UnitTestContextDto emptyContext() {
        return new UnitTestContextDto(
                new ProjectContextDto(1L, "demo", "CASE_APPROVED"),
                new AnalysisSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
