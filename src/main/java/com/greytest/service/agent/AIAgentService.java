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
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleReviewContextDto;
import com.greytest.dto.agent.GenerationContextDtos.CoverageRefinementContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleReviewResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.dto.diff.MethodDiffItem;
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
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000L;
    private static final String UNIT_TEST_SEMANTIC_ERROR = "Unit Test semantic validation failed: ";

    private final GenerationContextBuilder contextBuilder;
    private final PromptManager promptManager;
    private final LlmClient llmClient;
    private final GenerationResponseParser responseParser;
    private final AiContextLogService contextLogService;
    private final ProjectRepository projectRepository;
    private final UsageQuotaService quotaService;
    private final LlmResponseCache responseCache;
    private final ProjectJavaVersionDetector javaVersionDetector;
    private final LlmStageTokenLimits stageTokenLimits;

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService) {
        this(contextBuilder, promptManager, llmClient, responseParser, contextLogService,
                null, null, null, null, LlmStageTokenLimits.defaults());
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
                projectRepository, quotaService, responseCache, null, LlmStageTokenLimits.defaults());
    }

    public AIAgentService(
            GenerationContextBuilder contextBuilder,
            PromptManager promptManager,
            LlmClient llmClient,
            GenerationResponseParser responseParser,
            AiContextLogService contextLogService,
            ProjectJavaVersionDetector javaVersionDetector) {
        this(contextBuilder, promptManager, llmClient, responseParser, contextLogService,
                null, null, null, javaVersionDetector, LlmStageTokenLimits.defaults());
    }

    /** Constructor Spring dung de tao agent day du voi quota, cache va Java-version detector. */
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
        this(contextBuilder, promptManager, llmClient, responseParser, contextLogService,
                projectRepository, quotaService, responseCache, javaVersionDetector,
                LlmStageTokenLimits.defaults());
    }

    /** Constructor Spring dùng giới hạn output riêng cho từng công đoạn AI. */
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
            ProjectJavaVersionDetector javaVersionDetector,
            LlmStageTokenLimits stageTokenLimits) {
        this.contextBuilder = contextBuilder;
        this.promptManager = promptManager;
        this.llmClient = llmClient;
        this.responseParser = responseParser;
        this.contextLogService = contextLogService;
        this.projectRepository = projectRepository;
        this.quotaService = quotaService;
        this.responseCache = responseCache;
        this.javaVersionDetector = javaVersionDetector;
        this.stageTokenLimits = stageTokenLimits;
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

    /** Kiểm tra oracle đã duyệt trước khi service thay thế dữ liệu cũ; không gọi LLM. */
    public void validateTestCasePrerequisites(Long projectId, Set<Long> planIds) {
        contextBuilder.buildTestCaseContext(projectId, planIds);
    }

    /** Kiểm tra đầy đủ BR/Plan/Case đã duyệt trước khi xóa Unit Test cũ. */
    public void validateUnitTestPrerequisites(Long projectId, Set<Long> caseIds) {
        contextBuilder.buildUnitTestContext(projectId, caseIds);
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

    /** Sinh Unit Test với source đầy đủ từ snapshot ứng viên, giữ nguyên ID các artifact. */
    public UnitTestResponseDto generateSnapshotUnitTests(Long projectId, Set<Long> caseIds, java.nio.file.Path snapshot) {
        return call("unit-test", projectId, contextBuilder.buildSnapshotUnitTestContext(projectId, caseIds, snapshot),
                UnitTestResponseDto.class, null, snapshot);
    }

    public UnitTestResponseDto generateUnitTests(
            Long projectId, Set<Long> caseIds, List<MethodDiffItem> changedMethods) {
        return call("unit-test", projectId,
                contextBuilder.buildUnitTestContext(projectId, caseIds, changedMethods),
                UnitTestResponseDto.class, null);
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
        return call(promptName, projectId, context, responseType, correction, null);
    }

    private <T> T call(String promptName, Long projectId, Object context, Class<T> responseType, String correction,
            java.nio.file.Path snapshot) {
        if ("unit-test".equals(promptName) && context instanceof UnitTestContextDto unitTestContext) {
            GeneratedUnitTestSemanticValidator.validateContext(unitTestContext)
                    .ifPresent(problem -> { throw new LlmResponseException(problem); });
        }
        String prompt = promptManager.render(promptName, Map.of("context_json", context));
        prompt += javaVersionInstruction(promptName, projectId, context, snapshot);
        if (correction != null && !correction.isBlank()) {
            String correctionGuidance = "test-plan".equals(promptName)
                    ? "Regenerate the full plans array and fix ALL mapping errors listed above. "
                            + "For each method, cover every missing id, remove unexpected ids, and preserve valid coverage. "
                            + "Look up each id in approvedBusinessRules and match its methodId and description; "
                            + "do not guess ids from their order or attach an unrelated rule just to satisfy coverage. "
                            + "Keep covered_rule_ids nonempty and unique within each plan, and use their smallest id as rule_id."
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
                    validateSemanticOutput(promptName, projectId, context, parsed, snapshot);
                    GenerationJobContext.log("Da tai su dung ket qua AI da sinh truoc do (100%).");
                    return parsed;
                } catch (LlmResponseException exception) {
                    responseCache.evict(prompt);
                    log.warn("Cached LLM response invalid for {}: {}", promptName, exception.getMessage());
                }
            }
        }

        Long userId = GenerationJobContext.actorUserId();
        if (userId == null && projectId != null && projectRepository != null) {
            userId = projectRepository.findById(projectId)
                    .map(project -> project.getOwnerUserId())
                    .orElse(null);
        }
        if (userId != null && quotaService != null) {
            quotaService.checkQuotaAvailable(userId);
        }

        String attemptPrompt = prompt;
        for (int attempt = 1; attempt <= EXHAUSTED_POOL_MAX_ATTEMPTS; attempt++) {
            try {
                String response = llmClient.complete(attemptPrompt, stageTokenLimits.optionsFor(promptName));
                contextLogService.writeResponse(promptName, attempt, response);
                T parsed = responseParser.parse(response, responseType);
                validateSemanticOutput(promptName, projectId, context, parsed, snapshot);

                // Chỉ tính và trừ quota khi đã hoàn tất xử lý và validate thành công
                int units = calculateUnits(context);
                if (userId != null && quotaService != null) {
                    quotaService.consumeLlmCall(userId, projectId, units,
                            Map.of("prompt", promptName, "attempt", attempt, "units", units));
                }

                if (responseCache != null) {
                    responseCache.putAfterSuccessfulTransaction(prompt, response);
                }
                return parsed;
            } catch (LlmResponseException exception) {
                log.warn("LLM response invalid for {} attempt {}: {}", promptName, attempt, exception.getMessage());
                if (!exception.isRetryable() || exception.getRetryAfterMillis() > MAX_RETRY_DELAY_MILLIS
                        || attempt >= maxAttemptsFor(exception)) {
                    throw exception;
                }
                if (isUnitTestSemanticError(exception)) {
                    GenerationJobContext.log("Unit Test chua dat kiem tra noi dung. Dang thu lai lan "
                            + (attempt + 1) + "/" + maxAttemptsFor(exception) + ".");
                    attemptPrompt = prompt + "\n\n# Unit Test semantic correction\n"
                            + exception.getMessage().substring(UNIT_TEST_SEMANTIC_ERROR.length())
                            + "\nRegenerate the complete unit_tests array. Fix only the reported technical errors; "
                            + "preserve approved test inputs, preconditions, expected results and business assertions. "
                            + "Production source is authoritative only for package, API, signature, type and wiring compatibility. "
                            + "Do not change an assertion or input to make a test pass on current production behavior. Return strict JSON only.";
                    continue;
                } else if (isMalformedJson(exception)) {
                    attemptPrompt = prompt + "\n\n# JSON correction\nThe previous response was not valid JSON. Regenerate the complete response as strict RFC 8259 JSON. Check every quote, comma, object and array brace; do not add or omit braces, and do not use markdown fences or Java literals.";
                }
                waitBeforeRetry(promptName, attempt, exception);
            }
        }
        throw new LlmResponseException("Khong the parse LLM response.");
    }

    private <T> void validateSemanticOutput(String promptName, Long projectId, Object context, T parsed,
            java.nio.file.Path snapshot) {
        if (!"unit-test".equals(promptName)
                || !(context instanceof UnitTestContextDto unitTestContext)
                || !(parsed instanceof UnitTestResponseDto unitTestResponse)) return;
        List<String> sourcePaths = unitTestContext.classes().stream()
                .map(com.greytest.dto.agent.GenerationContextDtos.ClassContextDto::filePath)
                .toList();
        ProjectJavaVersionDetector.TestFramework framework = javaVersionDetector == null
                ? null : (snapshot == null ? javaVersionDetector.detectTestFramework(projectId, sourcePaths)
                        : javaVersionDetector.detectSnapshotTestFramework(snapshot, sourcePaths))
                        .map(ProjectJavaVersionDetector.TestFrameworkInfo::framework).orElse(null);
        GeneratedUnitTestSemanticValidator.validate(unitTestContext, unitTestResponse, framework)
                .ifPresent(problem -> {
                    throw new LlmResponseException(UNIT_TEST_SEMANTIC_ERROR + problem, true);
                });
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
        long exponentialDelay = Math.min(1_000L << Math.max(attempt - 1, 0), MAX_RETRY_DELAY_MILLIS);
        long minimumDelay = isSharedPoolExhausted(exception)
                ? EXHAUSTED_POOL_DELAY_MILLIS : exponentialDelay;
        long baseDelay = Math.max(Math.max(exception.getRetryAfterMillis(), exponentialDelay), minimumDelay);
        long cappedBaseDelay = Math.min(baseDelay, MAX_RETRY_DELAY_MILLIS - jitter);
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

    private static boolean isUnitTestSemanticError(LlmResponseException exception) {
        return exception.getMessage() != null && exception.getMessage().startsWith(UNIT_TEST_SEMANTIC_ERROR);
    }

    private String javaVersionInstruction(String promptName, Long projectId, Object context, java.nio.file.Path snapshot) {
        if (!"unit-test".equals(promptName) || javaVersionDetector == null) return "";
        StringBuilder instruction = new StringBuilder();
        List<String> sourcePaths = context instanceof UnitTestContextDto unitTestContext
                ? unitTestContext.classes().stream()
                        .map(com.greytest.dto.agent.GenerationContextDtos.ClassContextDto::filePath)
                        .toList()
                : List.of();
        try {
            instruction.append((snapshot == null ? javaVersionDetector.detect(projectId, sourcePaths)
                    : javaVersionDetector.detectSnapshot(snapshot, sourcePaths))
                    .map(info -> "\n\n# Java compatibility detected\nBuild file `" + info.buildFile()
                            + "` declares Java " + info.version() + ". Use only syntax and APIs available in Java "
                            + info.version() + ".")
                    .orElse("\n\n# Java compatibility detected\nNo Java version was found in the build files. Use Java 8-compatible syntax and APIs."));
        } catch (RuntimeException exception) {
            log.warn("Cannot detect Java version for project {}", projectId, exception);
            instruction.append("\n\n# Java compatibility detected\nJava version detection failed. Use Java 8-compatible syntax and APIs.");
        }
        try {
            (snapshot == null ? javaVersionDetector.detectTestFramework(projectId, sourcePaths)
                    : javaVersionDetector.detectSnapshotTestFramework(snapshot, sourcePaths)).ifPresent(info -> {
                instruction.append("\n\n# Test framework detected\nBuild file `")
                        .append(info.buildFile()).append("` requires ");
                if (info.framework() == ProjectJavaVersionDetector.TestFramework.JUNIT4) {
                    instruction.append("JUnit 4 only. Use org.junit.Test, org.junit.Before and org.junit.Assert only; "
                            + "never import org.junit.jupiter. For exceptions use try/fail/catch (portable to JUnit 4.12). "
                            + "JUnit 4 assertion messages are the first argument.");
                } else {
                    instruction.append("JUnit 5 only. Use org.junit.jupiter.api consistently; never mix org.junit.* lifecycle/test annotations.");
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Cannot detect test framework for project {}", projectId, exception);
        }
        return instruction.toString();
    }

    String languageInstruction() {
        if (Locale.ENGLISH.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage())) {
            return "# Output language\nReturn every natural-language field in English only. Do not mix Vietnamese prose into descriptions. Never use Chinese characters (such as 抛出, 异常, 返回). Keep JSON keys, enum values, identifiers, code and file paths unchanged.";
        }
        return "# Ngon ngu output\n"
                + "Tra loi tat ca cac truong ngon ngu tu nhien bang tieng Viet only. "
                + "TUYET DOI KHONG su dung bat ky chu Han / chu tieng Trung nao (vi du: KHONG dung 抛出, 异常, 返回, 如果, ...). "
                + "Dung tu ngu tieng Viet chuan (vi du: 'nem ra', 'nem ngoai le', 'tra ve', 'neu', 'la null') hoac giu nguyen tu khoa Java bang tieng Anh (throw, return, exception). "
                + "Khong tron cau tieng Anh vao description. "
                + "Giu nguyen cac thuat ngu IT pho bien bang tieng Anh (vi du: API, endpoint, controller, service, repository, method, class, source code, Test Plan, Test Case, Unit Test, mock, assertion, branch, coverage); khong dich guong ep. "
                + "Khong dich JSON key, enum value, identifier, code va file path.";
    }

    int calculateUnits(Object context) {
        if (context instanceof UnitTestContextDto unitTestContext) {
            int count = unitTestContext.approvedTestCases() != null
                    ? unitTestContext.approvedTestCases().size() : 0;
            return Math.max((count + 1) / 2, 1);
        }
        if (context instanceof TestCaseContextDto testCaseContext) {
            int count = testCaseContext.approvedTestPlans() != null
                    ? testCaseContext.approvedTestPlans().size() : 0;
            return Math.max((count + 1) / 2, 1);
        }
        if (context instanceof TestPlanContextDto testPlanContext) {
            int count = testPlanContext.approvedBusinessRules() != null
                    ? testPlanContext.approvedBusinessRules().size() : 0;
            return Math.max((count + 1) / 2, 1);
        }
        if (context instanceof BusinessRuleGenerationContextDto ruleContext) {
            int count = ruleContext.classes() != null
                    ? ruleContext.classes().stream()
                            .mapToInt(c -> c.methods() != null ? c.methods().size() : 0)
                            .sum()
                    : 0;
            return Math.max((count + 2) / 3, 1);
        }
        if (context instanceof BusinessRuleReviewContextDto reviewContext) {
            int count = reviewContext.businessRules() != null
                    ? reviewContext.businessRules().size() : 0;
            return Math.max((count + 1) / 2, 1);
        }
        if (context instanceof CoverageRefinementContextDto refinementContext) {
            int count = refinementContext.coverageGaps() != null
                    ? refinementContext.coverageGaps().size() : 0;
            return Math.max((count + 1) / 2, 1);
        }
        return 1;
    }
}
