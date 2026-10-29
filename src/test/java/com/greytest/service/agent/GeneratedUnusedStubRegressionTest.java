package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

import jakarta.validation.Validation;

class GeneratedUnusedStubRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @ParameterizedTest
    @ValueSource(longs = {2467L, 2473L})
    void rejectsUnusedUserGetterBeforeConfirmedMissingRecordReturn(long caseId) throws Exception {
        var fixture = fixture(caseId);
        assertThat(validate(fixture.context(), fixture.test()))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("UnnecessaryStubbingException", "user.getId()", "early return"));
    }

    @ParameterizedTest
    @ValueSource(longs = {2467L, 2473L})
    void acceptsRemovingOnlyUnusedStubAndPreservingNonNullUserAndAssertions(long caseId) throws Exception {
        var fixture = fixture(caseId);
        assertThat(validate(fixture.context(), transform(fixture.test(), this::removeUserStub))).isEmpty();
    }

    @Test
    void doesNotTreatAnActualGetterReadInTheTestAsUnused() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace("// Act",
                "org.junit.jupiter.api.Assertions.assertEquals(\"user-1\", user.getId());\n// Act"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotGuessWhenFixtureEscapesToAnotherHelperOrAct() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace("// Act", "inspect(user);\n// Act"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotInferEarlyReturnFromUnmatchedLookupArgument() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace(
                "when(articleReadService.findById(\"missing-article\"))",
                "when(articleReadService.findById(\"another-id\"))"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotInferNullResultFromChainedOrConsecutiveAnswers() throws Exception {
        var fixture = fixture(2467L);
        for (String answer : List.of("thenReturn(null, new ArticleData())", "thenReturn(null).thenReturn(new ArticleData())")) {
            var test = transform(fixture.test(), code -> code.replace("thenReturn(null)", answer));
            assertThat(validate(fixture.context(), test)).isEmpty();
        }
    }

    @Test
    void doesNotTrustAnEarlierNullStubAfterAnotherStubbingOrHelper() throws Exception {
        var fixture = fixture(2467L);
        for (String override : List.of(
                "when(articleReadService.findById(\"missing-article\")).thenReturn(new ArticleData()).thenReturn(new ArticleData());",
                "org.mockito.Mockito.doReturn(new ArticleData()).when(articleReadService).findById(\"missing-article\");",
                "reconfigureRepository();")) {
            var test = transform(fixture.test(), code -> code.replace("// Act", override + "\n// Act"));
            assertThat(validate(fixture.context(), test)).isEmpty();
        }
    }

    @Test
    void doesNotTrustAConditionalNullStubThatWasNotExecuted() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace(
                "when(articleReadService.findById(\"missing-article\")).thenReturn(null);",
                "Object ignored = false ? when(articleReadService.findById(\"missing-article\")).thenReturn(null) : null;"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotReportAFixtureStubThatWasNotExecuted() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace(
                "org.mockito.Mockito.when(user.getId()).thenReturn(\"user-1\");",
                "Object ignored = false ? org.mockito.Mockito.when(user.getId()).thenReturn(\"user-1\") : null;"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotTrustAnArgumentVariableWhoseValueCanChange() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace("// Arrange", "// Arrange\nString id = \"missing-article\";")
                .replace("findById(\"missing-article\")", "findById(id)")
                .replace("// Act", "id = \"present-article\";\n// Act")
                .replace("findById(\"missing-article\", user)", "findById(id, user)"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotAnalyzeAnotherInvocationThatOnlySharesTheServiceSimpleName() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace("import io.spring.application.ArticleQueryService;",
                "import another.module.ArticleQueryService;"));
        assertThat(validate(fixture.context(), test)).hasValueSatisfying(message -> assertThat(message)
                .contains("Invalid import").doesNotContain("UnnecessaryStubbingException"));
    }

    @Test
    void acceptsDifferentMockFieldNameWhenSutNameMatchesProductionDependencyName() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace("articleReadService", "readService")
                .replace("articleQueryService", "articleReadService"));
        assertThat(validate(fixture.context(), test)).isEmpty();
    }

    @Test
    void doesNotTrustAnActArgumentThatCanOverrideTheNullStub() throws Exception {
        var fixture = fixture(2467L);
        var test = transform(fixture.test(), code -> code.replace("findById(\"missing-article\", user)",
                "findById(\"missing-article\", user, restubLookup())")
                .replace("class ArticleQueryServiceTest {", """
                        class ArticleQueryServiceTest {
                            private int restubLookup() {
                                org.mockito.Mockito.doReturn(new ArticleData()).when(articleReadService).findById("missing-article");
                                return 0;
                            }
                        """));
        var classes = fixture.context().classes().stream().map(c -> {
            if (!c.className().equals("ArticleQueryService")) return c;
            String source = c.sourceCode().replace("findById(String id, User user)", "findById(String id, User user, int ignored)");
            var methods = c.methods().stream().map(m -> {
                if (!m.methodName().equals("findById")) return m;
                var parameters = new ArrayList<>(m.parameters());
                parameters.add(new MethodParamDto("ignored", "int"));
                return new MethodContextDto(m.id(), m.classQualifiedName(), m.methodName(), m.returnType(), parameters,
                        m.throwsList(), m.visibility(), m.sourceCode().replace("findById(String id, User user)",
                        "findById(String id, User user, int ignored)"), m.lineStart(), m.lineEnd(), m.annotations(), m.endpoints(), m.branches());
            }).toList();
            return new ClassContextDto(c.id(), c.packageName(), c.className(), c.qualifiedName(), c.classType(),
                    c.filePath(), source, c.annotations(), methods);
        }).toList();
        var context = fixture.context();
        context = new UnitTestContextDto(context.project(), context.analysis(), classes, context.approvedBusinessRules(),
                context.approvedTestPlans(), context.approvedTestCases(), List.of(), List.of(), List.of());
        assertThat(validate(context, test)).isEmpty();
    }

    @Test
    void doesNotRejectWhenTheEarlyReturnBranchReadsUser() throws Exception {
        var fixture = fixture(2467L);
        var classes = fixture.context().classes().stream().map(c -> {
            String source = c.sourceCode().replace("return Optional.empty();", "user.getId(); return Optional.empty();");
            return new ClassContextDto(c.id(), c.packageName(), c.className(), c.qualifiedName(), c.classType(),
                    c.filePath(), source, c.annotations(), c.methods());
        }).toList();
        var context = fixture.context();
        context = new UnitTestContextDto(context.project(), context.analysis(), classes, context.approvedBusinessRules(),
                context.approvedTestPlans(), context.approvedTestCases(), List.of(), List.of(), List.of());
        assertThat(validate(context, fixture.test())).isEmpty();
    }

    @Test
    void requestsCorrectedTestThroughExistingRetryWithoutChangingApprovedCase(@TempDir Path logs) throws Exception {
        var fixture = fixture(2467L);
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(1L)).thenReturn(fixture.context());
        var client = mock(LlmClient.class);
        var expected = new UnitTestResponseDto(List.of(transform(fixture.test(), this::removeUserStub)));
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(
                mapper.writeValueAsString(new UnitTestResponseDto(List.of(fixture.test()))),
                mapper.writeValueAsString(expected));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var agent = new AIAgentService(contexts, new PromptManager(mapper), client,
                    new GenerationResponseParser(mapper, validation.getValidator()), new AiContextLogService(mapper, logs.toString()));
            var result = agent.generateUnitTests(1L);
            assertThat(result.unitTests()).singleElement().satisfies(test -> {
                assertThat(test.caseId()).isEqualTo(2467L);
                assertThat(test.sourceCode()).isEqualTo(expected.unitTests().get(0).sourceCode());
            });
        }
    }

    private Optional<String> validate(UnitTestContextDto context, GeneratedUnitTestDto test) {
        return GeneratedUnitTestSemanticValidator.validate(context, new UnitTestResponseDto(List.of(test)),
                ProjectJavaVersionDetector.TestFramework.JUNIT5);
    }

    private String removeUserStub(String source) {
        return source.replace("org.mockito.Mockito.when(user.getId()).thenReturn(\"user-1\");", "");
    }

    private GeneratedUnitTestDto transform(GeneratedUnitTestDto test, UnaryOperator<String> change) {
        return new GeneratedUnitTestDto(test.caseId(), test.testClassName(), test.testMethodName(),
                test.packageName(), test.generationType(), change.apply(test.sourceCode()));
    }

    private Fixture fixture(long caseId) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/generated-contract/realworld-unused-stubs.json")) {
            var json = mapper.readTree(input);
            var context = mapper.treeToValue(json.get("context"), UnitTestContextDto.class);
            var classes = context.classes().stream().map(c -> new ClassContextDto(c.id(), c.packageName(), c.className(),
                    c.qualifiedName(), c.classType(), c.filePath(), c.sourceCode(), c.annotations(), c.methods())).toList();
            var cases = context.approvedTestCases().stream().filter(c -> c.id() == caseId).toList();
            context = new UnitTestContextDto(context.project(), context.analysis(), classes, context.approvedBusinessRules(),
                    context.approvedTestPlans(), cases, List.of(), List.of(), List.of());
            for (var test : json.get("tests")) {
                var generated = mapper.treeToValue(test, GeneratedUnitTestDto.class);
                if (generated.caseId() == caseId) return new Fixture(context, generated);
            }
            throw new IllegalArgumentException("Missing fixture case " + caseId);
        }
    }

    private record Fixture(UnitTestContextDto context, GeneratedUnitTestDto test) { }
}
