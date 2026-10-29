package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.LlmStageTokenLimits;
import com.greytest.service.agent.PromptManager;

class GenerationFingerprintServiceTest {
    @Test void unchangedOutputValidationReusesSolverWithoutGrowingFacadeRegistry() {
        var output=Map.of("source_code","package example; class SubjectTest { void test(){ new Subject().target(1); } }");
        try(var scope=GenerationJobContext.openScope(null)) {
            assertThat(service.outputFingerprint(output,root).reusable()).isTrue();
        }
        int initial=facadeRegistrySize();
        for(int index=0;index<12;index++) try(var scope=GenerationJobContext.openScope(null)) {
            assertThat(service.outputFingerprint(output,root).reusable()).isTrue();
        }
        assertThat(facadeRegistrySize()).isEqualTo(initial);
    }

    @Test void uncachedLargeProjectReleasesFacadeRegistryAfterOutputValidation() throws Exception {
        String comment="/*"+"a".repeat(1_050_000)+"*/";
        write("src/main/java/example/FirstLarge.java","package example; "+comment+" class FirstLarge {}");
        write("src/main/java/example/SecondLarge.java","package example; "+comment+" class SecondLarge {}");
        try(var scope=GenerationJobContext.openScope(null)) {
            assertThat(service.outputFingerprint(Map.of("source_code","package example; class SubjectTest { void test(){ new Subject().target(1); } }"),root).reusable()).isTrue();
        }
        assertThat(facadeRegistrySize()).isZero();
    }

