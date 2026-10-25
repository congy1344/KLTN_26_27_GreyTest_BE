package com.greytest.service.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.greytest.entity.Project;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.GenerationJobContext;

/** Đọc cấu hình build để chọn Java và test framework phù hợp khi sinh Unit Test. */
@Service
public class ProjectJavaVersionDetector {

    private static final long MAX_BUILD_FILE_BYTES = 2_000_000L;
    private static final Pattern MAVEN_PROPERTY = Pattern.compile(
            "<\\s*(maven\\.compiler\\.(?:release|source|target)|java\\.version)\\s*>\\s*([0-9]+(?:\\.[0-9]+)?)\\s*<",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAVEN_COMPILER_TAG = Pattern.compile(
            "<\\s*(release|source|target)\\s*>\\s*([0-9]+(?:\\.[0-9]+)?)\\s*<",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADLE_TOOLCHAIN = Pattern.compile(
            "languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\(\\s*['\\\"]?([0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADLE_COMPATIBILITY = Pattern.compile(
            "(?:sourceCompatibility|targetCompatibility)\\s*=\\s*(?:JavaVersion\\.VERSION_(?:1_)?|JavaVersion\\.toVersion\\(\\s*['\\\"]?(?:1\\.)?|['\\\"]?(?:1\\.)?)([0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPRING_BOOT_PARENT = Pattern.compile(
            "(?s)<parent>.*?<artifactId>\\s*spring-boot-starter-parent\\s*</artifactId>.*?"
                    + "<version>\\s*([0-9]+)\\.([0-9]+)[^<]*</version>.*?</parent>",
            Pattern.CASE_INSENSITIVE);

    private final ProjectRepository projects;

    public ProjectJavaVersionDetector(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * Trả về Java version hiệu lực trong build file gần root nhất.
     * Nếu project chưa có storage hoặc build file không khai báo version thì trả empty.
     */
    public Optional<JavaVersionInfo> detect(Long projectId) {
        Optional<Path> root = projectRoot(projectId);
        if (root.isEmpty()) return Optional.empty();
        return detectJavaVersion(root.get(), buildFiles(root.get()));
    }

    /** Phát hiện Java version theo module chứa source; batch xung đột version sẽ trả empty. */
    public Optional<JavaVersionInfo> detect(Long projectId, List<String> sourcePaths) {
        Optional<Path> root = projectRoot(projectId);
        return root.isEmpty() ? Optional.empty() : detectSnapshot(root.get(), sourcePaths);
    }

    /** Đọc Java version từ đúng snapshot đang sinh test, không thay đổi project storage. */
    public Optional<JavaVersionInfo> detectSnapshot(Path snapshot, List<String> sourcePaths) {
        Path root = snapshot.toAbsolutePath().normalize();
        List<Path> allBuildFiles = buildFiles(root);
        if (sourcePaths == null || sourcePaths.isEmpty()) return detectJavaVersion(root, allBuildFiles);
        List<JavaVersionInfo> detected = new ArrayList<>();
        for (String sourcePath : sourcePaths) {
            if (sourcePath == null || sourcePath.isBlank()) continue;
            Path source = root.resolve(sourcePath).normalize();
            if (!source.startsWith(root)) continue;
            List<Path> ancestors = allBuildFiles.stream()
                    .filter(buildFile -> source.startsWith(buildFile.getParent()))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
            detectJavaVersion(root, ancestors).ifPresent(detected::add);
        }
        if (detected.isEmpty()) return detectJavaVersion(root, allBuildFiles);
        String version = detected.get(0).version();
        return detected.stream().allMatch(info -> info.version().equals(version))
                ? Optional.of(detected.get(0)) : Optional.empty();
    }

    private Optional<JavaVersionInfo> detectJavaVersion(Path root, List<Path> candidates) {
        for (Path buildFile : candidates) {
            Optional<String> version = readVersion(buildFile);
            if (version.isPresent()) {
                return Optional.of(new JavaVersionInfo(version.get(), root.relativize(buildFile).toString()));
            }
        }
        return Optional.empty();
    }

    /** Phát hiện JUnit chính từ dependency/build convention; không suy đoán khi thiếu bằng chứng. */
    public Optional<TestFrameworkInfo> detectTestFramework(Long projectId) {
        Optional<Path> root = projectRoot(projectId);
        if (root.isEmpty()) return Optional.empty();
        return detectTestFramework(root.get(), buildFiles(root.get()));
    }

    /**
     * Phát hiện framework theo module chứa source đang sinh test.
     * Nếu một batch trải qua nhiều module dùng framework khác nhau thì không ép một framework sai.
     */
    public Optional<TestFrameworkInfo> detectTestFramework(Long projectId, List<String> sourcePaths) {
        Optional<Path> root = projectRoot(projectId);
        return root.isEmpty() ? Optional.empty() : detectSnapshotTestFramework(root.get(), sourcePaths);
    }

    /** Đọc JUnit từ build của snapshot ứng viên, không dùng build baseline. */
    public Optional<TestFrameworkInfo> detectSnapshotTestFramework(Path snapshot, List<String> sourcePaths) {
        Path root = snapshot.toAbsolutePath().normalize();
        List<Path> allBuildFiles = buildFiles(root);
        if (sourcePaths == null || sourcePaths.isEmpty()) return detectTestFramework(root, allBuildFiles);
        List<TestFrameworkInfo> detected = new ArrayList<>();
        for (String sourcePath : sourcePaths) {
            if (sourcePath == null || sourcePath.isBlank()) continue;
            Path source = root.resolve(sourcePath).normalize();
            if (!source.startsWith(root)) continue;
            List<Path> ancestors = allBuildFiles.stream()
                    .filter(buildFile -> source.startsWith(buildFile.getParent()))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
            detectTestFramework(root, ancestors).ifPresent(detected::add);
        }
        if (detected.isEmpty()) return detectTestFramework(root, allBuildFiles);
        TestFramework framework = detected.get(0).framework();
        return detected.stream().allMatch(info -> info.framework() == framework)
                ? Optional.of(detected.get(0)) : Optional.empty();
    }

    private Optional<TestFrameworkInfo> detectTestFramework(Path root, List<Path> candidates) {
        for (Path buildFile : candidates) {
            String source = readBuildSource(buildFile).orElse("");
            String relative = root.relativize(buildFile).toString();
            if (source.contains("org.junit.jupiter") || source.contains("junit-jupiter")
                    || source.contains("useJUnitPlatform")) {
                return Optional.of(new TestFrameworkInfo(TestFramework.JUNIT5, relative));
            }
            if (source.matches("(?s).*<groupId>\\s*junit\\s*</groupId>.*"
                    + "<artifactId>\\s*junit\\s*</artifactId>.*")
                    || source.contains("junit:junit")) {
                return Optional.of(new TestFrameworkInfo(TestFramework.JUNIT4, relative));
            }
            Matcher boot = SPRING_BOOT_PARENT.matcher(source);
            if (boot.find()) {
                int major = Integer.parseInt(boot.group(1));
                int minor = Integer.parseInt(boot.group(2));
                TestFramework framework = major > 2 || major == 2 && minor >= 2
                        ? TestFramework.JUNIT5 : TestFramework.JUNIT4;
                return Optional.of(new TestFrameworkInfo(framework, relative));
            }
        }
        return Optional.empty();
    }

    private Optional<Path> projectRoot(Long projectId) {
        if (projectId == null) return Optional.empty();
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            return Optional.empty();
        }
        try {
            Path root = Path.of(project.getStoragePath()).toAbsolutePath().normalize();
            return Files.isDirectory(root) ? Optional.of(root) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /** Cung cấp build thật của module và các cấp cha để AI đối chiếu dependency/exclusion, không đoán thư viện fixture. */
    public java.util.Map<String, String> buildEvidence(Long projectId, List<String> sourcePaths) {
        return projectRoot(projectId).map(root -> snapshotBuildEvidence(root, sourcePaths)).orElse(java.util.Map.of());
    }

    public java.util.Map<String, String> snapshotBuildEvidence(Path snapshot, List<String> sourcePaths) {
        Path root = snapshot.toAbsolutePath().normalize();
        if (sourcePaths == null || sourcePaths.isEmpty()) return java.util.Map.of();
        List<Path> sources = sourcePaths.stream().filter(path -> path != null && !path.isBlank())
                .map(path -> root.resolve(path).normalize()).filter(path -> path.startsWith(root)).toList();
        var evidence = new java.util.LinkedHashMap<String, String>();
        int remaining = 64_000;
        for (Path build : buildFiles(root)) {
            if (sources.stream().noneMatch(source -> source.startsWith(build.getParent()))) continue;
            String content = readBuildSource(build).orElse("");
            if (content.isBlank() || content.length() > remaining) continue;
            evidence.put(root.relativize(build).toString().replace('\\', '/'), content);
            remaining -= content.length();
        }
        return java.util.Collections.unmodifiableMap(evidence);
    }

    /** Chỉ dùng version có bằng chứng trong build của module; không lấy Mockito của GreyTest. */
    public Optional<MockitoVersionInfo> detectMockitoVersion(Long projectId, List<String> sourcePaths) {
        return projectRoot(projectId).flatMap(root -> detectSnapshotMockitoVersion(root, sourcePaths));
    }

    public Optional<MockitoVersionInfo> detectSnapshotMockitoVersion(Path snapshot, List<String> sourcePaths) {
        Path root = snapshot.toAbsolutePath().normalize();
        List<Path> builds = buildFiles(root);
        List<String> paths = sourcePaths == null || sourcePaths.isEmpty() ? List.of("pom.xml") : sourcePaths;
        List<MockitoVersionInfo> detected = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            Path source = root.resolve(path).normalize();
            if (!source.startsWith(root)) return Optional.empty();
            List<Path> ancestors = builds.stream().filter(file -> source.startsWith(file.getParent()))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList();
            Optional<MockitoVersionInfo> info = mockitoVersion(root, ancestors);
            if (info.isEmpty()) return Optional.empty();
            detected.add(info.get());
        }
        if (detected.isEmpty()) return Optional.empty();
        return detected.stream().allMatch(info -> info.major() == detected.get(0).major()
                && info.supportsNoInteractions() == detected.get(0).supportsNoInteractions())
                ? Optional.of(detected.get(0)) : Optional.empty();
    }

    private Optional<MockitoVersionInfo> mockitoVersion(Path root, List<Path> builds) {
        var properties = new java.util.HashMap<String, String>();
        var propertyFiles = new java.util.HashMap<String, Path>();
        var sources = new java.util.HashMap<Path, String>();
        for (Path build : builds) {
            String source = readBuildSource(build).orElse("");
            // Không có effective POM/profile activation: dùng API portable thay vì đoán version.
            if (source.matches("(?s).*<profiles\\b.*")) return Optional.empty();
            sources.put(build, source.replaceAll("(?s)<build\\b[^>]*>.*?</build>", "")
                    .replaceAll("(?s)<reporting\\b[^>]*>.*?</reporting>", ""));
        }
        for (int index = builds.size() - 1; index >= 0; index--) {
            Path build = builds.get(index);
            String source = sources.get(build);
            Matcher block = Pattern.compile("(?s)<properties>(.*?)</properties>").matcher(source);
            while (block.find()) {
                Matcher property = Pattern.compile("<([\\w.-]+)>\\s*([^<>]+?)\\s*</\\1>").matcher(block.group(1));
                while (property.find()) {
                    properties.put(property.group(1), property.group(2).trim()); propertyFiles.put(property.group(1), build);
                }
            }
            Matcher gradleProperty = Pattern.compile("(?:val |def |ext\\.)?([\\w]+)\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(source);
            while (gradleProperty.find()) properties.put(gradleProperty.group(1), gradleProperty.group(2));
        }
        for (Path build : builds) {
            String source = sources.get(build);
            List<String> versions = new ArrayList<>();
            Matcher dependency = Pattern.compile("(?s)<dependency>(.*?)</dependency>").matcher(source);
            while (dependency.find()) {
                String value = dependency.group(1);
                if (!value.matches("(?s).*<groupId>\\s*org\\.mockito\\s*</groupId>.*")
                        || !value.matches("(?s).*<artifactId>\\s*mockito-(core|inline|junit-jupiter|bom)\\s*</artifactId>.*")) continue;
                Matcher version = Pattern.compile("<version>\\s*([^<]+)\\s*</version>").matcher(value);
                if (version.find()) versions.add(version.group(1).trim());
            }
            Matcher gradle = Pattern.compile("org\\.mockito:mockito-(?:core|inline|junit-jupiter|bom):([^'\"\\s)]+)").matcher(source);
            while (gradle.find()) versions.add(gradle.group(1));
            if (!versions.isEmpty()) {
                List<String> resolved = versions.stream().map(version -> resolveMockitoVersion(version, properties)).toList();
                if (resolved.stream().anyMatch(version -> !version.matches("[1-9][0-9]*\\.[0-9]+(?:[.\\w-]*)"))) return Optional.empty();
                var info = new MockitoVersionInfo(resolved.get(0), root.relativize(build).toString());
                return resolved.stream().map(version -> new MockitoVersionInfo(version, info.buildFile()))
                        .allMatch(other -> other.major() == info.major() && other.supportsNoInteractions() == info.supportsNoInteractions())
                        ? Optional.of(info) : Optional.empty();
            }
        }
        if (properties.containsKey("mockito.version")) {
            String version = resolveMockitoVersion(properties.get("mockito.version"), properties);
            return version.matches("[1-9][0-9]*\\.[0-9]+(?:[.\\w-]*)")
                    ? Optional.of(new MockitoVersionInfo(version, root.relativize(propertyFiles.get("mockito.version")).toString())) : Optional.empty();
        }
        for (Path build : builds) {
            Matcher boot = SPRING_BOOT_PARENT.matcher(sources.get(build));
            if (boot.find() && "2".equals(boot.group(1)) && Integer.parseInt(boot.group(2)) <= 1)
                return Optional.of(new MockitoVersionInfo("2.x", root.relativize(build).toString()));
        }
        return Optional.empty();
    }

    private String resolveMockitoVersion(String value, java.util.Map<String, String> properties) {
        for (int step = 0; step < 10; step++) {
            String key = value.startsWith("${") && value.endsWith("}") ? value.substring(2, value.length() - 1)
                    : value.startsWith("$") ? value.substring(1) : null;
            if (key == null || !properties.containsKey(key)) break;
            value = properties.get(key);
        }
        return value;
    }

    private List<Path> buildFiles(Path root) {
        return GenerationJobContext.memoize(new BuildFilesKey(root.toAbsolutePath().normalize()),
                () -> scanBuildFiles(root));
    }

    private record BuildFilesKey(Path root) { }
    private record BuildSourceKey(Path file) { }

    private List<Path> scanBuildFiles(Path root) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isBuildFile)
                    .sorted(Comparator.comparingInt((Path path) -> path.getNameCount() - root.getNameCount())
                            .thenComparing(path -> path.toString().toLowerCase()))
                    .forEach(result::add);
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private Optional<String> readBuildSource(Path buildFile) {
        return GenerationJobContext.memoize(new BuildSourceKey(buildFile.toAbsolutePath().normalize()),
                () -> loadBuildSource(buildFile));
    }

    private Optional<String> loadBuildSource(Path buildFile) {
        try {
            if (Files.size(buildFile) > MAX_BUILD_FILE_BYTES) return Optional.empty();
            return Optional.of(stripComments(Files.readString(buildFile),
                    buildFile.getFileName().toString().equals("pom.xml")));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean isBuildFile(Path path) {
        String name = path.getFileName().toString();
        return "pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name);
    }

    private Optional<String> readVersion(Path buildFile) {
        Optional<String> source = readBuildSource(buildFile);
        if (source.isEmpty()) return Optional.empty();
        return buildFile.getFileName().toString().equals("pom.xml")
                ? mavenVersion(source.get()) : gradleVersion(source.get());
    }

    private String stripComments(String source, boolean maven) {
        String uncommented = source.replaceAll("(?s)<!--.*?-->", " ");
        if (!maven) {
            uncommented = uncommented.replaceAll("(?s)/\\*.*?\\*/", " ");
            uncommented = uncommented.replaceAll("(?m)//.*$", " ");
        }
        return uncommented;
    }

    private Optional<String> mavenVersion(String source) {
        String release = firstMavenValue(source, "maven.compiler.release");
        if (release == null) release = firstTagValue(source, "release");
        if (release == null) release = firstMavenValue(source, "maven.compiler.source");
        if (release == null) release = firstTagValue(source, "source");
        if (release == null) release = firstMavenValue(source, "java.version");
        String target = firstMavenValue(source, "maven.compiler.target");
        if (target == null) target = firstTagValue(source, "target");
        if (release == null && target == null) return Optional.empty();
        if (release == null) release = target;
        if (target != null) release = lowerVersion(release, target);
        String normalized = normalizeVersion(release);
        return normalized == null ? Optional.empty() : Optional.of(normalized);
    }

    private String firstMavenValue(String source, String property) {
        Matcher matcher = MAVEN_PROPERTY.matcher(source);
        while (matcher.find()) {
            if (property.equalsIgnoreCase(matcher.group(1))) return matcher.group(2);
        }
        return null;
    }

    private String firstTagValue(String source, String tag) {
        Matcher matcher = MAVEN_COMPILER_TAG.matcher(source);
        while (matcher.find()) {
            if (tag.equalsIgnoreCase(matcher.group(1))) return matcher.group(2);
        }
        return null;
    }

    private Optional<String> gradleVersion(String source) {
        List<String> versions = new ArrayList<>();
        Matcher toolchain = GRADLE_TOOLCHAIN.matcher(source);
        while (toolchain.find()) addVersion(versions, toolchain.group(1));
        Matcher compatibility = GRADLE_COMPATIBILITY.matcher(source);
        while (compatibility.find()) addVersion(versions, compatibility.group(1));
        if (versions.isEmpty()) return Optional.empty();
        String version = versions.get(0);
        for (int index = 1; index < versions.size(); index++) version = lowerVersion(version, versions.get(index));
        return Optional.of(version);
    }

    private void addVersion(List<String> versions, String value) {
        String normalized = normalizeVersion(value);
        if (normalized != null) versions.add(normalized);
    }

    private String lowerVersion(String first, String second) {
        return versionNumber(first) <= versionNumber(second) ? first : second;
    }

    private String normalizeVersion(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("1.")) normalized = normalized.substring(2);
        if (!normalized.matches("\\d{1,2}")) return null;
        int number = Integer.parseInt(normalized);
        return number >= 1 && number <= 99 ? normalized : null;
    }

    private int versionNumber(String value) {
        try {
            return Integer.parseInt(normalizeVersion(value));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    public record JavaVersionInfo(String version, String buildFile) {
    }

    public enum TestFramework { JUNIT4, JUNIT5 }

    public record TestFrameworkInfo(TestFramework framework, String buildFile) {
    }

    public record MockitoVersionInfo(String version, String buildFile) {
        public int major() { return Integer.parseInt(version.split("\\.")[0]); }
        public boolean supportsNoInteractions() {
            return major() > 3 || major() == 3 && !version.matches("3\\.0(?:\\.0)?(?:[-A-Za-z].*)?");
        }
    }
}
