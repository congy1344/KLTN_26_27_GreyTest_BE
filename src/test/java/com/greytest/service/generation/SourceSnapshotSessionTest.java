package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.GenerationContextBuilder;

class SourceSnapshotSessionTest {
    @TempDir Path root;

    @Test void nestedBatchesAndParallelWorkersReadSourceOnlyOnce() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject { int value() { return 1; } }");
        try (var scope = GenerationJobContext.openScope(null)) {
            SourceSnapshotSession.withSession(() -> {
                var first = SourceDependencyIndex.load(root);
                var workers = Executors.newFixedThreadPool(4);
                try {
                    var futures = java.util.stream.IntStream.range(0, 12).mapToObj(i -> workers.submit(
                            GenerationJobContext.wrap(() -> GenerationContextBuilder.withInputSnapshot(() -> SourceDependencyIndex.load(root)))::get)).toList();
                    for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isSameAs(first);
                } catch (Exception error) { throw new AssertionError(error); }
                finally { workers.shutdownNow(); }
                return null;
            });
            assertThat(GenerationJobContext.metrics()).containsEntry("dependency.walks", 1L)
                    .containsEntry("dependencySourceReads", 1L);
        }
    }

    @Test void sameSizeEditWithRestoredTimestampCannotReuseIndex() throws Exception {
        Path source = write("src/main/java/example/Subject.java", "package example; class Subject { int value() { return 1; } }");
        var timestamp = Files.getLastModifiedTime(source);
        assertThatThrownBy(() -> SourceSnapshotSession.withSession(() -> {
            SourceDependencyIndex.load(root);
            try {
                Files.writeString(source, "package example; class Subject { int value() { return 2; } }");
                Files.setLastModifiedTime(source, timestamp);
            } catch (Exception error) { throw new AssertionError(error); }
            return SourceDependencyIndex.load(root);
        })).isInstanceOf(SourceSnapshotSession.SnapshotChangedException.class);
    }

    @Test void retryCreatesNewSessionAndRebuildsAfterBodyEdit() throws Exception {
        Path file = write("src/main/java/example/Subject.java", "package example; class Subject { int value() { return 1; } }");
        var first = SourceSnapshotSession.withSession(() -> SourceDependencyIndex.load(root));
        Files.writeString(file, "package example; class Subject { int value() { return 2; } }");
        var second = SourceSnapshotSession.withSession(() -> SourceDependencyIndex.load(root));
        assertThat(second).isNotSameAs(first);
    }

    @Test void classpathConfigurationSwitchStopsSessionWithoutLlmRetry() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject {}");
        String previous = System.getProperty("greytest.analysis.classpath-file");
        try {
            assertThatThrownBy(() -> SourceSnapshotSession.withSession(() -> {
                SourceDependencyIndex.load(root);
                System.setProperty("greytest.analysis.classpath-file", root.resolve("other-classpath.json").toString());
                return SourceDependencyIndex.load(root);
            })).isInstanceOfSatisfying(SourceSnapshotSession.SnapshotChangedException.class, error -> {
                assertThat(error.isRetryable()).isFalse();
                assertThat(error.getClassification()).isEqualTo(com.greytest.service.agent.LlmResponseException.ErrorClassification.SOURCE_ANALYSIS_UNRESOLVED);
            });
        } finally {
            if (previous == null) System.clearProperty("greytest.analysis.classpath-file");
            else System.setProperty("greytest.analysis.classpath-file", previous);
        }
    }

    @Test void externalClasspathInputEditIsDetectedEvenWhenTimestampIsRestored() throws Exception {
        Path sourceRoot = root.resolve("snapshot");
        Files.createDirectories(sourceRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("src/main/java/Subject.java"), "class Subject {}");
        Path external = write("settings.xml", "first");
        var timestamp = Files.getLastModifiedTime(external);
        assertThatThrownBy(() -> SourceSnapshotSession.withSession(() -> {
            SourceDependencyIndex.load(sourceRoot);
            SourceSnapshotSession.watchFile(external);
            try { Files.writeString(external, "other"); Files.setLastModifiedTime(external, timestamp); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return null;
        })).isInstanceOf(SourceSnapshotSession.SnapshotChangedException.class);
    }

    @Test void sourceBuildAndResourceEditsInvalidateSession() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject {}");
        for (String file : List.of("pom.xml", "lombok.config", "src/main/java/example/Added.java", "src/main/resources/application.yml")) {
            assertThatThrownBy(() -> SourceSnapshotSession.withSession(() -> {
                SourceDependencyIndex.load(root);
                try { write(file, "changed"); } catch (Exception error) { throw new AssertionError(error); }
                return SourceDependencyIndex.load(root);
            })).isInstanceOf(SourceSnapshotSession.SnapshotChangedException.class);
            Files.delete(root.resolve(file));
        }
    }

    @Test void unreadableSourceAtFinalVerificationIsClassifiedAsSnapshotFailure() throws Exception {
        write("src/main/java/example/Subject.java", "package example; class Subject {}");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        try (var digest = org.mockito.Mockito.mockStatic(com.greytest.service.agent.PersistentSourceAnalysisService.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            digest.when(() -> com.greytest.service.agent.PersistentSourceAnalysisService.contentDigest(root)).thenAnswer(call -> {
                if (reads.incrementAndGet() > 1) throw new com.greytest.service.agent.LlmResponseException("Injected source read failure");
                return call.callRealMethod();
            });
            assertThatThrownBy(() -> SourceSnapshotSession.withSession(() -> SourceDependencyIndex.load(root)))
                    .isInstanceOf(SourceSnapshotSession.SnapshotChangedException.class)
                    .hasMessageContaining("Cannot verify source snapshot");
        }
    }

    @Test void unsupportedDirectoryMonitoringFallsBackToVerifiedReads() throws Exception {
        var uri = java.net.URI.create("jar:" + root.resolve("source.zip").toUri());
        try (var archive = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"));
                var scope = GenerationJobContext.openScope(null)) {
            Path sourceRoot = archive.getPath("/");
            Files.createDirectories(sourceRoot.resolve("src/main/java/example"));
            Files.writeString(sourceRoot.resolve("src/main/java/example/Subject.java"), "package example; class Subject {}");
            SourceSnapshotSession.withSession(() -> {
                SourceDependencyIndex.load(sourceRoot);
                SourceDependencyIndex.load(sourceRoot);
                return null;
            });
            assertThat(GenerationJobContext.metrics()).containsEntry("snapshot.fallback", 1L)
                    .containsEntry("dependency.walks", 2L).containsEntry("dependencySourceReads", 2L);
        }
    }

    private Path write(String relative, String source) throws Exception {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, source); return file;
    }
}
