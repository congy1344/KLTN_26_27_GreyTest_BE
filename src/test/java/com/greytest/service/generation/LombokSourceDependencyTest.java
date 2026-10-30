package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.service.agent.LlmStageTokenLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class LombokSourceDependencyTest {
    @TempDir Path root;
    private final GenerationFingerprintService service = new GenerationFingerprintService(
            new ObjectMapper(), null, LlmStageTokenLimits.defaults(), new MockEnvironment());

    @Test void sourceLombokSymbolsResolveChainedCallsAndConstructors() throws Exception {
        write("Patient", "package example; @lombok.Data @lombok.NoArgsConstructor @lombok.AllArgsConstructor public class Patient { private Long id; private String name; }");
        write("Subject", "package example; @lombok.extern.slf4j.Slf4j @lombok.RequiredArgsConstructor class Subject { private final Patient patient; String target() { log.info(\"read\"); return patient.getName().trim(); } }");
        var before = fingerprint("Patient p = new Patient(); p.setName(\"hello\"); new Subject(p).target();");
        assertThat(before.provenance()).doesNotContain("unresolved");
        assertThat(before.reusable()).isTrue();
        write("Patient", "package example; @lombok.Data @lombok.NoArgsConstructor @lombok.AllArgsConstructor public class Patient { private Long id; private String name = \"changed\"; }");
        assertThat(fingerprint("Patient p = new Patient(); p.setName(\"hello\"); new Subject(p).target();").value()).isNotEqualTo(before.value());
    }

    @Test void generatedAccessorStillRejectsIncompatibleSetterAndStaticCall() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private String name; }");
        assertThat(fingerprint("Patient p = new Patient(); p.setName(42);").reusable()).isFalse();
        assertThat(fingerprint("Patient.getName();").reusable()).isFalse();
    }

    @Test void sourceOverrideCannotBeIgnoredForGeneratedGetter() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private String name; }");
        write("Child", "package example; class Child extends Patient { public String getName() { return \"child\"; } }");
        assertThat(fingerprint("Patient p = new Child(); p.getName();").reusable()).isFalse();
    }

    @Test void customLombokConfigurationStaysUnresolved() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private String name; }");
        Files.writeString(root.resolve("lombok.config"), "lombok.accessors.fluent = true\n");
        assertThat(fingerprint("Patient p = new Patient(); p.getName();").reusable()).isFalse();
    }

    @Test void ownerAccessLevelCannotBeOverriddenByDataInference() throws Exception {
        for (String annotation : java.util.List.of("@lombok.Getter(lombok.AccessLevel.NONE)", "@lombok.Getter(lombok.AccessLevel.PRIVATE)")) {
            write("Patient", "package example; @lombok.Data " + annotation + " public class Patient { private String name; }");
            assertThat(fingerprint("Patient p = new Patient(); p.getName();").reusable()).as(annotation).isFalse();
        }
        write("Patient", "package example; @lombok.Data @lombok.Setter(lombok.AccessLevel.NONE) public class Patient { private String name; }");
        assertThat(fingerprint("Patient p = new Patient(); p.setName(\"hello\");").reusable()).isFalse();
    }

    @Test void addingLombokConfigurationInvalidatesAnAlreadyCachedIndex() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private String name; }");
        assertThat(fingerprint("Patient p = new Patient(); p.getName();").reusable()).isTrue();
        Files.writeString(root.resolve("lombok.config"), "lombok.accessors.fluent = true\n");
        assertThat(fingerprint("Patient p = new Patient(); p.getName();").reusable()).isFalse();
    }

    @Test void unsupportedSurfaceAnnotationsRemainUnresolved() throws Exception {
        for (String annotation : java.util.List.of("@lombok.experimental.FieldDefaults(makeFinal=true)", "@lombok.experimental.FieldNameConstants")) {
            write("Patient", "package example; @lombok.Data " + annotation + " public class Patient { private String name; }");
            assertThat(fingerprint("Patient p = new Patient();").reusable()).as(annotation).isFalse();
        }
    }

    @Test void builderResolvesFluentChainAndBuild() throws Exception {
        write("Patient", "package example; @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor public class Patient { private String name; }");
        var fp = fingerprint("Patient p = Patient.builder().name(\"Alice\").build();");
        assertThat(fp.reusable()).isTrue();
        assertThat(fp.provenance()).doesNotContain("unresolved");
    }

    @Test void booleanGetterAliasesCannotCreateAnAbsentAccessor() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private boolean active; public boolean getActive() { return false; } }");
        assertThat(fingerprint("Patient p = new Patient(); p.isActive();").reusable()).isFalse();
    }

    @Test void dataEqualityTracksCustomCanEqualHelper() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private String name; protected boolean canEqual(Object value) { return helper(); } private boolean helper() { return true; } }");
        var before = fingerprint("Patient p = new Patient(); p.equals(new Patient());");
        assertThat(before.reusable()).isTrue();
        write("Patient", "package example; @lombok.Data public class Patient { private String name; protected boolean canEqual(Object value) { return helper(); } private boolean helper() { return false; } }");
        assertThat(fingerprint("Patient p = new Patient(); p.equals(new Patient());").value()).isNotEqualTo(before.value());
    }

    @Test void unresolvedParentInAccessibleModuleBlocksReuse() throws Exception {
        write("Patient", "package example; @lombok.Data public class Patient { private String name; }");
        write("UnknownChild", "package example; class UnknownChild extends MissingBase { }");
        assertThat(fingerprint("Patient p = new Patient(); p.getName();").reusable()).isFalse();
    }

    @Test void wildcardAnnotationShadowingCannotInventAccessors() throws Exception {
        write("Patient", "package example; import lombok.*; @interface Getter {} @Getter public class Patient { private String name; }");
        assertThat(fingerprint("Patient p = new Patient(); p.getName();").reusable()).isFalse();
    }


    private GenerationFingerprintService.Fingerprint fingerprint(String statements) {
        return service.outputFingerprint(Map.of("source_code", "package example; class GeneratedTest { void test() { " + statements + " } }"), root);
    }
    private void write(String name, String source) throws Exception {
        Path file = root.resolve("src/main/java/example/" + name + ".java");
        Files.createDirectories(file.getParent()); Files.writeString(file, source);
    }
}
