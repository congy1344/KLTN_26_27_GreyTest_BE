package com.greytest.service.generation;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.service.agent.PersistentSourceAnalysisService;

/** Giữ index của snapshot đã xác minh trong một run; hủy run nếu source hoặc classpath bị sửa. */
public final class SourceSnapshotSession implements AutoCloseable {
    private static final String KEY = SourceSnapshotSession.class.getName();
    private final Map<Path, Object> indexes = new HashMap<>();
    private final Map<Path, String> digests = new LinkedHashMap<>();
    private final Map<Path, String> externalFiles = new LinkedHashMap<>();
    private final Map<Path, BasicFileAttributes> identities = new LinkedHashMap<>();
    private final Map<WatchKey, Set<Path>> registrations = new LinkedHashMap<>();
    private final Map<Path, WatchKey> directories = new HashMap<>();
    private final List<Runnable> finalChecks = new ArrayList<>();
    private final String classpathConfiguration = System.getProperty("greytest.analysis.classpath-file", "").trim();
    private final WatchService watcher;
    private boolean invalid;
    private boolean monitoring = true;

    private SourceSnapshotSession() throws IOException { watcher = FileSystems.getDefault().newWatchService(); }

    public static <T> T withSession(Supplier<T> task) {
        if (current() != null) return task.get();
        SourceSnapshotSession session;
        try { session = new SourceSnapshotSession(); }
        catch (IOException | UnsupportedOperationException unavailable) {
            GenerationJobContext.increment("snapshot.fallback");
            return task.get();
        }
        try (var scope = GenerationJobContext.openScope(GenerationJobContext.actorUserId()); session) {
            return GenerationJobContext.withValue(KEY, session, () -> {
                T result = task.get();
                GenerationJobContext.measure("snapshot.verify", () -> { session.verifyContents(); return null; });
                return result;
            });
        }
    }

    private static SourceSnapshotSession current() { return GenerationJobContext.scopedValue(KEY); }

    /** Loader đã capture root; mất watcher thì mọi reuse cục bộ phải quay về đọc mới. */
    static boolean hasMonitoredSnapshot(Path root) {
        var session = current();
        if (session == null) return false;
        synchronized (session) { return session.monitoring && session.digests.containsKey(root); }
    }

    static <T> T index(Path root, Supplier<T> loader) {
        var session = current();
        if (session == null) return loader.get();
        synchronized (session) {
            if (!session.monitoring) return loader.get();
            session.check();
            if (session.indexes.containsKey(root)) {
                GenerationJobContext.increment("snapshot.indexHits");
                @SuppressWarnings("unchecked") T cached = (T) session.indexes.get(root);
                return cached;
            }
            GenerationJobContext.measure("snapshot.capture", () -> { session.capture(root); return null; });
            if (!session.monitoring) return loader.get();
            T index = loader.get();
            session.check();
            session.indexes.put(root, index);
            return index;
        }
    }

    /** Digest chỉ dùng trong session đã giám sát; ngoài session luôn đọc lại source như trước. */
    public static String sourceDigest(Path root, Supplier<String> loader) {
        var session = current();
        if (session == null) return loader.get();
        synchronized (session) {
            if (!session.monitoring) return loader.get();
            session.check();
            session.capture(root.toAbsolutePath().normalize());
            if (!session.monitoring) return loader.get();
            return session.digests.get(root.toAbsolutePath().normalize());
        }
    }

    static String contentIdentity(Path root) {
        var session = current();
        return session == null || !session.monitoring ? "verified-load" : session.digests.get(root);
    }

    public static void assertUnchanged() {
        var session = current();
        if (session != null) synchronized (session) { session.check(); }
    }

    /** WatchService có thể báo trễ; hash lại trước khi công bố stage thành công, không hash theo batch. */
    public static void verifyStage() {
        var session = current();
        if (session != null) GenerationJobContext.measure("snapshot.verify", () -> {
            session.verifyContents();
            return null;
        });
    }

    /** Theo dõi cả file chưa tồn tại để không bỏ sót config mới được tạo. */
    static void watchFile(Path file) {
        var session = current();
        if (session == null) return;
        synchronized (session) {
            Path absolute = file.toAbsolutePath().normalize();
            if (!session.monitoring || session.externalFiles.containsKey(absolute)) return;
            try {
                Path parent = absolute.getParent();
                while (parent != null && !Files.isDirectory(parent)) parent = parent.getParent();
                if (parent == null) throw new IOException("Missing parent directory");
                session.register(parent, absolute);
                session.externalFiles.put(absolute, fileDigest(absolute));
            } catch (IOException | UnsupportedOperationException | ProviderMismatchException unavailable) { session.disableReuse(); }
        }
    }

