package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.agent.GenerationContextDtos.AnalysisSummaryDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AgentGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final PromptManager promptManager = new PromptManager(objectMapper);
    private final GenerationResponseParser parser = new GenerationResponseParser(objectMapper, validator);
    private final MockLlmClient mockLlmClient = new MockLlmClient();

    @Test
    void promptAndMockClientRunWithoutApiKey() {
        String prompt = promptManager.render("business-rule", Map.of("context_json", Map.of("project", "demo")));

        BusinessRuleResponseDto response = parser.parse(
                mockLlmClient.complete(prompt),
                BusinessRuleResponseDto.class);

        assertThat(prompt).contains("# Prompt: business-rule", "\"project\" : \"demo\"", "1-5 independent rules");
        assertThat(response.rules()).hasSize(1);
        assertThat(response.rules().get(0).methodId()).isEqualTo(1L);
    }

    @Test
    void mockClientUsesMethodIdFromPromptContext() {
        String prompt = """
                # Prompt: business-rule
                Context:
                {
                  "classes": [
                    {
                      "methods": [
                        { "id": 90983, "classQualifiedName": "demo.AccountService", "methodName": "findAccounts" },
                        { "id": 90984, "classQualifiedName": "demo.AccountService", "methodName": "saveAccount" }
                      ]
                    }
                  ]
                }
                """;

        BusinessRuleResponseDto response = parser.parse(
                mockLlmClient.complete(prompt),
                BusinessRuleResponseDto.class);

        assertThat(response.rules()).extracting("methodId").containsExactly(90983L, 90984L);
    }

    @Test
    void aiAgentServiceUsesGatewayPieces(@TempDir Path tempDir) throws Exception {
        GenerationContextBuilder contextBuilder = mock(GenerationContextBuilder.class);
        when(contextBuilder.buildBusinessRuleGenerationContext(1L)).thenReturn(context());
        AiContextLogService contextLogService = new AiContextLogService(objectMapper, tempDir.toString());
        AIAgentService service = new AIAgentService(contextBuilder, promptManager, mockLlmClient, parser, contextLogService);

        BusinessRuleResponseDto response = service.generateBusinessRules(1L);

        assertThat(response.rules()).extracting("category").containsExactly("VALIDATION");
        try (var files = Files.list(tempDir)) {
            List<Path> logFiles = files.toList();
            Path contextLog = logFiles.stream()
                    .filter(file -> !file.getFileName().toString().contains("-response-"))
                    .findFirst().orElseThrow();
            Path responseLog = logFiles.stream()
                    .filter(file -> file.getFileName().toString().contains("-response-1"))
                    .findFirst().orElseThrow();
            assertThat(Files.readString(contextLog)).contains("context_json", "rendered_prompt", "\"projectId\" : 1");
            assertThat(Files.readString(responseLog)).contains("\"rules\"");
        }
    }

    @Test
    void contextLogDefaultPathUsesProjectRootLog(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("greytest");
        Files.createDirectories(projectRoot.resolve("backend"));
        Files.createDirectories(projectRoot.resolve("frontend"));

        assertThat(AiContextLogService.resolveLogDir("../log", projectRoot.resolve("backend")))
                .isEqualTo(projectRoot.resolve("log"));
        assertThat(AiContextLogService.resolveLogDir("log", projectRoot.resolve("backend")))
                .isEqualTo(projectRoot.resolve("backend").resolve("log"));
        assertThat(AiContextLogService.resolveLogDir("./log", projectRoot.resolve("backend")))
                .isEqualTo(projectRoot.resolve("backend").resolve("log"));
    }

    @Test
    void parserRejectsWrongSchemaClearly() {
        String invalidJson = """
                {
                  "rules": [
                    { "method_id": 1, "category": "VALIDATION" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(invalidJson, BusinessRuleResponseDto.class))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("LLM response khong dung schema");
    }

    @Test
    void parserAcceptsJsonCodeFence() {
        String fencedJson = """
                ```json
                {
                  "rules": [
                    {
                      "method_id": 1,
                      "description": "Input {demo} phai hop le.",
                      "category": "VALIDATION"
                    }
                  ]
                }
                ```
                """;

        BusinessRuleResponseDto response = parser.parse(fencedJson, BusinessRuleResponseDto.class);

        assertThat(response.rules()).hasSize(1);
    }

    @Test
    void parserAcceptsJsonWrappedByText() {
        String wrappedJson = """
                Day la ket qua JSON:
                {
                  "rules": [
                    {
                      "method_id": 1,
                      "description": "Input phai hop le.",
                      "category": "VALIDATION"
                    }
                  ]
                }
                Ket thuc.
                """;

        BusinessRuleResponseDto response = parser.parse(wrappedJson, BusinessRuleResponseDto.class);

        assertThat(response.rules()).hasSize(1);
    }

    private BusinessRuleGenerationContextDto context() {
        AnalysisManifestDto manifest = new AnalysisManifestDto(
                1L,
                "demo",
                "1.1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        AnalysisSummaryDto summary = new AnalysisSummaryDto(
                0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), manifest);
        return new BusinessRuleGenerationContextDto(
                new ProjectContextDto(1L, "demo", "ANALYZED"),
                summary,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
