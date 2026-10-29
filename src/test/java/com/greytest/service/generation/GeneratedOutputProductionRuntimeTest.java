package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedOutputProductionRuntimeTest {
    @Test void safeSpringFixtureRemainsReusableWithoutSpringTestRuntime(@TempDir Path root) throws Exception {
        Path source = root.resolve("src/main/java/example/Subject.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class Subject { private String value; }");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        classpath = java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("spring-test-"))
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, RuntimeCheck.class.getName(), root.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(output).isZero();
    }

    public static class RuntimeCheck {
        public static void main(String[] arguments) throws Exception {
            try {
                Class.forName("org.springframework.test.util.ReflectionTestUtils");
                throw new AssertionError("spring-test must be absent from this runtime");
            } catch (ClassNotFoundException expected) { }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var service = new GenerationFingerprintService(mapper, new com.greytest.service.agent.PromptManager(mapper),
                    com.greytest.service.agent.LlmStageTokenLimits.defaults(), new org.springframework.core.env.StandardEnvironment());
            String test = "package example; import org.springframework.test.util.ReflectionTestUtils; class SubjectTest { Subject subject = new Subject(); void setup() { ReflectionTestUtils.setField(subject, \"value\", \"fixture\"); ReflectionTestUtils.getField(subject, \"value\"); } }";
            var fixture = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", test))), Path.of(arguments[0]));
            if (!fixture.reusable()) throw new AssertionError(fixture.provenance());
            var reflected = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code",
                    test.replace("getField", "invokeMethod")))), Path.of(arguments[0]));
            if (reflected.reusable()) throw new AssertionError("Unresolved reflective production invocation cannot be reused");
        }
    }
}
