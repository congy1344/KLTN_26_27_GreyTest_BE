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

/** Đọc cấu hình build của project để chọn cú pháp/API Java phù hợp khi sinh Unit Test. */
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

    private final ProjectRepository projects;

    public ProjectJavaVersionDetector(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * Trả về Java version hiệu lực trong build file gần root nhất.
     * Nếu project chưa có storage hoặc build file không khai báo version thì trả empty.
     */
    public Optional<JavaVersionInfo> detect(Long projectId) {
        if (projectId == null) return Optional.empty();
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            return Optional.empty();
        }
        Path root;
        try {
            root = Path.of(project.getStoragePath()).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (!Files.isDirectory(root)) return Optional.empty();

        List<Path> buildFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isBuildFile)
                    .sorted(Comparator.comparingInt((Path path) -> path.getNameCount() - root.getNameCount())
                            .thenComparing(path -> path.toString().toLowerCase()))
                    .forEach(buildFiles::add);
        } catch (IOException ignored) {
            return Optional.empty();
        }
        for (Path buildFile : buildFiles) {
            Optional<String> version = readVersion(buildFile);
            if (version.isPresent()) {
                return Optional.of(new JavaVersionInfo(version.get(), root.relativize(buildFile).toString()));
            }
        }
        return Optional.empty();
    }

    private boolean isBuildFile(Path path) {
        String name = path.getFileName().toString();
        return "pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name);
    }

    private Optional<String> readVersion(Path buildFile) {
        try {
            if (Files.size(buildFile) > MAX_BUILD_FILE_BYTES) return Optional.empty();
            String source = stripComments(Files.readString(buildFile), buildFile.getFileName().toString().equals("pom.xml"));
            if (buildFile.getFileName().toString().equals("pom.xml")) {
                return mavenVersion(source);
            }
            return gradleVersion(source);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
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
}