package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.service.agent.LlmStageTokenLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ParserDependencyClasspathTest {
    @TempDir Path root;
    private final GenerationFingerprintService service = new GenerationFingerprintService(new ObjectMapper(), null,
            LlmStageTokenLimits.defaults(), new MockEnvironment());

    @Test void configuredJarResolvesExternalParentAndJarChangeInvalidatesFingerprint() throws Exception {
        source("Patient", "package example; public class Patient { private String name; public String getName() { return name; } }");
        source("Gateway", "package example; class Gateway extends dep.ExternalBase { }");
        assertThat(fingerprint().reusable()).isFalse();
        Path first = jar("first", 1); Path second = jar("second", 2);
        Path classpath = root.resolve("classpath.json"); configure(classpath, first);
        String previous = System.getProperty("greytest.analysis.classpath-file");
        try {
            System.setProperty("greytest.analysis.classpath-file", classpath.toString());
            var before = fingerprint();
            assertThat(before.provenance()).doesNotContain("unresolved");
            assertThat(before.reusable()).isTrue();
            assertThat(service.outputFingerprint(Map.of("source_code", "package example; class Test { void run() { new Patient().getName(); } }"), root).reusable()).isFalse();
            configure(classpath, second);
            var after = fingerprint();
            assertThat(after.reusable()).isTrue();
            assertThat(after.value()).isNotEqualTo(before.value());
            configure(classpath, first);
            var originalPath = fingerprint();
            Files.copy(second, first, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThat(fingerprint().value()).isNotEqualTo(originalPath.value());
            Files.writeString(root.resolve("pom.xml"), "<project><artifactId>changed</artifactId></project>");
            assertThat(fingerprint().reusable()).isFalse();
        } finally {
            if (previous == null) System.clearProperty("greytest.analysis.classpath-file");
            else System.setProperty("greytest.analysis.classpath-file", previous);
        }
    }

    @Test void staleExternalBuildInputAndUnboundProjectAreHandledSeparately() throws Exception {
        Path dependency = jar("first", 1); Path manifest = root.resolve("classpath.json"); configure(manifest, dependency);
        Path settings = root.resolve("settings.xml"); Files.writeString(settings, "initial");
        ObjectMapper mapper = new ObjectMapper(); var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(manifest.toFile());
        config.set("externalInputs", mapper.valueToTree(Map.of(settings.toString(), GenerationFingerprintService.hash("initial"))));
        mapper.writeValue(manifest.toFile(), config);
        String previous = System.getProperty("greytest.analysis.classpath-file");
        try {
            System.setProperty("greytest.analysis.classpath-file", manifest.toString());
            assertThat(ParserDependencyClasspath.load(root, java.util.Set.of(".")).configured()).isTrue();
            Path other = root.resolve("another-project"); Files.createDirectories(other);
            assertThat(ParserDependencyClasspath.load(other, java.util.Set.of(".")).configured()).isFalse();
            Files.writeString(settings, "changed");
            assertThatThrownBy(() -> ParserDependencyClasspath.load(root, java.util.Set.of("."))).isInstanceOf(IllegalArgumentException.class);
        } finally { restore(previous); }
    }

    @Test void sourceBindingCannotShadowAnEarlierJarWithTheSameClass() throws Exception {
        Path earlier = jar("earlier", 1); Path binary = jar("bound", 2);
        Path library = root.resolve("lib"); Files.createDirectories(library); Files.writeString(library.resolve("source.txt"), "bound-source");
        Path manifest = root.resolve("classpath.json");
        new ObjectMapper().writeValue(manifest.toFile(), Map.of("format", "greytest-analysis-classpath-v1", "builds", Map.of(),
                "sourceRoots", java.util.List.of(root.toRealPath().toString()), "modules", Map.of(
                ".", Map.of("jars", java.util.List.of(earlier.toString(), binary.toString()), "sourceBindings", Map.of("lib", Map.of(
                        "sourceHash", ParserDependencyClasspath.sourceHash(root, library), "jarHash", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)))))),
                "lib", Map.of("jars", java.util.List.of(), "sourceBindings", Map.of()))));
        String previous = System.getProperty("greytest.analysis.classpath-file");
        try {
            System.setProperty("greytest.analysis.classpath-file", manifest.toString());
            assertThatThrownBy(() -> ParserDependencyClasspath.load(root, java.util.Set.of(".", "lib")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shadowed");
        } finally { restore(previous); }
    }

    private void restore(String previous) {
        if (previous == null) System.clearProperty("greytest.analysis.classpath-file");
        else System.setProperty("greytest.analysis.classpath-file", previous);
    }

    @Test void classpathDigestIsStableAcrossFreshJvmProcesses() throws Exception {
        Path manifest = root.resolve("classpath.json"); configure(manifest, jar("first", 1));
        var digests = new java.util.HashSet<String>();
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        for (int i = 0; i < 4; i++) {
            var process = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"), DigestProbe.class.getName(), root.toString(), manifest.toString())
                    .redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                assertThat(process.exitValue()).withFailMessage(output).isZero(); digests.add(output);
            } finally { process.destroyForcibly(); }
        }
        assertThat(digests).hasSize(1);
    }

    public static class DigestProbe {
        public static void main(String[] args) {
            System.setProperty("greytest.analysis.classpath-file", args[1]);
            System.out.println(ParserDependencyClasspath.load(Path.of(args[0]), java.util.Set.of(".")).digest());
        }
    }

    @Test void dispatchUsesTheSelectedModuleInsteadOfEveryModuleInTheArchive() throws Exception {
        Path parent = root.resolve("a/src/main/java/demo/Patient.java"); Files.createDirectories(parent.getParent());
        Files.writeString(parent, "package demo; public class Patient { public String getName() { return \"parent\"; } }");
        Path child = root.resolve("b/src/main/java/demo/Child.java"); Files.createDirectories(child.getParent());
        Files.writeString(child, "package demo; public class Child extends Patient { public String getName() { return \"child\"; } }");
        Path compiled = root.resolve("compiled"); Files.createDirectories(compiled);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", compiled.toString(), parent.toString())).isZero();
        Path binary = root.resolve("parent.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(binary))) {
            out.putNextEntry(new JarEntry("demo/Patient.class")); out.write(Files.readAllBytes(compiled.resolve("demo/Patient.class"))); out.closeEntry();
        }
        Path manifest = root.resolve("classpath.json");
        new ObjectMapper().writeValue(manifest.toFile(), Map.of("format", "greytest-analysis-classpath-v1", "builds", Map.of(),
                "sourceRoots", java.util.List.of(root.toRealPath().toString()), "modules", Map.of(
                "a", Map.of("jars", java.util.List.of(), "sourceBindings", Map.of()),
                "b", Map.of("jars", java.util.List.of(binary.toString()), "sourceBindings", Map.of("a", Map.of(
                        "sourceHash", ParserDependencyClasspath.sourceHash(root, root.resolve("a")),
                        "jarHash", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)))))))));
        String previous = System.getProperty("greytest.analysis.classpath-file");
        try {
            System.setProperty("greytest.analysis.classpath-file", manifest.toString());
            var output = Map.of("source_code", "package demo; class Test { void run() { Patient p = new Patient(); p.getName(); } }");
            assertThat(service.outputFingerprint(output, root, "a").reusable()).isTrue();
            assertThat(service.outputFingerprint(output, root, "b").reusable()).isFalse();
        } finally { restore(previous); }
    }

    private void configure(Path manifest, Path jar) throws Exception {
        new ObjectMapper().writeValue(manifest.toFile(), Map.of("format", "greytest-analysis-classpath-v1", "builds", Map.of(), "sourceRoots", java.util.List.of(root.toRealPath().toString()),
                "modules", Map.of(".", Map.of("jars", java.util.List.of(jar.toString()), "sourceBindings", Map.of()))));
    }

    private Path jar(String folder, int value) throws Exception {
        Path directory = root.resolve(folder); Path java = directory.resolve("dep/ExternalBase.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "package dep; public class ExternalBase { public int value() { return " + value + "; } }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), java.toString())).isZero();
        Path jar = directory.resolve("dependency.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("dep/ExternalBase.class")); output.write(Files.readAllBytes(directory.resolve("dep/ExternalBase.class"))); output.closeEntry();
        }
        return jar;
    }
    private GenerationFingerprintService.Fingerprint fingerprint() {
        return service.outputFingerprint(Map.of("source_code", "package example; class GeneratedTest { void test() { Patient p = new Patient(); p.getName(); } }"), root, ".");
    }
    private void source(String name, String code) throws Exception {
        Path file = root.resolve("src/main/java/example/" + name + ".java"); Files.createDirectories(file.getParent()); Files.writeString(file, code);
    }
}
