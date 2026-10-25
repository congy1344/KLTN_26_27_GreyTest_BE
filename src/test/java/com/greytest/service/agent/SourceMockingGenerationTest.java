package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

import jakarta.validation.Validation;

class SourceMockingGenerationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationContextBuilder contexts = mock(GenerationContextBuilder.class);
    private final LlmClient client = mock(LlmClient.class);
    private static final String METHOD = "public Object check(String name) { String hash = encoder.encode(name); return repository.save(hash); }";
    private static final String SOURCE = "package demo; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; "
            + "class Service { private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(); Repository repository; " + METHOD + " }";

    @ParameterizedTest
    @ValueSource(strings = {"encoder.encode", "nested"})
    void rejectsUnmockableSetupAtTestCaseStageAndRetries(String mockKey, @TempDir Path log) throws Exception {
        var context = caseContext(SOURCE);
        when(contexts.buildTestCaseContext(1L, Set.of(30L))).thenReturn(context);
        var invalid = cases(mockKey.equals("nested") ? Map.of("encoder", Map.of("encode", "encoded-password"))
                : Map.of(mockKey, "encoded-password"));
        var valid = cases(Map.of("repository.save", "saved user"));
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(json(invalid), json(valid));

        assertCaseOutcomesPreserved(agent(log).generateTestCases(1L, Set.of(30L)), valid);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("Test Case semantic correction", "encoder", "final", "30");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "class Service { private final Hasher encoder; Service(Hasher encoder) { this.encoder = encoder; } }",
            "class Service { @org.springframework.beans.factory.annotation.Autowired Hasher encoder; }",
            "class Service { Hasher encoder = new Hasher(); }"})
    void preservesMocksThatMockitoCanInject(String source, @TempDir Path log) throws Exception {
        when(contexts.buildTestCaseContext(1L, Set.of(30L))).thenReturn(caseContext(source));
        var valid = cases(Map.of("encoder.encode", "encoded-password"));
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(json(valid));
        assertCaseOutcomesPreserved(agent(log).generateTestCases(1L, Set.of(30L)), valid);
        verify(client).complete(anyString(), any(LlmRequestOptions.class));
    }

    @Test
    void failsAfterBoundedTestCaseRetryInsteadOfPersistingBadMocks(@TempDir Path log) throws Exception {
        when(contexts.buildTestCaseContext(1L, Set.of(30L))).thenReturn(caseContext(SOURCE));
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(json(cases(Map.of("encoder.encode", "encoded-password"))));
        assertThatThrownBy(() -> agent(log).generateTestCases(1L, Set.of(30L)))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("encoder");
        verify(client, times(2)).complete(anyString(), any(LlmRequestOptions.class));
    }

    @Test
    void reconsidersMockSetupErrorWithoutChangingBusinessExpectation(@TempDir Path log) throws Exception {
        var context = unitContext();
        when(contexts.buildUnitTestContext(1L, Set.of(40L))).thenReturn(context);
        var expected = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", "demo", "NEW_TEST", """
                package demo;
                @org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
                class ServiceTest {
                    @org.mockito.Mock Repository repository;
                    @org.mockito.InjectMocks Service service;
                    @org.junit.jupiter.api.Test void testCase() {
                        service.check("password");
                        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
                        org.mockito.Mockito.verify(repository).save(hash.capture());
                        org.junit.jupiter.api.Assertions.assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("password", hash.getValue()));
                    }
                }
                """)));
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(error(40L, "UNSUPPORTED_BEHAVIOR"), json(expected));
        assertThat(agent(log).generateUnitTests(1L, Set.of(40L))).isEqualTo(expected);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("Mock setup correction", "real", "expected result", "Encoded password is saved");
        assertThat(context.approvedTestCases().get(0).testData()).containsKey("mocks");
        assertThat(context.approvedTestCases().get(0).status()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_SOURCE", "UNSUPPORTED_BEHAVIOR"})
    void stillStopsForMissingSourceOrAnUnrelatedCase(String reason, @TempDir Path log) throws Exception {
        when(contexts.buildUnitTestContext(1L, Set.of(40L))).thenReturn(unitContext());
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(error(reason.equals("MISSING_SOURCE") ? 40L : 99L, reason));
        assertThatThrownBy(() -> agent(log).generateUnitTests(1L, Set.of(40L)))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining(reason);
        verify(client).complete(anyString(), any(LlmRequestOptions.class));
    }

    @Test
    void mockSetupRecoveryIsBounded(@TempDir Path log) throws Exception {
        when(contexts.buildUnitTestContext(1L, Set.of(40L))).thenReturn(unitContext());
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(error(40L, "UNSUPPORTED_BEHAVIOR"));
        assertThatThrownBy(() -> agent(log).generateUnitTests(1L, Set.of(40L))).isInstanceOf(LlmResponseException.class);
        verify(client, times(2)).complete(anyString(), any(LlmRequestOptions.class));
    }

    private AIAgentService agent(Path log) {
        return new AIAgentService(contexts, new PromptManager(mapper), client,
                new GenerationResponseParser(mapper, Validation.buildDefaultValidatorFactory().getValidator()), new AiContextLogService(mapper, log.toString()));
    }

    @Test
    void checksNestedAndModernServiceDeclarations() {
        var base = caseContext(SOURCE);
        var previous = base.classes().get(0);
        var nested = new ClassContextDto(previous.id(), "demo", "Service", "demo.Outer.Service", "SERVICE", previous.filePath(),
                "package demo; class Outer { static class Service { private static final Hasher encoder = new Hasher(); } }", List.of(), previous.methods());
        var nestedContext = new TestCaseContextDto(null, null, List.of(nested), base.approvedBusinessRules(), base.approvedTestPlans(), List.of());
        assertThat(SourceMockingSupport.validateTestCases(nestedContext, cases(Map.of("encoder.encode", "hash")))).isPresent();
        var modern = caseContext("class Service { private static final Hasher encoder = new Hasher(); String label = \"\"\"\nlabel\n\"\"\"; }");
        assertThat(SourceMockingSupport.validateTestCases(modern, cases(Map.of("encoder.encode", "hash")))).isPresent();
    }

    @Test
    void checksFactoryCreatedLoggerAndScopesEvidenceToThePlansMethod() {
        var logger = caseContext("class Service { private final Logger log = LoggerFactory.getLogger(getClass()); }");
        assertThat(SourceMockingSupport.validateTestCases(logger, cases(Map.of("log.info", "message"))))
                .hasValueSatisfying(message -> assertThat(message).contains("log", "final"));
        var base = caseContext("class Service { // private final Hasher encoder = new Hasher();\n }");
        var unrelated = new ClassContextDto(5L, "other", "OtherService", "other.OtherService", "SERVICE", "other/OtherService.java",
                "package other; class OtherService { private final Hasher encoder = new Hasher(); }", List.of(), List.of());
        var scoped = new TestCaseContextDto(null, null, List.of(base.classes().get(0), unrelated), base.approvedBusinessRules(), base.approvedTestPlans(), List.of());
        assertThat(SourceMockingSupport.validateTestCases(scoped, cases(Map.of("encoder.encode", "hash")))).isEmpty();
    }

    private void assertCaseOutcomesPreserved(TestCaseResponseDto actual, TestCaseResponseDto expected) {
        assertThat(actual.cases()).zipSatisfy(expected.cases(), (item, original) -> {
            assertThat(item.preconditions()).startsWith(original.preconditions());
            assertThat(item).usingRecursiveComparison().ignoringFields("preconditions").isEqualTo(original);
        });
    }

    private TestCaseContextDto caseContext(String source) {
        var method = new MethodContextDto(10L, "demo.Service", "check", "Object", List.of(), List.of(), "public", METHOD, 1, 1, List.of(), List.of(), List.of());
        var service = new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE", "src/main/java/demo/Service.java", source, List.of(), List.of(method));
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-1", "Save encoded password", null, "AI", "APPROVED", false, null);
        var plan = new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-1", "Create user", "Save encoded password", "HAPPY_PATH", "APPROVED", false);
        return new TestCaseContextDto(null, null, List.of(service), List.of(rule), List.of(plan), List.of());
    }

    private UnitTestContextDto unitContext() {
        var base = caseContext(SOURCE);
        var repository = new ClassContextDto(2L, "demo", "Repository", "demo.Repository", "REPOSITORY", "src/main/java/demo/Repository.java",
                "package demo; interface Repository { Object save(String hash); }", List.of(), List.of());
        var testCase = new TestCaseContextItemDto(40L, 30L, "TC-1", "HAPPY_PATH", "Save encoded password", "Mock encoder returns a hash",
                Map.of("input", Map.of("password", "password"), "mocks", Map.of("encoder.encode", "encoded-password")),
                "Encoded password is saved", "HIGH", "BR-1 -> TP-1", "APPROVED", false);
        return new UnitTestContextDto(null, null, List.of(base.classes().get(0), repository), base.approvedBusinessRules(), base.approvedTestPlans(),
                List.of(testCase), List.of(), List.of(), List.of());
    }

    private TestCaseResponseDto cases(Map<String, Object> mocks) {
        return new TestCaseResponseDto(List.of(new GeneratedTestCaseDto(30L, "HAPPY_PATH", "Save encoded password", "User is absent",
                Map.of("input", Map.of("password", "password"), "mocks", mocks), "Encoded password is saved", "HIGH", "BR-1 -> TP-1")));
    }

    private String error(long id, String reason) throws Exception {
        return json(Map.of("generation_error", Map.of("case_id", id, "reason", reason, "details", "Cannot mock internally constructed encoder")));
    }

    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
}
