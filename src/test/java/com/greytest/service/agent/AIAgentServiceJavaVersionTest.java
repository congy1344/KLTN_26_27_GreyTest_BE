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
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.service.agent.ProjectJavaVersionDetector.JavaVersionInfo;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFrameworkInfo;

import jakarta.validation.Validation;

class AIAgentServiceJavaVersionTest {

    @Test
    void snapshotGenerationUsesCandidateBuildInsteadOfBaseline(@TempDir Path root) throws Exception {
        Path baseline = java.nio.file.Files.createDirectories(root.resolve("baseline"));
        Path candidate = java.nio.file.Files.createDirectories(root.resolve("candidate"));
        java.nio.file.Files.writeString(baseline.resolve("pom.xml"), """
                <project><properties><java.version>8</java.version></properties><dependencies>
                <dependency><groupId>junit</groupId><artifactId>junit</artifactId></dependency></dependencies></project>
                """);
        java.nio.file.Files.writeString(candidate.resolve("pom.xml"), """
                <project><properties><java.version>17</java.version></properties><dependencies>
                <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId></dependency></dependencies></project>
                """);
        Path candidateSource = candidate.resolve("src/main/java/demo/GeneratedService.java");
        java.nio.file.Files.createDirectories(candidateSource.getParent());
        java.nio.file.Files.writeString(candidateSource, context().classes().get(0).sourceCode());
        var projects = mock(com.greytest.repository.ProjectRepository.class);
        var project = new com.greytest.entity.Project();
        project.setStoragePath(baseline.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        GenerationContextBuilder contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildSnapshotUnitTestContext(1L, java.util.Set.of(1L), candidate)).thenReturn(context());
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> prompt = new AtomicReference<>();
        LlmClient client = text -> {
            prompt.set(text);
            return "{\"unit_tests\":[{\"case_id\":1,\"test_class_name\":\"GeneratedServiceTest\",\"test_method_name\":\"case1\",\"package_name\":\"demo\",\"generation_type\":\"NEW_TEST\",\"source_code\":\"package demo; class GeneratedServiceTest { @org.junit.jupiter.api.Test void case1() {} }\"}]}";
        };
        AIAgentService agent = new AIAgentService(contexts, new PromptManager(mapper), client,
                new GenerationResponseParser(mapper, Validation.buildDefaultValidatorFactory().getValidator()),
                new AiContextLogService(mapper, root.resolve("logs").toString()), new ProjectJavaVersionDetector(projects));

        var result = agent.generateSnapshotUnitTests(1L, java.util.Set.of(1L), candidate);

        assertThat(result.unitTests()).hasSize(1);
        assertThat(result.unitTests().get(0).sourceCode()).startsWith("// GREYTEST_SOURCE_V1 ");
        assertThat(prompt.get()).contains("declares Java 17", "JUnit 5 only").doesNotContain("declares Java 8", "JUnit 4 only");
    }

    @Test
    void addsDetectedJavaVersionToUnitTestPrompt(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationContextBuilder contextBuilder = mock(GenerationContextBuilder.class);
        when(contextBuilder.buildUnitTestContext(1L)).thenReturn(context());
        ProjectJavaVersionDetector detector = mock(ProjectJavaVersionDetector.class);
        when(detector.detect(eq(1L), anyList())).thenReturn(Optional.of(new JavaVersionInfo("8", "pom.xml")));
        when(detector.detectTestFramework(eq(1L), anyList()))
                .thenReturn(Optional.of(new TestFrameworkInfo(TestFramework.JUNIT4, "pom.xml")));
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        LlmClient client = prompt -> {
            capturedPrompt.set(prompt);
            return "{\"unit_tests\":[{\"case_id\":1,\"test_class_name\":\"GeneratedServiceTest\",\"test_method_name\":\"case1\",\"package_name\":\"demo\",\"generation_type\":\"NEW_TEST\",\"source_code\":\"package demo; import org.junit.Test; import static org.junit.Assert.assertEquals; public class GeneratedServiceTest { @Test public void case1() { assertEquals(1, 1); } }\"}]}";
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

    private UnitTestContextDto context() {
        MethodContextDto method = new MethodContextDto(1L, "demo.GeneratedService", "send", "void",
                List.of(), List.of(), "PUBLIC", "public void send() {}", 1, 1, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(1L, "demo", "GeneratedService",
                "demo.GeneratedService", "SERVICE", "src/main/java/demo/GeneratedService.java",
                "package demo; class GeneratedService { public void send() {} }", List.of(), List.of(method));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(1L, 1L, "BR-001", "rule", null,
                "AI", "APPROVED", false, "STMT-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(1L, 1L, List.of(1L), "TP-001",
                "plan", "plan", "HAPPY_PATH", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(1L, 1L, "TC-001", "HAPPY_PATH",
                "case", "setup", java.util.Map.of(), "returns", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(
                new ProjectContextDto(1L, "demo", "CASE_APPROVED"),
                new AnalysisSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null),
                List.of(javaClass), List.of(rule), List.of(plan), List.of(testCase), List.of(), List.of(), List.of());
    }
}