    private int facadeRegistrySize() {
        synchronized(com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class) {
            return ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(
                com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class,"instances")).size();
        }
    }
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private PromptManager prompts;
    private MockEnvironment environment;
    private GenerationFingerprintService service;

    @BeforeEach
    void setup() throws Exception {
        prompts = mock(PromptManager.class);
        when(prompts.load("business-rule")).thenReturn("rules-v1");
        when(prompts.load("unit-test")).thenReturn("tests-v1");
        when(prompts.load("source-contract")).thenReturn("contract-v1");
        environment = new MockEnvironment().withProperty("llm.provider", "mock");
        service = new GenerationFingerprintService(mapper, prompts, LlmStageTokenLimits.defaults(), environment);
        write("src/main/java/example/Subject.java", subject("return helper(value);", "return value + 1;", "return 5;"));
    }

    @Test void unchangedSourceAndListOrderingHaveSameFingerprint() {
        var first = fingerprint(context(List.of(method(1L, "target", "int"), method(2L, "other", null))));
        var second = fingerprint(context(List.of(method(2L, "other", null), method(1L, "target", "int"))));
        assertThat(first.reusable()).isTrue();
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(first.provenance()).contains("greytest-fingerprint-v1", "sourceDependencies");
    }

    @Test void unrelatedMethodChangeDoesNotInvalidateSelectedTarget() throws Exception {
        var context = context(List.of(method(1L, "target", "int")));
        String before = fingerprint(context).value();
        write("src/main/java/example/Subject.java", subject("return helper(value);", "return value + 1;", "return 100;"));
        assertThat(fingerprint(context).value()).isEqualTo(before);
    }

    @Test void targetAndReachableHelperChangesInvalidate() throws Exception {
        var context = context(List.of(method(1L, "target", "int")));
        String before = fingerprint(context).value();
        write("src/main/java/example/Subject.java", subject("return helper(value);", "return value + 2;", "return 5;"));
        String helperChanged = fingerprint(context).value();
        assertThat(helperChanged).isNotEqualTo(before);
        write("src/main/java/example/Subject.java", subject("return helper(value) + 3;", "return value + 2;", "return 5;"));
        assertThat(fingerprint(context).value()).isNotEqualTo(helperChanged);
    }

    @Test void typedCollaboratorMethodChangeInvalidatesButOtherMethodDoesNot() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { private Dao dao; int target(int value) { return dao.read(value); } }");
        write("src/main/java/example/Dao.java", "package example; class Dao { int read(int value) { return value; } int unused() { return 1; } }");
        var context = context(List.of(method(1L, "target", "int")));
        var before = fingerprint(context);
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Dao.java", "package example; class Dao { int read(int value) { return value; } int unused() { return 2; } }");
        assertThat(fingerprint(context).value()).isEqualTo(before.value());
        write("src/main/java/example/Dao.java", "package example; class Dao { int read(int value) { return value + 1; } int unused() { return 2; } }");
        assertThat(fingerprint(context).value()).isNotEqualTo(before.value());
    }

    @Test void dtoAndBuildContractChangesInvalidate() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { Input target(Input value) { return value; } }");
        write("src/main/java/example/Input.java", "package example; record Input(String name) {}");
        var context = context(List.of(method(1L, "target", "Input")));
        String first = fingerprint(context).value();
        write("src/main/java/example/Input.java", "package example; record Input(String name, int age) {}");
        String dtoChanged = fingerprint(context).value();
        assertThat(dtoChanged).isNotEqualTo(first);
        write("pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        assertThat(fingerprint(context).value()).isNotEqualTo(dtoChanged);
    }

    @Test void dtoHelperAndMethodAnnotationContractsInvalidate() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { @Policy Input target(Input value) { return value; } }");
        write("src/main/java/example/Policy.java", "package example; @interface Policy { String value() default \"one\"; }");
        write("src/main/java/example/Input.java", "package example; class Input { int normalized(int value) { return Rules.clean(value); } }");
        write("src/main/java/example/Rules.java", "package example; class Rules { static int clean(int value) { return value; } }");
        var context = context(List.of(method(1L, "target", "Input")));
        var before = fingerprint(context);
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Rules.java", "package example; class Rules { static int clean(int value) { return value + 1; } }");
        var helperChanged = fingerprint(context);
        assertThat(helperChanged.value()).isNotEqualTo(before.value());
        write("src/main/java/example/Policy.java", "package example; @interface Policy { String value() default \"two\"; }");
        assertThat(fingerprint(context).value()).isNotEqualTo(helperChanged.value());
    }

    @Test void overloadAndModuleIdentitiesStayDistinct() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { int target(int value) { return 1; } int target(String value) { return 2; } }");
        assertThat(fingerprint(context(List.of(method(1L, "target", "int")))).value())
                .isNotEqualTo(fingerprint(context(List.of(method(1L, "target", "String")))).value());
        write("another/src/main/java/example/Subject.java", "package example; class Subject { int target(int value) { return 1; } }");
        var otherModule = new ClassContextDto(1L, "example", "Subject", "example.Subject", "SERVICE", "another/src/main/java/example/Subject.java", "", List.of(), List.of(method(1L, "target", "int")));
        var otherContext = new BusinessRuleGenerationContextDto(project(), null, List.of(otherModule), List.of(), List.of(), List.of(), List.of());
        assertThat(fingerprint(otherContext).reusable()).isTrue();
        assertThat(fingerprint(otherContext).value()).isNotEqualTo(fingerprint(context(List.of(method(1L, "target", "int")))).value());
    }

    @Test void promptAndProviderConfigurationChangesInvalidateWithoutSecrets() {
        var context = context(List.of(method(1L, "target", "int")));
        String before = fingerprint(context).value();
        when(prompts.load("business-rule")).thenReturn("rules-v2");
        String promptChanged = fingerprint(context).value();
        assertThat(promptChanged).isNotEqualTo(before);
        environment.withProperty("llm.temperature", "0.8").withProperty("llm.api-key", "TOP-SECRET");
        var configChanged = fingerprint(context);
        assertThat(configChanged.value()).isNotEqualTo(promptChanged);
        assertThat(configChanged.provenance()).doesNotContain("TOP-SECRET");
    }

    @Test void ownGeneratedOutputAndUnrelatedExistingCasesAreExcluded() {
        var classes = context(List.of(method(1L, "target", "int"))).classes();
        var target = new TestCaseContextItemDto(1L, 10L, "TC1", "HAPPY_PATH", "target", "", Map.of("b", 2, "a", 1), "one", "HIGH", "", "APPROVED", false);
        var first = new UnitTestContextDto(project(), null, classes, List.of(), List.of(), List.of(target), List.of(), List.of(), List.of());
        var own = new GeneratedUnitTestContextDto(1L, "SubjectTest", "targetTest", "example", "src/test/java/example/SubjectTest.java", "new output");
        var second = new UnitTestContextDto(project(), null, classes, List.of(), List.of(), List.of(target), List.of(new TestCaseContextItemDto(999L, 999L, "OTHER", "EDGE", "unrelated", "", Map.of(), "other", "LOW", "", "APPROVED", false)), List.of(own), List.of());
        assertThat(service.fingerprint("unit-test", first, root).value()).isEqualTo(service.fingerprint("unit-test", second, root).value());
    }

    @Test void reviewMetadataDoesNotInvalidateButBusinessTestDataStatusDoes() {
        var classes = context(List.of(method(1L, "target", "int"))).classes();
        var initial = new TestCaseContextItemDto(1L, 10L, "TC1", "HAPPY_PATH", "target", "", Map.of("status", "OPEN"), "one", "HIGH", "", "APPROVED", false);
        var reviewed = new TestCaseContextItemDto(1L, 10L, "TC1", "HAPPY_PATH", "target", "", Map.of("status", "OPEN"), "one", "HIGH", "", "PENDING", true);
        var changed = new TestCaseContextItemDto(1L, 10L, "TC1", "HAPPY_PATH", "target", "", Map.of("status", "CLOSED"), "one", "HIGH", "", "PENDING", true);
        var first = new UnitTestContextDto(project(), null, classes, List.of(), List.of(), List.of(initial), List.of(), List.of(), List.of());
        var second = new UnitTestContextDto(project(), null, classes, List.of(), List.of(), List.of(reviewed), List.of(), List.of(), List.of());
        var third = new UnitTestContextDto(project(), null, classes, List.of(), List.of(), List.of(changed), List.of(), List.of(), List.of());
        assertThat(service.fingerprint("unit-test", second, root).value()).isEqualTo(service.fingerprint("unit-test", first, root).value());
        assertThat(service.fingerprint("unit-test", third, root).value()).isNotEqualTo(service.fingerprint("unit-test", first, root).value());
    }

    @Test void outputFingerprintTracksAdditionalProductionApisWithoutHashingTestSource() throws Exception {
        write("src/main/java/example/Factory.java", "package example; class Factory { static int fixture() { return 7; } static int other() { return 2; } }");
        String source = "package example; import static org.junit.jupiter.api.Assertions.assertEquals; class SubjectTest { Subject subject = new Subject(); @org.junit.jupiter.api.Test void testTarget() { assertEquals(Factory.fixture(), subject.target(1)); } }";
        Object response = Map.of("unit_tests", List.of(Map.of("source_code", source)));
        var before = service.outputFingerprint(response, root);
        assertThat(before.reusable()).isTrue();
        Object reformatted = Map.of("unit_tests", List.of(Map.of("source_code", "// GREYTEST_SOURCE_V1 ignored\n" + source.replace("testTarget", "renamedTest"))));
        assertThat(service.outputFingerprint(reformatted, root).value()).isEqualTo(before.value());
        write("src/main/java/example/Factory.java", "package example; class Factory { static int fixture() { return 7; } static int other() { return 9; } }");
        assertThat(service.outputFingerprint(response, root).value()).isEqualTo(before.value());
        write("src/main/java/example/Factory.java", "package example; class Factory { static int fixture() { return 8; } static int other() { return 9; } }");
        assertThat(service.outputFingerprint(response, root).value()).isNotEqualTo(before.value());
        assertThat(service.outputFingerprint(Map.of("rules", List.of(Map.of("description", "rule"))), root).reusable()).isTrue();
    }

    @Test void outputFingerprintCannotReuseUnknownProductionCall() {
        String source = "package example; class SubjectTest { void testTarget() { Subject.missingApi(); } }";
        assertThat(service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", source))), root).reusable()).isFalse();
    }

    @Test void implementationInAnotherModuleCannotBeDiscarded() throws Exception {
        write("module-a/src/main/java/example/Subject.java", "package example; class Subject { Repo repo; int target() { return repo.save(1); } }");
        write("module-a/src/main/java/example/Repo.java", "package example; interface Repo { int save(int n); }");
        write("module-a/src/main/java/example/Local.java", "package example; class Local implements Repo { public int save(int n) { return n; } }");
        write("module-b/src/main/java/example/Remote.java", "package example; class Remote implements Repo { public int save(int n) { return n + 1; } }");
        Files.delete(root.resolve("src/main/java/example/Subject.java"));
        var response = Map.of("source_code", "package example; class SubjectTest { void test() { new Subject().target(); } }");
        assertThat(service.outputFingerprint(response, root).reusable()).isFalse();
    }

    @Test void constructorHelperDependenciesAreResolvedAndTracked() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { Subject() { Factory.touch(); } int target() { return 1; } }");
        write("src/main/java/example/Factory.java", "package example; class Factory { static void touch() { int value = 1; } }");
        var response = Map.of("source_code", "package example; class SubjectTest { void test() { new Subject().target(); } }");
        var before = service.outputFingerprint(response, root);
        assertThat(before.provenance()).doesNotContain("unresolved constructor helper calls");
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Factory.java", "package example; class Factory { static void touch() { int value = 2; } }");
        assertThat(service.outputFingerprint(response, root).value()).isNotEqualTo(before.value());
    }

    @Test void unknownConstructorHelperCannotBeReused() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { Subject() { missing(); } int target() { return 1; } }");
        var response = Map.of("source_code", "package example; class SubjectTest { void test() { new Subject().target(); } }");
        assertThat(service.outputFingerprint(response, root).reusable()).isFalse();
    }

    @Test void inheritedConstructorHelperIsTracked() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject extends Base { Subject() { super(); } int target() { return 1; } }");
        write("src/main/java/example/Base.java", "package example; class Base { Base() { Factory.touch(); } }");
        write("src/main/java/example/Factory.java", "package example; class Factory { static void touch() { int value = 1; } }");
        var response = Map.of("source_code", "package example; class SubjectTest { void test() { new Subject().target(); } }");
        var before = service.outputFingerprint(response, root);
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Factory.java", "package example; class Factory { static void touch() { int value = 2; } }");
        assertThat(service.outputFingerprint(response, root).value()).isNotEqualTo(before.value());
    }

    @Test void lombokGeneratedAccessorsTrackFieldContract() throws Exception {
        write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private String name; }");
        var response = Map.of("source_code", "package example; class PatientTest { void test() { Patient p = new Patient(); p.getName(); } }");
        var before = service.outputFingerprint(response, root);
        assertThat(before.provenance()).doesNotContain("unresolved output overload");
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private int name; }");
        assertThat(service.outputFingerprint(response, root).value()).isNotEqualTo(before.value());
    }

    @Test void lombokAccessorOnExtensibleTypeCannotIgnoreOverrides() throws Exception {
        write("src/main/java/example/Patient.java", "package example; @lombok.Data class Patient { private String name; }");
        write("src/main/java/example/Child.java", "package example; class Child extends Patient { public String getName() { return \"child\"; } }");
        var response = Map.of("source_code", "package example; class PatientTest { void test() { Patient p = new Child(); p.getName(); } }");
        assertThat(service.outputFingerprint(response, root).reusable()).isFalse();
    }

    @Test void lombokAccessorSuppressedByExistingMethodIsNotVerified() throws Exception {
        for (String method : List.of("String getname() { return name; }", "String GETNAME(String... args) { return name; }")) {
            write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private String name; " + method + " }");
            var response = Map.of("source_code", "package example; class PatientTest { void test() { Patient p = new Patient(); p.getName(); } }");
            assertThat(service.outputFingerprint(response, root).reusable()).as(method).isFalse();
        }
    }

    @Test void explicitVarargsAccessorTracksActualMethod() throws Exception {
        write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private String name; String getName(String... args) { return \"one\"; } }");
        var response = Map.of("source_code", "package example; class PatientTest { void test() { Patient p = new Patient(); p.getName(); } }");
        var before = service.outputFingerprint(response, root);
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private String name; String getName(String... args) { return \"two\"; } }");
        assertThat(service.outputFingerprint(response, root).value()).isNotEqualTo(before.value());
    }

    @Test void lombokConfigurationDisablesAccessorInference() throws Exception {
        write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private String name; }");
        write("lombok.config", "lombok.accessors.fluent = true\n");
        var response = Map.of("source_code", "package example; class PatientTest { void test() { Patient p = new Patient(); p.getName(); } }");
        assertThat(service.outputFingerprint(response, root).reusable()).isFalse();
    }

    @Test void lombokAccessorRejectsTypeQualifierAndIncompatibleArgument() throws Exception {
        write("src/main/java/example/Patient.java", "package example; @lombok.Data final class Patient { private String name; }");
        for (String call : List.of("Patient.getName();", "Patient p = new Patient(); p.setName(42);")) {
            var response = Map.of("source_code", "package example; class PatientTest { void test() { " + call + " } }");
            assertThat(service.outputFingerprint(response, root).reusable()).as(call).isFalse();
        }
    }

    @Test void accessorWithoutLombokRemainsUnresolved() throws Exception {
        write("src/main/java/example/Patient.java", "package example; class Patient { private String name; }");
        var response = Map.of("source_code", "package example; class PatientTest { void test() { Patient p = new Patient(); p.getName(); } }");
        assertThat(service.outputFingerprint(response, root).reusable()).isFalse();
    }

    @Test void factoryReturningSutDoesNotFingerprintUnrelatedSutMethods() throws Exception {
        write("src/main/java/example/Factory.java", "package example; class Factory { static Subject make() { return new Subject(); } }");
        var response = Map.of("unit_tests", List.of(Map.of("source_code", "package example; class SubjectTest { Subject subject = Factory.make(); void testTarget() { subject.target(1); } }")));
        var before = service.outputFingerprint(response, root);
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Subject.java", subject("return helper(value);", "return value + 1;", "return 99;"));
        assertThat(service.outputFingerprint(response, root).value()).isEqualTo(before.value());
    }

    @Test void unresolvedMockitoWrapperStillTracksItsTypedProductionCall() throws Exception {
        String source = "package example; import static org.mockito.fixture.Missing.verify; class SubjectTest { Subject subject = new Subject(); void testTarget() { verify(subject).other(); } }";
        var response = Map.of("unit_tests", List.of(Map.of("source_code", source)));
        var before = service.outputFingerprint(response, root);
        assertThat(before.reusable()).isTrue();
        write("src/main/java/example/Subject.java", subject("return helper(value);", "return value + 1;", "return 99;"));
        assertThat(service.outputFingerprint(response, root).value()).isNotEqualTo(before.value());
    }

    @Test void reflectiveProductionApiCannotBeReusedWithoutResolution() {
        String source = "package example; class SubjectTest { Subject subject = new Subject(); void testTarget() { org.springframework.test.util.ReflectionTestUtils.invokeMethod(subject, \"other\"); } }";
        assertThat(service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", source))), root).reusable()).isFalse();
    }

    @Test void literalWhitespaceIsPreservedButCommentsAreIgnored() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { String target(int value) { return \"a b\"; } }");
        var context = context(List.of(method(1L, "target", "int")));
        String before = fingerprint(context).value();
        write("src/main/java/example/Subject.java", "package example; class Subject { /* comment */ String target(int value) { return \"a b\"; } }");
        assertThat(fingerprint(context).value()).isEqualTo(before);
        write("src/main/java/example/Subject.java", "package example; class Subject { String target(int value) { return \"a  b\"; } }");
        assertThat(fingerprint(context).value()).isNotEqualTo(before);
    }

    @Test void unresolvedInternalCallCannotBeReused() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { int target(int value) { return missing(value); } }");
        var result = fingerprint(context(List.of(method(1L, "target", "int"))));
        assertThat(result.reusable()).isFalse();
        assertThat(result.provenance()).contains("unresolved");
    }

    @Test void changedSourceWithinSameRunDoesNotUseStaleIndex() throws Exception {
        GenerationJobContext.bind(1L, ignored -> {});
        try {
            var context = context(List.of(method(1L, "target", "int")));
            String before = fingerprint(context).value();
            write("src/main/java/example/Subject.java", subject("return helper(value);", "return value + 9;", "return 5;"));
            assertThat(fingerprint(context).value()).isNotEqualTo(before);
        } finally { GenerationJobContext.clear(); }
    }

    @Test void batchSourceReadsAreSharedAndNestedFreshSnapshotDetectsResourceEdits() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { @org.springframework.beans.factory.annotation.Value(\"${limits.maximum}\") int maximum; int target(int value) { return value + maximum; } }");
        write("src/main/resources/application-prod.properties", "limits.maximum=5\n");
        GenerationJobContext.bind(1L, ignored -> {});
        try {
            var context = context(List.of(method(1L, "target", "int")));
            com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(() -> {
                var before = fingerprint(context);
                long reads = ((Number) GenerationJobContext.metrics().get("dependencySourceReads")).longValue();
                assertThat(fingerprint(context).value()).isEqualTo(before.value());
                assertThat(((Number) GenerationJobContext.metrics().get("dependencySourceReads")).longValue()).isEqualTo(reads);
                try { write("src/main/resources/application-prod.properties", "limits.maximum=10\n"); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
                var fresh = com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(() -> fingerprint(context));
                assertThat(fresh.value()).isNotEqualTo(before.value());
                return null;
            });
        } finally { GenerationJobContext.clear(); }
    }

    @Test void verifiedSourceIndexSurvivesJobsButEveryResourceAndSourceEditIsChecked() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { @org.springframework.beans.factory.annotation.Value(\"${limits.maximum}\") int maximum; int target(int value) { return helper(value) + maximum; } int helper(int value) { return value; } }");
        write("src/main/resources/application-prod.properties", "limits.maximum=5\n");
        var context = context(List.of(method(1L, "target", "int")));
        String before;
        GenerationJobContext.bind(1L, ignored -> {});
        try {
            before = fingerprint(context).value();
            assertThat(((Number) GenerationJobContext.metrics().getOrDefault("dependencyAstParses", 0L)).longValue()).isPositive();
        } finally { GenerationJobContext.clear(); }
        GenerationJobContext.bind(1L, ignored -> {});
        try {
            assertThat(fingerprint(context).value()).isEqualTo(before);
            assertThat(((Number) GenerationJobContext.metrics().getOrDefault("dependencyAstParses", 0L)).longValue()).isZero();
            write("src/main/resources/application-prod.properties", "limits.maximum=10\n");
            assertThat(fingerprint(context).value()).isNotEqualTo(before);
            assertThat(((Number) GenerationJobContext.metrics().getOrDefault("dependencyAstParses", 0L)).longValue()).isZero();
            write("src/main/java/example/Subject.java", "package example; class Subject { @org.springframework.beans.factory.annotation.Value(\"${limits.maximum}\") int maximum; int target(int value) { return helper(value) + maximum; } int helper(int value) { return value + 1; } }");
            assertThat(fingerprint(context).value()).isNotEqualTo(before);
            assertThat(((Number) GenerationJobContext.metrics().getOrDefault("dependencyAstParses", 0L)).longValue()).isPositive();
        } finally { GenerationJobContext.clear(); }
    }

    @Test void pathOutsideSnapshotCannotBeReused() {
        var badClass = new ClassContextDto(1L, "example", "Subject", "example.Subject", "SERVICE", "../outside.java", "", List.of(), List.of(method(1L, "target", "int")));
        assertThat(fingerprint(new BusinessRuleGenerationContextDto(project(), null, List.of(badClass), List.of(), List.of(), List.of(), List.of())).reusable()).isFalse();
    }

    private GenerationFingerprintService.Fingerprint fingerprint(Object context) { return service.fingerprint("business-rule", context, root); }
    private ProjectContextDto project() { return new ProjectContextDto(1L, "Example", "ANALYZED"); }
    private BusinessRuleGenerationContextDto context(List<MethodContextDto> methods) {
        return new BusinessRuleGenerationContextDto(project(), null, List.of(new ClassContextDto(1L, "example", "Subject", "example.Subject", "SERVICE", "src/main/java/example/Subject.java", "", List.of(), methods)), List.of(), List.of(), List.of(), List.of());
    }
    private MethodContextDto method(Long id, String name, String parameterType) {
        return new MethodContextDto(id, "example.Subject", name, "int", parameterType == null ? List.of() : List.of(new MethodParamDto("value", parameterType)), List.of(), "public", "", 1, 2, List.of(), List.of(), List.of());
    }
    private String subject(String target, String helper, String other) {
        return "package example; class Subject { int target(int value) { " + target + " } private int helper(int value) { " + helper + " } int other() { " + other + " } }";
    }
    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }
}
