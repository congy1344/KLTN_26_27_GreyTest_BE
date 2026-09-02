package com.greytest.service.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleReviewResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.UsageQuotaService;

import lombok.extern.slf4j.Slf4j;

/**
 * Service dieu phoi context -> prompt -> LLM -> parser cho cac buoc AI generation.
 */
@Slf4j
@Service
public class AIAgentService {

    private static final int DEFAULT_MAX_ATTEMPTS = 2;
    private static final int MALFORMED_JSON_MAX_ATTEMPTS = 3;
    private static final int EXHAUSTED_POOL_MAX_ATTEMPTS = 3;
    private static final long EXHAUSTED_POOL_DELAY_MILLIS = 30_000L;

    private final GenerationContextBuilder contextBuilder;
    private final PromptManager promptManager;
    private final LlmClient llmClient;
    private final GenerationResponseParser responseParser;
    private final AiContextLogService contextLogService;
    private final ProjectRepository projectRepository;
    private final UsageQuotaService quotaService;
    private final LlmResponseCache responseCache;
    private final ProjectJavaVersionDetector javaVersionDetector;

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService) {
        this(contextBuilder, promptManager, llmClient, responseParser, contextLogService,
                null, null, null, null);
    }

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService,
            ProjectRepository projectRepository,
            UsageQuotaService quotaService,
            LlmResponseCache responseCache) {
        this(contextBuilder, promptManager, llmClient, responseParser, contextLogService,
                projectRepository, quotaService, responseCache, null);
    }

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService,
            ProjectJavaVersionDetector javaVersionDetector) {
        this(contextBuilder, promptManager, llmClient, responseParser, contextLogService,
                null, null, null, javaVersionDetector);
    }

    /** Constructor Spring dung de tao agent day du voi quota, cache va Java-version detector. */
    @org.springframework.beans.factory.annotation.Autowired
    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService,
            ProjectRepository projectRepository,
            UsageQuotaService quotaService,
            LlmResponseCache responseCache,
            ProjectJavaVersionDetector javaVersionDetector) {
        this.contextBuilder = contextBuilder;
        this.promptManager = promptManager;
        this.llmClient = llmClient;
        this.responseParser = responseParser;
        this.contextLogService = contextLogService;
        this.projectRepository = projectRepository;
        this.quotaService = quotaService;
        this.responseCache = responseCache;
        this.javaVersionDetector = javaVersionDetector;
    }

    public BusinessRuleResponseDto generateBusinessRules(Long projectId) {
        return call(projectId, "business-rule", contextBuilder.buildBusinessRuleGenerationContext(projectId),
                BusinessRuleResponseDto.class);
    }

    public BusinessRuleResponseDto generateBusinessRules(Long projectId, Set<Long> methodIds) {
        return call(projectId, "business-rule", contextBuilder.buildBusinessRuleGenerationContext(projectId, methodIds),
                BusinessRuleResponseDto.class);
    }

    public BusinessRuleResponseDto generateBusinessRules(Long projectId, Set<Long> methodIds, String correction) {
        return call("business-rule", projectId, contextBuilder.buildBusinessRuleGenerationContext(projectId, methodIds),
                BusinessRuleResponseDto.class, correction);
    }

    public BusinessRuleReviewResponseDto reviewBusinessRules(Long projectId) {
        return call(projectId, "business-rule-review", contextBuilder.buildBusinessRuleReviewContext(projectId),
                BusinessRuleReviewResponseDto.class);
    }

    public BusinessRuleReviewResponseDto reviewBusinessRules(Long projectId, Set<Long> ruleIds) {
        return call(projectId, "business-rule-review", contextBuilder.buildBusinessRuleReviewContext(projectId, ruleIds),
                BusinessRuleReviewResponseDto.class);
    }

    public TestPlanResponseDto generateTestPlan(Long projectId) {
        return call(projectId, "test-plan", contextBuilder.buildTestPlanContext(projectId), TestPlanResponseDto.class);
    }

    public TestPlanResponseDto generateTestPlan(Long projectId, Set<Long> ruleIds) {
        return call(projectId, "test-plan", contextBuilder.buildTestPlanContext(projectId, ruleIds), TestPlanResponseDto.class);
    }

    public TestPlanResponseDto generateTestPlan(Long projectId, Set<Long> ruleIds, String correction) {
        return call("test-plan", projectId, contextBuilder.buildTestPlanContext(projectId, ruleIds),
                TestPlanResponseDto.class, correction);
    }

    public TestCaseResponseDto generateTestCases(Long projectId) {
        return call(projectId, "test-case", contextBuilder.buildTestCaseContext(projectId), TestCaseResponseDto.class);
    }

    public TestCaseResponseDto generateTestCases(Long projectId, Set<Long> planIds) {
        return call(projectId, "test-case", contextBuilder.buildTestCaseContext(projectId, planIds), TestCaseResponseDto.class);
    }

    public UnitTestResponseDto generateUnitTests(Long projectId) {
        return call("unit-test", projectId, contextBuilder.buildUnitTestContext(projectId), UnitTestResponseDto.class, null);
    }

    public TestCaseResponseDto generateCoverageRefinement(Long projectId, int round, List<CoverageGapDto> gaps) {
        return call(projectId, "coverage-refinement",
                contextBuilder.buildCoverageRefinementContext(projectId, round, gaps),
                TestCaseResponseDto.class);
    }

    public UnitTestResponseDto generateUnitTests(Long projectId, Set<Long> caseIds) {
        return call("unit-test", projectId, contextBuilder.buildUnitTestContext(projectId, caseIds), UnitTestResponseDto.class, null);
    }

    private <T> T call(Long projectId, String promptName, Object context, Class<T> responseType) {
        return call(promptName, projectId, context, responseType, null);
    }

    private <T> T call(String promptName, Object context, Class<T> responseType) {
        return call(promptName, null, context, responseType, null);
    }

    private <T> T call(String promptName, Object context, Class<T> responseType, String correction) {
        return call(promptName, null, context, responseType, correction);
    }

    private <T> T call(String promptName, Long projectId, Object context, Class<T> responseType, String correction) {
        String prompt = promptManager.render(promptName, Map.of("context_json", context));
        prompt += javaVersionInstruction(promptName, projectId);
        if (correction != null && !correction.isBlank()) {
            String correctionGuidance = "test-plan".equals(promptName)
                    ? "Regenerate the full plans array. Keep method_id and rule_id valid, and make the union of covered_rule_ids cover every approved Business Rule in this batch."
                    : "Regenerate the full response. Return exactly one separate rule for every decision id, including nested decisions; never merge a missing decision into another rule.";
            prompt += "\n\n# Semantic correction\nPrevious response was invalid: " + correction
                    + "\n" + correctionGuidance;
        }
        prompt += "\n\n" + languageInstruction();
        contextLogService.write(promptName, context, prompt);

        if (responseCache != null) {
            Optional<String> cachedResponse = responseCache.get(prompt);
            if (cachedResponse.isPresent()) {
                try {
                    T parsed = responseParser.parse(cachedResponse.get(), responseType);
                    GenerationJobContext.log("Da tai su dung ket qua AI da sinh truoc do (100%).");
                    return parsed;
                } catch (LlmResponseException exception) {
                    responseCache.evict(prompt);
                    log.warn("Cached LLM response invalid for {}: {}", promptName, exception.getMessage());
                }
            }
        }

        String attemptPrompt = prompt;
        for (int attempt = 1; attempt <= EXHAUSTED_POOL_MAX_ATTEMPTS; attempt++) {
            try {
                Long userId = GenerationJobContext.actorUserId();
                if (userId == null && projectId != null && projectRepository != null) {
                    userId = projectRepository.findById(projectId)
                            .map(project -> project.getOwnerUserId())
                            .orElse(null);
                }
                if (userId != null && quotaService != null) {
                    quotaService.consumeLlmCall(userId, projectId,
                            Map.of("prompt", promptName, "attempt", attempt));
                }

                String response = llmClient.complete(attemptPrompt);
                contextLogService.writeResponse(promptName, attempt, response);
                T parsed = responseParser.parse(response, responseType);
                if (responseCache != null) {
                    responseCache.putAfterSuccessfulTransaction(prompt, response);
                }
                return parsed;
            } catch (LlmResponseException exception) {
                log.warn("LLM response invalid for {} attempt {}: {}", promptName, attempt, exception.getMessage());
                if (!exception.isRetryable() || attempt >= maxAttemptsFor(exception)) {
                    throw exception;
                }
                if (isMalformedJson(exception)) {
                    attemptPrompt = prompt + "\n\n# JSON correction\nThe previous response was not valid JSON. Regenerate the complete response as strict RFC 8259 JSON. Check every quote, comma, object and array brace; do not add or omit braces, and do not use markdown fences or Java literals.";
                }
                waitBeforeRetry(promptName, attempt, exception);
            }
        }
        throw new LlmResponseException("Khong the parse LLM response.");
    }

    private void waitBeforeRetry(String promptName, int attempt, LlmResponseException exception) {
        long jitter = ThreadLocalRandom.current().nextLong(250L, 751L);
        long delay = retryDelayMillis(attempt, exception, jitter);
        log.info("LLM {} is temporarily limited; retrying in {} ms", promptName, delay);
        String reason = isSharedPoolExhausted(exception)
                ? "Provider AI đang tạm hết tài nguyên dùng chung."
                : "Lần gọi AI tạm thời thất bại.";
        GenerationJobContext.log(reason + " Hệ thống sẽ tự thử lại sau khoảng "
                + Math.max(1, Math.round(delay / 1_000.0)) + " giây.");
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmResponseException("Bi gian doan trong khi cho thu lai LLM.", interrupted);
        }
    }

    static int maxAttemptsFor(LlmResponseException exception) {
        if (isSharedPoolExhausted(exception)) return EXHAUSTED_POOL_MAX_ATTEMPTS;
        return isMalformedJson(exception) ? MALFORMED_JSON_MAX_ATTEMPTS : DEFAULT_MAX_ATTEMPTS;
    }

    static long retryDelayMillis(int attempt, LlmResponseException exception, long jitter) {
        long exponentialDelay = Math.min(1_000L << Math.max(attempt - 1, 0), 60_000L);
        long minimumDelay = isSharedPoolExhausted(exception)
                ? EXHAUSTED_POOL_DELAY_MILLIS : exponentialDelay;
        long baseDelay = Math.max(Math.max(exception.getRetryAfterMillis(), exponentialDelay), minimumDelay);
        long cappedBaseDelay = Math.min(baseDelay, 60_000L - jitter);
        return cappedBaseDelay + jitter;
    }

    private static boolean isSharedPoolExhausted(LlmResponseException exception) {
        return exception.getMessage() != null
                && exception.getMessage().toLowerCase(Locale.ROOT)
                        .contains("all available accounts exhausted");
    }

    private static boolean isMalformedJson(LlmResponseException exception) {
        return exception.getMessage() != null
                && exception.getMessage().toLowerCase(Locale.ROOT)
                        .contains("khong phai json hop le");
    }

    private String javaVersionInstruction(String promptName, Long projectId) {
        if (!"unit-test".equals(promptName) || javaVersionDetector == null) return "";
        try {
            return javaVersionDetector.detect(projectId)
                    .map(info -> "\n\n# Java compatibility detected\nBuild file `" + info.buildFile()
                            + "` declares Java " + info.version() + ". Use only syntax and APIs available in Java "
                            + info.version() + ".")
                    .orElse("\n\n# Java compatibility detected\nNo Java version was found in the build files. Use Java 8-compatible syntax and APIs.");
        } catch (RuntimeException exception) {
            log.warn("Cannot detect Java version for project {}", projectId, exception);
            return "\n\n# Java compatibility detected\nJava version detection failed. Use Java 8-compatible syntax and APIs.";
        }
    }

    String languageInstruction() {
        if (Locale.ENGLISH.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage())) {
            return "# Output language\nReturn every natural-language field in English only. Do not mix Vietnamese prose into descriptions. Keep JSON keys, enum values, identifiers, code and file paths unchanged.";
        }
        return "# Ngon ngu output\nTra loi tat ca cac truong ngon ngu tu nhien bang tieng Viet only. Khong tron cau tieng Anh vao description. Giu nguyen cac thuat ngu IT pho bien bang tieng Anh (vi du: API, endpoint, controller, service, repository, method, class, source code, Test Plan, Test Case, Unit Test, mock, assertion, branch, coverage); khong dich guong ep. Khong dich JSON key, enum value, identifier, code va file path.";
    }
}