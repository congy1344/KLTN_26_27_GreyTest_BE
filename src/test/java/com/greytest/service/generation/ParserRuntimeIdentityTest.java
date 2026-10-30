package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParserRuntimeIdentityTest {
    @TempDir Path root;

    @Test void applicationRepackagingDoesNotChangeParserLibrariesButRealLibraryChangeDoes() throws Exception {
        Path old = boot("old.jar", "old mapper", "library-v1");
        Path repackaged = boot("repackaged.jar", "new mapper", "library-v1");
        Path changed = boot("changed.jar", "new mapper", "library-v2");
        String before = identity(old);
        assertThat(identity(repackaged)).isEqualTo(before);
        assertThat(identity(changed)).isNotEqualTo(before);
    }

    @Test void archivedJarMustProveBothLegacyFingerprintAndUnchangedLibrariesAndJdk() throws Exception {
        Path old = boot("old.jar", "old mapper", "library-v1");
        Path current = boot("current.jar", "new mapper", "library-v1");
        Path changed = boot("changed.jar", "new mapper", "library-v2");
        String jdk = "17:vendor";
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(current.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(Files.readAllBytes(old));
        String legacy = GenerationFingerprintService.hash("default-runtime:" + jdk + ":" + java.util.HexFormat.of().formatHex(digest.digest()));
        String fresh = GenerationFingerprintService.hash("default-runtime:" + jdk + ":" + identity(current));
        assertThat(ParserDependencyClasspath.compatibleArchivedRuntime(legacy, fresh, current, old, jdk)).isTrue();
        assertThat(ParserDependencyClasspath.compatibleArchivedRuntime("unproven", fresh, current, old, jdk)).isFalse();
        assertThat(ParserDependencyClasspath.compatibleArchivedRuntime(legacy, fresh, current, changed, jdk)).isFalse();
        assertThat(ParserDependencyClasspath.compatibleArchivedRuntime(legacy, fresh, current, old, "new-jdk")).isFalse();
        assertThat(ParserDependencyClasspath.compatibleArchivedRuntime(legacy, "configured-classpath", current, old, jdk)).isFalse();
        assertThat(ParserDependencyClasspath.compatibleArchivedRuntime(legacy, fresh, current, root.resolve("missing.jar"), jdk)).isFalse();
    }

    private String identity(Path jar) throws Exception {
        var type = Class.forName(ParserDependencyClasspath.class.getName() + "$RuntimeIdentity");
        var capture = type.getDeclaredMethod("capture"); capture.setAccessible(true);
        String previous = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", jar.toString());
            return (String) capture.invoke(null);
        } finally { System.setProperty("java.class.path", previous); }
    }

    private Path boot(String name, String app, String library) throws Exception {
        Path path = root.resolve(name);
        try (var zip = new JarOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new JarEntry("BOOT-INF/classes/")); zip.closeEntry();
            zip.putNextEntry(new JarEntry("BOOT-INF/classes/com/greytest/mapper/SourceUpdateMapper.class"));
            zip.write(app.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new JarEntry("BOOT-INF/lib/model.jar"));
            zip.write(library.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return path;
    }
}
