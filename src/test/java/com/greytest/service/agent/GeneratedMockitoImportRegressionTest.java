package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.greytest.dto.UnitTestFileDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.exception.StorageException;
import com.greytest.service.UnitTestFileService;

class GeneratedMockitoImportRegressionTest {

    @ParameterizedTest
    @ValueSource(strings = {"import org.mockito.junit.jupiter.Strictness;",
            "import static org.mockito.junit.jupiter.Strictness.STRICT_STUBS;",
            "import static demo.CustomHelpers.*; import org.mockito.junit.jupiter.Strictness;"})
    void rejectsWrongStrictnessPackageDuringGenerationAndExport(String imports) {
        var file = file(imports, "", "");
        var response = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "ServiceTest", "testCase", "demo", "NEW_TEST", file.sourceCode())));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), response))
                .hasValueSatisfying(message -> assertThat(message).contains("org.mockito.quality.Strictness"));
        assertThatThrownBy(() -> new UnitTestFileService().createCoverageArchive(List.of(file)))
                .isInstanceOf(StorageException.class).hasMessageContaining("org.mockito.quality.Strictness");
    }

    @ParameterizedTest
    @ValueSource(strings = {"import org.mockito.quality.Strictness;", "import org.mockito.quality.*;"})
    void acceptsCorrectStrictnessPackage(String imports) {
        var file = file(imports, "", "");
        file = new UnitTestFileDto(file.filePath(), file.testClassName(), file.packageName(), 1, file.caseCodes(),
                file.sourceCode().replace("class ServiceTest", "@org.mockito.junit.jupiter.MockitoSettings("
                        + "strictness = Strictness.STRICT_STUBS) class ServiceTest"));
        assertThat(new UnitTestFileService().createCoverageArchive(List.of(file))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Strictness.STRICT_STUBS", "org.mockito.junit.jupiter.Strictness.STRICT_STUBS"})
    void rejectsUnresolvedStrictnessInMockitoSettings(String value) {
        var file = file("import org.mockito.junit.jupiter.*;", "", "");
        var invalid = new UnitTestFileDto(file.filePath(), file.testClassName(), file.packageName(), 1, file.caseCodes(),
                file.sourceCode().replace("class ServiceTest", "@MockitoSettings(strictness = " + value + ") class ServiceTest"));
        assertThatThrownBy(() -> new UnitTestFileService().createCoverageArchive(List.of(invalid)))
                .isInstanceOf(StorageException.class).hasMessageContaining("org.mockito.quality.Strictness");
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifyNoMoreInteractions(repository)", "verify(repository)", "never()", "times(1)",
            "when(repository.toString())", "any(String.class)", "eq(\"id\")", "doThrow(new IllegalStateException())"})
    void refusesToExportMockitoCallsWithoutStaticImports(String invocation) {
        var file = file("import static org.junit.jupiter.api.Assertions.*;", "", invocation);
        assertThatThrownBy(() -> new UnitTestFileService().createCoverageArchive(List.of(file)))
                .isInstanceOf(StorageException.class).hasMessageContaining("static import");
    }

    @Test
    void rejectsMissingMockitoImportDuringGenerationEvenWhenFrameworkIsUnknown() {
        var file = file("import static org.junit.jupiter.api.Assertions.*;", "", "verifyNoMoreInteractions(repository)");
        var response = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "ServiceTest", "testCase", "demo", "NEW_TEST", file.sourceCode())));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), response))
                .hasValueSatisfying(message -> assertThat(message).contains("verifyNoMoreInteractions", "static import"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "import static org.mockito.Mockito.verifyNoMoreInteractions;|verifyNoMoreInteractions(repository)",
            "import static org.mockito.Mockito.*;|verifyNoMoreInteractions(repository)",
            "import static org.mockito.BDDMockito.*;|verifyNoMoreInteractions(repository)",
            "import static org.mockito.ArgumentMatchers.*;|any(String.class)",
            "import static org.mockito.Mockito.*;|eq(\"id\")",
            "import org.mockito.Mockito;|Mockito.verifyNoMoreInteractions(repository)",
            "import static demo.CustomHelpers.*;|verifyNoMoreInteractions(repository)"})
    void preservesImportedQualifiedAndUnresolvedCustomBindings(String imports, String invocation) {
        var file = file(imports, "", invocation);
        assertThat(new UnitTestFileService().createCoverageArchive(List.of(file))).isNotEmpty();
    }

    @Test
    void preservesDeclaredAndInheritedHelpers() {
        var declared = file("", "void verifyNoMoreInteractions(Object value) {}", "verifyNoMoreInteractions(repository)");
        assertThat(new UnitTestFileService().createCoverageArchive(List.of(declared))).isNotEmpty();
        var inherited = file("", "", "verifyNoMoreInteractions(repository)");
        inherited = new UnitTestFileDto(inherited.filePath(), inherited.testClassName(), inherited.packageName(),
                1, inherited.caseCodes(), inherited.sourceCode().replace("class ServiceTest {", "class ServiceTest extends BaseTest {"));
        assertThat(new UnitTestFileService().createCoverageArchive(List.of(inherited))).isNotEmpty();
    }

    @Test
    void argumentMatchersWildcardCannotBindMockitoVerification() {
        var file = file("import static org.mockito.ArgumentMatchers.*;", "", "verifyNoMoreInteractions(repository)");
        assertThatThrownBy(() -> new UnitTestFileService().createCoverageArchive(List.of(file)))
                .isInstanceOf(StorageException.class).hasMessageContaining("verifyNoMoreInteractions");
    }

    private UnitTestFileDto file(String imports, String helper, String invocation) {
        String source = "package demo; " + imports + " "
                + "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Object repository; " + helper
                + " @org.junit.jupiter.api.Test void testCase() { " + invocation + "; } }";
        return new UnitTestFileDto("src/test/java/demo/ServiceTest.java", "ServiceTest", "demo", 1, List.of("TC-001"), source);
    }

    private UnitTestContextDto context() {
        var method = new MethodContextDto(10L, "demo.Service", "run", "void", List.of(), List.of(),
                "public", "public void run() {}", 1, 1, List.of(), List.of(), List.of());
        var owner = new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", null, List.of(), List.of(method));
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-001", "rule", null, "AI", "APPROVED", false, null);
        var plan = new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-001", "plan", "plan", "HAPPY_PATH", "APPROVED", false);
        var testCase = new TestCaseContextItemDto(40L, 30L, "TC-001", "HAPPY_PATH", "case", "setup", Map.of(),
                "unchanged outcome", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(null, null, List.of(owner), List.of(rule), List.of(plan), List.of(testCase),
                List.of(), List.of(), List.of());
    }
}
