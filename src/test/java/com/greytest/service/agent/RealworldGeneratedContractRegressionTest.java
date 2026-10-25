package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

class RealworldGeneratedContractRegressionTest {
    @ParameterizedTest
    @ValueSource(longs = {2310L, 2312L, 2360L})
    void checksActualGeneratedTestsAgainstTheirOriginalSourceContext(long caseId) throws Exception {
        var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var input = getClass().getResourceAsStream("/fixtures/generated-contract/realworld-cases.json")) {
            for (var node : mapper.readTree(input)) {
                var generated = mapper.treeToValue(node.get("test"), GeneratedUnitTestDto.class);
                if (generated.caseId() != caseId) continue;
                var stored = mapper.treeToValue(node.get("context"), UnitTestContextDto.class);
                var classes = stored.classes().stream().map(c -> new ClassContextDto(c.id(), c.packageName(), c.className(),
                        c.qualifiedName(), c.classType(), c.filePath(), c.sourceCode(), c.annotations(), c.methods(), c.sourceCode())).toList();
                var context = new UnitTestContextDto(stored.project(), stored.analysis(), classes, stored.approvedBusinessRules(),
                        stored.approvedTestPlans(), stored.approvedTestCases(), List.of(), List.of(), List.of());
                if (caseId == 2310L) {
                    // Bản source gốc dùng Map.get + int nên NPE là đúng; không đổi TC thành mặc định 0.
                    assertThat(validate(context, generated, generated.sourceCode())).isEmpty();
                } else if (caseId == 2360L) {
                    assertThat(validate(context, generated, generated.sourceCode()))
                            .hasValueSatisfying(message -> assertThat(message).contains("getFollowing", "isFollowing"));
                    assertThat(validate(context, generated, generated.sourceCode().replace(".getFollowing()", ".isFollowing()"))).isEmpty();
                } else {
                    assertThat(validate(context, generated, generated.sourceCode()))
                            .hasValueSatisfying(message -> assertThat(message).contains("verify", "followingAuthors"));
                    String verified = generated.sourceCode().replace("// Assert", "// Assert\nverify(userRelationshipQueryService).followingAuthors(eq(currentUser.getId()), eq(Arrays.asList(\"author2\", \"author1\")));\nverify(articleFavoritesReadService).userFavorites(eq(Arrays.asList(\"a2\", \"a1\")), eq(currentUser));");
                    assertThat(validate(context, generated, verified))
                            .isEmpty();
                }
                return;
            }
        }
        throw new AssertionError("Missing real regression case " + caseId);
    }

    private java.util.Optional<String> validate(UnitTestContextDto context, GeneratedUnitTestDto original, String source) {
        return GeneratedUnitTestSemanticValidator.validate(context, new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                original.caseId(), original.testClassName(), original.testMethodName(), original.packageName(), original.generationType(), source))),
                ProjectJavaVersionDetector.TestFramework.JUNIT5);
    }
}