    static void watchTree(Path root) {
        var session = current();
        if (session != null) synchronized (session) {
            if (!session.monitoring) return;
            try { session.registerTree(root); }
            catch (IOException | UnsupportedOperationException | ProviderMismatchException unavailable) { session.disableReuse(); }
        }
    }

    static void verifyAtEnd(Runnable check) {
        var session = current();
        if (session != null) synchronized (session) { session.finalChecks.add(check); }
    }

    private void capture(Path root) {
        if (digests.containsKey(root)) return;
        try {
            if (root.getParent() != null) register(root.getParent(), root);
            registerTree(root);
            identities.put(root, Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException | ProviderMismatchException unavailable) {
            disableReuse();
            return;
        }
        try {
            digests.put(root, PersistentSourceAnalysisService.contentDigest(root));
            check();
        } catch (SnapshotChangedException changed) { throw changed; }
        catch (RuntimeException error) { throw changed("Cannot establish immutable source snapshot", error); }
    }

    private void disableReuse() {
        monitoring = false;
        indexes.clear();
        GenerationJobContext.increment("snapshot.fallback");
    }

    private void registerTree(Path root) throws IOException {
        GenerationJobContext.increment("snapshot.watchWalks");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("Symbolic source directory");
                register(dir, null);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) throw new IOException("Symbolic source file");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path directory, Path selected) throws IOException {
        WatchKey key = directories.get(directory);
        if (key == null) {
            key = directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            directories.put(directory, key);
            registrations.put(key, new HashSet<>());
        }
        Set<Path> paths = registrations.get(key);
        if (selected == null) { paths.clear(); paths.add(directory); }
        else if (!paths.contains(directory)) paths.add(selected);
    }

    private void check() {
        if (invalid || !classpathConfiguration.equals(System.getProperty("greytest.analysis.classpath-file", "").trim()))
            throw changed("Source snapshot or classpath changed during generation", null);
        for (WatchKey key; monitoring && (key = watcher.poll()) != null;) {
            Path directory = (Path) key.watchable();
            Set<Path> paths = registrations.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY && event.context() instanceof Path relative
                        && directories.containsKey(directory.resolve(relative))) continue;
                if (event.kind() == StandardWatchEventKinds.OVERFLOW || paths.contains(directory)
                        || event.context() instanceof Path relative && paths.contains(directory.resolve(relative)))
                    throw changed("Source snapshot or classpath changed during generation: " + directory + "/" + event.context()
                            + " (" + event.kind().name() + ")", null);
            }
            if (!key.reset()) throw changed("Source snapshot monitor lost its directory", null);
        }
        try {
            for (var entry : identities.entrySet()) {
                var now = Files.readAttributes(entry.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!now.isDirectory() || !Objects.equals(now.fileKey(), entry.getValue().fileKey()))
                    throw changed("Source snapshot identity changed", null);
            }
        } catch (IOException error) { throw changed("Source snapshot is missing", error); }
    }

    private synchronized void verifyContents() {
        check();
        for (var entry : digests.entrySet()) try {
            if (!entry.getValue().equals(PersistentSourceAnalysisService.contentDigest(entry.getKey())))
                throw changed("Source snapshot content changed during generation", null);
        } catch (SnapshotChangedException changed) { throw changed; }
        catch (RuntimeException error) { throw changed("Cannot verify source snapshot content", error); }
        for (var entry : externalFiles.entrySet()) try {
            if (!entry.getValue().equals(fileDigest(entry.getKey()))) throw changed("Source configuration changed", null);
        } catch (IOException error) { throw changed("Source configuration is missing", error); }
        for (Runnable verify : finalChecks) try { verify.run(); }
        catch (RuntimeException failure) { throw changed("Analysis classpath changed during generation", failure); }
        check();
    }

    private static String fileDigest(Path file) throws IOException {
        return Files.exists(file) ? GenerationFingerprintService.hash(Files.readAllBytes(file)) : "absent";
    }

    private SnapshotChangedException changed(String message, Throwable cause) {
        invalid = true;
        return new SnapshotChangedException(message, cause);
    }

    @Override public void close() {
        long started = System.nanoTime();
        try { watcher.close(); }
        catch (IOException error) { throw new IllegalStateException("Cannot close source snapshot monitor", error); }
        finally { GenerationJobContext.recordDuration("snapshot.close", System.nanoTime() - started); }
    }

    public static final class SnapshotChangedException extends LlmResponseException {
        private SnapshotChangedException(String message, Throwable cause) {
            super(message, cause, false, 0, ErrorClassification.SOURCE_ANALYSIS_UNRESOLVED);
        }
    }
}
