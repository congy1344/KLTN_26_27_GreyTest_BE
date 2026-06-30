package com.greytest.service.agent;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleReviewResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

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

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser) {
        this.contextBuilder = contextBuilder;
        this.promptManager = promptManager;
        this.llmClient = llmClient;
        this.responseParser = responseParser;
    }

    public BusinessRuleResponseDto generateBusinessRules(Long projectId) {
        return call("business-rule", contextBuilder.buildBusinessRuleGenerationContext(projectId),
                BusinessRuleResponseDto.class);
    }

    public BusinessRuleReviewResponseDto reviewBusinessRules(Long projectId) {
        return call("business-rule-review", contextBuilder.buildBusinessRuleReviewContext(projectId),
                BusinessRuleReviewResponseDto.class);
    }

    public TestPlanResponseDto generateTestPlan(Long projectId) {
        return call("test-plan", contextBuilder.buildTestPlanContext(projectId), TestPlanResponseDto.class);
    }

    public TestCaseResponseDto generateTestCases(Long projectId) {
        return call("test-case", contextBuilder.buildTestCaseContext(projectId), TestCaseResponseDto.class);
    }

    public UnitTestResponseDto generateUnitTests(Long projectId) {
        return call("unit-test", contextBuilder.buildUnitTestContext(projectId), UnitTestResponseDto.class);
    }

    private <T> T call(String promptName, Object context, Class<T> responseType) {
        String prompt = promptManager.render(promptName, Map.of("context_json", context));
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return responseParser.parse(llmClient.complete(prompt), responseType);
            } catch (LlmResponseException exception) {
                log.warn("LLM response invalid for {} attempt {}: {}", promptName, attempt, exception.getMessage());
                if (attempt == MAX_ATTEMPTS) throw exception;
            }
        }
        throw new LlmResponseException("Khong the parse LLM response.");
    }
}
