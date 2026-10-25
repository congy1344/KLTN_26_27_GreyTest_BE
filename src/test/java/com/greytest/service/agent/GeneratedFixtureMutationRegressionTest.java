package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

class GeneratedFixtureMutationRegressionTest {
    @Test
    void rejectsExcludedElementReadFromMockReturnedListAfterInPlaceRemoval() throws Exception {
        assertThat(validate(java.util.function.UnaryOperator.identity(), java.util.function.UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("comments", "before", "mutat"));
    }

    @Test
    void acceptsElementReferenceCapturedBeforeTheServiceMutatesItsList() throws Exception {
        assertThat(validate(code -> code.replace("CursorPager<CommentData> result =",
                        "CommentData extraItem = comments.get(20);\nCursorPager<CommentData> result =")
                .replace("contains(comments.get(20))", "contains(extraItem)"), java.util.function.UnaryOperator.identity())).isEmpty();
    }

    @Test
    void acceptsIndependentExpectedListAndSourceThatDoesNotMutateTheFixture() throws Exception {
        assertThat(validate(code -> code.replace("contains(comments.get(20))", "contains(expected.get(19))"),
                java.util.function.UnaryOperator.identity())).isEmpty();
        assertThat(validate(java.util.function.UnaryOperator.identity(), code -> code.replace("comments.remove(page.getLimit());", "")))
                .isEmpty();
        assertThat(validate(code -> code.replace("thenReturn(comments)", "thenReturn(new ArrayList<>(comments))"),
                java.util.function.UnaryOperator.identity())).isEmpty();
    }

    @Test
    void acceptsSourceCopyBeforeRemoval() throws Exception {
        assertThat(validate(java.util.function.UnaryOperator.identity(), code -> code.replace(
                "comments.remove(page.getLimit());", "comments = new ArrayList<>(comments);\ncomments.remove(page.getLimit());"))).isEmpty();
    }

    @Test
    void acceptsRestoredFixtureSnapshot() throws Exception {
        assertThat(validate(code -> code.replace("CursorPager<CommentData> result =",
                "List<CommentData> snapshot = new ArrayList<>(comments);\nCursorPager<CommentData> result =")
                .replace("assertEquals(20, page.getLimit());", "comments = snapshot;\nassertEquals(20, page.getLimit());"),
                java.util.function.UnaryOperator.identity())).isEmpty();
    }

    @Test
    void rejectsTheSameMutationThroughExplicitThis() throws Exception {
        assertThat(validate(java.util.function.UnaryOperator.identity(), code -> code.replace(
                "comments = commentReadService.findByArticleIdWithCursor", "comments = this.commentReadService.findByArticleIdWithCursor")))
                .hasValueSatisfying(message -> assertThat(message).contains("comments", "before", "mutat"));
    }

    private Optional<String> validate(java.util.function.UnaryOperator<String> testTransform,
            java.util.function.UnaryOperator<String> sourceTransform) throws Exception {
        var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var input = getClass().getResourceAsStream("/fixtures/generated-contract/realworld-mutated-list.json")) {
            var node = mapper.readTree(input);
            var c = mapper.treeToValue(node.get("context"), UnitTestContextDto.class);
            var t = mapper.treeToValue(node.get("test"), GeneratedUnitTestDto.class);
            var classes = c.classes().stream().map(s -> new ClassContextDto(s.id(), s.packageName(), s.className(),
                    s.qualifiedName(), s.classType(), s.filePath(), sourceTransform.apply(s.sourceCode()), s.annotations(),
                    s.methods(), sourceTransform.apply(s.sourceCode()))).toList();
            var context = new UnitTestContextDto(c.project(), c.analysis(), classes, c.approvedBusinessRules(),
                    c.approvedTestPlans(), c.approvedTestCases(), List.of(), List.of(), List.of(), c.supportingResources(), c.contextWarnings());
            return GeneratedUnitTestSemanticValidator.validate(context, new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                    t.caseId(), t.testClassName(), t.testMethodName(), t.packageName(), t.generationType(), testTransform.apply(t.sourceCode())))),
                    ProjectJavaVersionDetector.TestFramework.JUNIT5);
        }
    }
}
