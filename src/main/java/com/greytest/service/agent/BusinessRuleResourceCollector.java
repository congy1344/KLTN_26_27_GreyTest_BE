package com.greytest.service.agent;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;
import org.w3c.dom.Element;
import org.xml.sax.helpers.DefaultHandler;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto;
import com.greytest.service.ServiceScopeResolver;

/** Thu thập mapper và khóa cấu hình được Java tham chiếu, chỉ trong module source liên quan. */
final class BusinessRuleResourceCollector {
    private static final int MAX_FILE_BYTES = 128_000;
    private static final int MAX_CONTEXT_CHARS = 32_000;
    private static final int MAX_RESOURCE_FILES = 200;
    private static final int MAX_WARNING_CHARS = 4_000;
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}:]+)(?::[^}]*)?}");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)password|passwd|pwd|secret|token|credential|api[-_.]?key|private[-_.]?key|authorization");
    private static final Pattern LOMBOK_SETTING = Pattern.compile(
            "(?:clear\\s+)?(?:config\\.stopBubbling|lombok\\.getter\\.noIsPrefix|lombok\\.accessors\\.(?:fluent|chain|prefix|capitalization))(?:\\s*(?:\\+=|-=|=).*)?");
    record Evidence(List<ResourceEvidenceDto> resources, List<String> warnings) {}
    private record MapperEvidence(String path, String source, Element mapper) {}
    private record SqlFragment(MapperEvidence mapper, Element sql) {}
    private record IncludeReference(MapperEvidence mapper, String reference) {}

    private final List<ResourceEvidenceDto> resources = new ArrayList<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private int contextChars;
    private int warningChars;
    private int indexedFiles;
    private record ResourceClassShape(String path, String qualifiedName, String source) {}
    private record ResourceReadKey(Path root, List<ResourceClassShape> classes) {}

    static Evidence collect(Path sourceRoot, List<ClassContextDto> classes) {
        var shape = classes.stream().map(type -> new ResourceClassShape(type.filePath(), type.qualifiedName(), type.sourceCode()))
                .sorted(Comparator.comparing(ResourceClassShape::path, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(type -> String.valueOf(type.qualifiedName())).thenComparing(type -> String.valueOf(type.source())))
                .toList();
        return GenerationContextBuilder.inputSnapshot(new ResourceReadKey(sourceRoot == null ? null : sourceRoot.toAbsolutePath().normalize(), shape),
                () -> collectFresh(sourceRoot, classes));
    }

    private static Evidence collectFresh(Path sourceRoot, List<ClassContextDto> classes) {
        var collector = new BusinessRuleResourceCollector();
        if (classes.isEmpty()) return new Evidence(List.of(), List.of());
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return new Evidence(List.of(), List.of("Source resource path is unavailable; mapper/configuration evidence is not verified."));
        }
        collector.read(sourceRoot, classes);
        return new Evidence(List.copyOf(collector.resources), List.copyOf(collector.warnings));
    }

    private void read(Path sourceRoot, List<ClassContextDto> classes) {
        try {
            Path root = sourceRoot.toRealPath();
            collectLombokConfiguration(root, classes);
            Map<String, List<ClassContextDto>> modules = new TreeMap<>();
            classes.forEach(type -> modules.computeIfAbsent(ServiceScopeResolver.modulePath(type.filePath()),
                    ignored -> new ArrayList<>()).add(type));
            for (var module : modules.entrySet()) readModule(root, module.getKey(), module.getValue());
        } catch (IOException | RuntimeException exception) {
            warn("Cannot read related source resources; do not infer missing mapper/configuration behavior.");
        }
    }

    private void collectLombokConfiguration(Path root, List<ClassContextDto> classes) {
        Map<Path, Boolean> visited = new LinkedHashMap<>();
        for (ClassContextDto type : classes) {
            if (type.filePath() == null || type.filePath().isBlank()) continue;
            Path source = root.resolve(type.filePath().replace('\\', '/')).normalize();
            if (!source.startsWith(root)) {
                warn("Source path escapes source root; Lombok configuration is not verified.");
                continue;
            }
            Path directory = source.getParent();
            int depth = 0;
            // Chỉ đọc cấu hình tổ tiên của class trong snapshot; không dùng cấu hình máy chủ ngoài project.
            while (directory != null && directory.startsWith(root) && depth++ < 64) {
                Path config = directory.resolve("lombok.config");
                boolean stop = visited.computeIfAbsent(config, path -> readLombokConfiguration(root, path));
                if (stop) break;
                directory = directory.getParent();
            }
            if (depth >= 64) warn("Lombok configuration ancestor scan limit reached; inherited settings are not verified.");
        }
    }

    private boolean readLombokConfiguration(Path root, Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        String path = root.relativize(file).toString().replace('\\', '/');
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root)
                    || Files.size(file) > MAX_FILE_BYTES || indexedFiles >= MAX_RESOURCE_FILES) {
                warn("Lombok configuration omitted (unsafe path, size or scan limit): " + path);
                return true;
            }
            indexedFiles++;
            boolean stop = false;
            StringBuilder content = new StringBuilder();
            for (String raw : Files.readString(file).lines().toList()) {
                String line = raw.trim();
                if (line.startsWith("import ")) {
                    warn("Lombok configuration imports are not resolved: " + path);
                } else if (LOMBOK_SETTING.matcher(line).matches()) {
                    content.append(line).append('\n');
                    if (line.matches("config\\.stopBubbling\\s*=\\s*true")) stop = true;
                    if (line.matches("config\\.stopBubbling\\s*=\\s*false")) stop = false;
                }
            }
            if (!content.isEmpty()) add("LOMBOK_CONFIGURATION", path, content.toString());
            return stop;
        } catch (IOException | RuntimeException exception) {
            warn("Cannot safely read Lombok configuration " + path + "; generated accessors are not verified.");
            return true;
        }
    }

    private void readModule(Path root, String module, List<ClassContextDto> classes) throws IOException {
        Set<String> namespaces = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        classes.forEach(type -> {
            namespaces.add(type.qualifiedName());
            configurationReferences(type.sourceCode(), keys, prefixes);
        });
        Path directory = root.resolve(module).resolve("src/main/resources").normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!directory.toRealPath().startsWith(root)) {
            warn("Resource directory escapes source root: " + module);
            return;
        }
        Set<Path> files = new java.util.TreeSet<>(Comparator.comparing(Path::toString));
        index(directory, files);
        List<MapperEvidence> mappers = new ArrayList<>();
        for (Path file : files) readFile(root, file, keys, prefixes, mappers);
        collectMappers(mappers, namespaces);
    }

    private void index(Path directory, Set<Path> files) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || !supported(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (indexedFiles >= MAX_RESOURCE_FILES) {
                    warn("Resource scan limit reached; some resources are not verified.");
                    return FileVisitResult.TERMINATE;
                }
                files.add(file);
                indexedFiles++;
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean supported(String name) {
        return name.endsWith(".xml") || name.matches("application(?:-[A-Za-z0-9_-]+)?\\.(?:yml|yaml|properties)");
    }

    private void readFile(Path root, Path file, Set<String> keys, Set<String> prefixes, List<MapperEvidence> mappers) {
        String path = root.relativize(file).toString().replace('\\', '/');
        try {
            if (!file.toRealPath().startsWith(root) || Files.size(file) > MAX_FILE_BYTES) {
                warn("Resource omitted (outside source root or too large): " + path);
                return;
            }
            String source = Files.readString(file);
            if (file.getFileName().toString().endsWith(".xml")) {
                MapperEvidence mapper = readMapper(path, source);
                if (mapper != null) mappers.add(mapper);
            } else if (!keys.isEmpty() || !prefixes.isEmpty()) {
                readConfiguration(path, source, keys, prefixes);
            }
        } catch (Exception exception) {
            warn("Cannot safely read resource " + path + "; its behavior is not verified.");
        }
    }

    private MapperEvidence readMapper(String path, String source) throws Exception {
        // MyBatis có DOCTYPE chuẩn; cho phép khai báo đó nhưng tuyệt đối không đọc DTD/entity bên ngoài.
        if (source.contains("<!ENTITY")) throw new IllegalArgumentException("Entity declarations are not supported");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler());
        var mapper = builder.parse(new InputSource(new StringReader(source))).getDocumentElement();
        return mapper != null && mapper.getTagName().equals("mapper")
                ? new MapperEvidence(path, source, mapper) : null;
    }

    private void collectMappers(List<MapperEvidence> mappers, Set<String> namespaces) {
        Map<String, List<SqlFragment>> fragments = new LinkedHashMap<>();
        for (MapperEvidence mapper : mappers) {
            for (var child = mapper.mapper().getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element sql && sql.getTagName().equals("sql")) {
                    String key = mapper.mapper().getAttribute("namespace") + "." + sql.getAttribute("id");
                    fragments.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new SqlFragment(mapper, sql));
                }
            }
        }
        Set<String> includedPaths = new LinkedHashSet<>();
        Set<String> visitedFragments = new LinkedHashSet<>();
        ArrayDeque<IncludeReference> pending = new ArrayDeque<>();
        for (MapperEvidence mapper : mappers) {
            if (!namespaces.contains(mapper.mapper().getAttribute("namespace"))) continue;
            includeMapper(mapper, includedPaths);
            enqueueIncludes(mapper, mapper.mapper(), pending);
        }
        // Duyệt fragment trong đúng module; đánh dấu đã thăm để include vòng không lặp vô hạn.
        while (!pending.isEmpty()) {
            IncludeReference include = pending.removeFirst();
            String reference = include.reference();
            if (reference.isBlank() || reference.contains("${")) {
                warn("Mapper " + include.mapper().path() + " has blank/dynamic SQL fragment reference " + reference + ".");
                continue;
            }
            String key = reference.contains(".") ? reference
                    : include.mapper().mapper().getAttribute("namespace") + "." + reference;
            if (!visitedFragments.add(key)) continue;
            List<SqlFragment> matches = fragments.getOrDefault(key, List.of());
            if (matches.size() != 1) {
                warn("Mapper " + include.mapper().path() + " has " + (matches.isEmpty() ? "missing" : "ambiguous")
                        + " SQL fragment evidence for " + key + ".");
                continue;
            }
            SqlFragment fragment = matches.get(0);
            includeMapper(fragment.mapper(), includedPaths);
            enqueueIncludes(fragment.mapper(), fragment.sql(), pending);
        }
    }

    private void includeMapper(MapperEvidence mapper, Set<String> includedPaths) {
        if (includedPaths.add(mapper.path())) add("MYBATIS_MAPPER", mapper.path(), mapper.source());
    }

    private void enqueueIncludes(MapperEvidence mapper, Element scope, ArrayDeque<IncludeReference> pending) {
        var includes = scope.getElementsByTagName("include");
        for (int index = 0; index < includes.getLength(); index++) {
            pending.addLast(new IncludeReference(mapper, ((Element) includes.item(index)).getAttribute("refid").trim()));
        }
    }

    private void configurationReferences(String source, Set<String> keys, Set<String> prefixes) {
        if (source == null) return;
        var unit = com.greytest.service.GenerationJobContext.memoize(
                "resource-configuration-ast-v1:" + GenerationSourceFingerprint.hash(source), () -> {
                    com.greytest.service.GenerationJobContext.increment("resourceEvidenceAstParses");
                    return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                            .parse(source).getResult();
                });
        unit.ifPresent(parsed -> parsed.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).forEach(annotation -> {
            String name = annotation.getName().getIdentifier();
            if (name.equals("Value")) {
                annotation.findAll(com.github.javaparser.ast.expr.StringLiteralExpr.class).forEach(literal -> {
                    var matcher = PROPERTY.matcher(literal.asString());
                    while (matcher.find()) keys.add(matcher.group(1));
                });
            } else if (name.equals("ConfigurationProperties")) {
                if (annotation.isSingleMemberAnnotationExpr() && annotation.asSingleMemberAnnotationExpr().getMemberValue().isStringLiteralExpr()) {
                    prefixes.add(annotation.asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().asString());
                } else if (annotation.isNormalAnnotationExpr()) {
                    annotation.asNormalAnnotationExpr().getPairs().stream()
                            .filter(pair -> Set.of("prefix", "value").contains(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr())
                            .forEach(pair -> prefixes.add(pair.getValue().asStringLiteralExpr().asString()));
                }
            }
        }));
    }

    private void readConfiguration(String path, String source, Set<String> keys, Set<String> prefixes) throws IOException {
        if (path.endsWith(".properties")) {
            int document = 0;
            for (String part : source.split("(?m)^(?:#|!)---\\r?$", -1)) {
                Properties properties = new Properties();
                properties.load(new StringReader(part));
                Map<String, Object> values = new TreeMap<>();
                properties.forEach((key, value) -> values.put(key.toString(), value));
                addConfiguration(path, ++document, values, keys, prefixes);
            }
            return;
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(MAX_FILE_BYTES);
        int document = 0;
        for (Object yaml : new Yaml(new SafeConstructor(options)).loadAll(source)) {
            Map<String, Object> flattened = new TreeMap<>();
            flatten("", yaml, flattened, 0);
            addConfiguration(path, ++document, flattened, keys, prefixes);
        }
    }

    private void flatten(String prefix, Object value, Map<String, Object> values, int depth) {
        if (depth > 20 || values.size() > 2_000) throw new IllegalArgumentException("Configuration nesting exceeds evidence limits");
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                if (key instanceof String name) flatten(prefix.isEmpty() ? name : prefix + "." + name, nested, values, depth + 1);
            });
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) flatten(prefix + "[" + index + "]", list.get(index), values, depth + 1);
        } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            values.put(prefix, value);
        }
    }

    private void addConfiguration(String path, int document, Map<String, Object> values, Set<String> keys, Set<String> prefixes) {
        StringBuilder content = new StringBuilder();
        values.forEach((key, value) -> {
            if ((keys.contains(key) || keys.stream().anyMatch(reference -> key.startsWith(reference + "["))
                    || prefixes.stream().anyMatch(prefix -> !prefix.isBlank() && (key.startsWith(prefix + ".") || key.startsWith(prefix + "["))))
                    && !SENSITIVE.matcher(key).find() && !value.toString().matches(".*://[^\\s]*@.*")) {
                content.append(key).append(" = ").append(value).append('\n');
            }
        });
        if (!content.isEmpty()) {
            Object profile = values.get("spring.config.activate.on-profile");
            if (profile == null) profile = values.get("spring.profiles");
            add("CONFIGURATION", path, "Document " + document + "; profile=" + (profile == null ? "unspecified" : profile)
                    + ". Values are declarations; active profile/environment overrides are unknown.\n" + content);
        }
    }

    private void add(String kind, String path, String content) {
        if (contextChars + content.length() > MAX_CONTEXT_CHARS) {
            warn("Resource omitted because complete evidence exceeds the context budget: " + path);
            return;
        }
        contextChars += content.length();
        resources.add(new ResourceEvidenceDto(kind, path, content));
    }

    private void warn(String message) {
        if (warningChars + message.length() > MAX_WARNING_CHARS) {
            warnings.add("Diagnostic limit reached; additional resource evidence remains unverified.");
        } else if (warnings.add(message)) {
            warningChars += message.length();
        }
    }
}
