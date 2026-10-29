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

class ConstructorReferenceDependencyTest {
    @TempDir Path root;
    private final GenerationFingerprintService service = new GenerationFingerprintService(
            new ObjectMapper(), null, LlmStageTokenLimits.defaults(), new MockEnvironment());

    @Test void streamConstructorReferenceTracksItsSourceConstructor() throws Exception {
        setup("Patient", "this.name = patient.name;", "java.util.stream.Collectors.toList()");
        var before = fingerprint();
        assertThat(before.provenance()).doesNotContain("unresolved");
        assertThat(before.reusable()).isTrue();
        write("Response", "package example; class Response { String name; public Response(Patient patient) { this.name = patient.name + \"changed\"; } }");
        assertThat(fingerprint().value()).isNotEqualTo(before.value());
    }

    @Test void incompatibleConstructorReferenceRemainsBlocked() throws Exception {
        setup("Integer", "this.name = \"other\";", "java.util.stream.Collectors.toList()");
        assertThat(fingerprint().reusable()).isFalse();
    }

    @Test void invalidCollectorRemainsBlocked() throws Exception {
        setup("Patient", "this.name = patient.name;", "42");
        assertThat(fingerprint().reusable()).isFalse();
    }

    @Test void genericTargetAndCheckedExceptionAreNotGuessed() throws Exception {
        setup("Patient", "this.name = patient.name;", "java.util.stream.Collectors.toList()");
        write("Response", "package example; class Response<T> { public Response(T value) {} }");
        write("Subject", "package example; class Subject { java.util.List<Patient> values; Object target() { return values.stream().map(Response<String>::new).collect(java.util.stream.Collectors.toList()); } }");
        assertThat(fingerprint().reusable()).isFalse();
        setup("Patient", "this.name = patient.name;", "java.util.stream.Collectors.toList()");
        write("Response", "package example; class Response { public Response(Patient value) throws java.io.IOException {} }");
        assertThat(fingerprint().reusable()).isFalse();
    }

    @Test void inaccessibleAndInnerTypesRemainBlocked() throws Exception {
        setup("Patient", "this.name = patient.name;", "java.util.stream.Collectors.toList()");
        Path other = root.resolve("src/main/java/other/Outer.java"); Files.createDirectories(other.getParent());
        Files.writeString(other, "package other; public class Outer { public class Inner { public Inner(example.Patient value) {} } }");
        write("Patient", "package example; public class Patient { String name; }");
        write("Subject", "package example; class Subject { java.util.List<Patient> values; Object target() { return values.stream().map(other.Outer.Inner::new).collect(java.util.stream.Collectors.toList()); } }");
        assertThat(fingerprint().reusable()).isFalse();
        Files.writeString(root.resolve("src/main/java/other/Response.java"), "package other; class Response { public Response(example.Patient value) {} }");
        write("Subject", "package example; class Subject { java.util.List<Patient> values; Object target() { return values.stream().map(other.Response::new).collect(java.util.stream.Collectors.toList()); } }");
        assertThat(fingerprint().reusable()).isFalse();
    }

    @Test void throwingSpecificOverloadCannotBeReplacedByObjectOverload() throws Exception {
        setup("Patient", "this.name = patient.name;", "java.util.stream.Collectors.toList()");
        write("Response", "package example; class Response { public Response(Object value) {} public Response(Patient value) throws java.io.IOException {} }");
        assertThat(fingerprint().reusable()).isFalse();
    }

    private void setup(String argumentType, String body, String collector) throws Exception {
        write("Patient", "package example; class Patient { String name; }");
        write("Response", "package example; class Response { String name; public Response(" + argumentType + " patient) { " + body + " } }");
        write("Subject", "package example; class Subject { java.util.List<Patient> values; java.util.List<Response> target() { return values.stream().map(Response::new).collect(" + collector + "); } }");
    }
    private GenerationFingerprintService.Fingerprint fingerprint() {
        return service.outputFingerprint(Map.of("source_code", "package example; class GeneratedTest { void test() { new Subject().target(); } }"), root);
    }
    private void write(String name, String source) throws Exception {
        Path file = root.resolve("src/main/java/example/" + name + ".java");
        Files.createDirectories(file.getParent()); Files.writeString(file, source);
    }
}
