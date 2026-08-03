package com.greytest.service.agent;

import java.util.Map;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.context.i18n.LocaleContextHolder;

import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleReviewResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.dto.CoverageGapDto;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Service dieu phoi context -> prompt -> LLM -> parser cho cac buoc AI generation.
 */
@Slf4j
@Service
public class AIAgentService {

    private static final int MAX_ATTEMPTS = 2;

    private final GenerationContextBuilder contextBuilder;
    private final PromptManager promptManager;
    private final LlmClient llmClient;
    private final GenerationResponseParser responseParser;
    private final AiContextLogService contextLogService;

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService) {
        this.contextBuilder = contextBuilder;
        this.promptManager = promptManager;
        this.llmClient = llmClient;
        this.responseParser = responseParser;
        this.contextLogService = contextLogService;
    }

    public BusinessRuleResponseDto generateBusinessRules(Long projectId) {
        return call("business-rule", contextBuilder.buildBusinessRuleGenerationContext(projectId),
                BusinessRuleResponseDto.class);
    }

    public BusinessRuleResponseDto generateBusinessRules(Long projectId, Set<Long> methodIds) {
        return call("business-rule", contextBuilder.buildBusinessRuleGenerationContext(projectId, methodIds),
                BusinessRuleResponseDto.class);
    }

    public BusinessRuleReviewResponseDto reviewBusinessRules(Long projectId) {
        return call("business-rule-review", contextBuilder.buildBusinessRuleReviewContext(projectId),
                BusinessRuleReviewResponseDto.class);
    }

    public TestPlanResponseDto generateTestPlan(Long projectId) {
        return call("test-plan", contextBuilder.buildTestPlanContext(projectId), TestPlanResponseDto.class);
    }

    public TestPlanResponseDto generateTestPlan(Long projectId, Set<Long> ruleIds) {
        return call("test-plan", contextBuilder.buildTestPlanContext(projectId, ruleIds), TestPlanResponseDto.class);
    }

    public TestCaseResponseDto generateTestCases(Long projectId) {
        return call("test-case", contextBuilder.buildTestCaseContext(projectId), TestCaseResponseDto.class);
    }

    public TestCaseResponseDto generateTestCases(Long projectId, Set<Long> planIds) {
        return call("test-case", contextBuilder.buildTestCaseContext(projectId, planIds), TestCaseResponseDto.class);
    }

    public UnitTestResponseDto generateUnitTests(Long projectId) {
        return call("unit-test", contextBuilder.buildUnitTestContext(projectId), UnitTestResponseDto.class);
    }

    public TestCaseResponseDto generateCoverageRefinement(
            Long projectId, int round, List<CoverageGapDto> gaps) {
        return call("coverage-refinement",
                contextBuilder.buildCoverageRefinementContext(projectId, round, gaps),
                TestCaseResponseDto.class);
    }

    public UnitTestResponseDto generateUnitTests(Long projectId, Set<Long> caseIds) {
        return call("unit-test", contextBuilder.buildUnitTestContext(projectId, caseIds), UnitTestResponseDto.class);
    }

    private <T> T call(String promptName, Object context, Class<T> responseType) {
        String prompt = promptManager.render(promptName, Map.of("context_json", context))
                + "\n\n" + languageInstruction();
        contextLogService.write(promptName, context, prompt);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = llmClient.complete(prompt);
                contextLogService.writeResponse(promptName, attempt, response);
                return responseParser.parse(response, responseType);
            } catch (LlmResponseException exception) {
                log.warn("LLM response invalid for {} attempt {}: {}", promptName, attempt, exception.getMessage());
                if (!exception.isRetryable() || attempt == MAX_ATTEMPTS) throw exception;
            }
        }
        throw new LlmResponseException("Khong the parse LLM response.");
    }

    String languageInstruction() {
        if (Locale.ENGLISH.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage())) {
            return "# Output language\nReturn every natural-language field in English. Keep JSON keys, enum values, identifiers, code and file paths unchanged.";
        }
        return "# Ngon ngu output\nTra loi cac truong ngon ngu tu nhien bang tieng Viet. Giu nguyen cac thuat ngu IT pho bien bang tieng Anh (vi du: API, endpoint, controller, service, repository, method, class, source code, Test Plan, Test Case, Unit Test, mock, assertion, branch, coverage); khong dich guong ep. Khong dich JSON key, enum value, identifier, code va file path.";
    }
}
