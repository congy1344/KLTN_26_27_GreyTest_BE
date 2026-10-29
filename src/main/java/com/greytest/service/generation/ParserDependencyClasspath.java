package com.greytest.service.generation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

/** Classpath Maven đã resolve theo module do operator cung cấp; parser chỉ đọc bytecode bất biến. */
final class ParserDependencyClasspath {
    private record Artifact(String hash, byte[] bytes, Set<String> classes) { }
    private record Module(List<Artifact> jars, Set<String> sources) { }
    record Snapshot(Map<String, Module> modules, String digest) {
        boolean configured() { return !modules.isEmpty(); }
        boolean sourceAvailable(String module, String sourceModule) {
            return !configured() || modules.containsKey(module) && modules.get(module).sources().contains(sourceModule);
        }
        CombinedTypeSolver solver(MemoryTypeSolver source, String module) {
            var solver = new CombinedTypeSolver(source);
            if (configured()) {
                var dependencies = modules.get(module);
                if (dependencies == null) throw new IllegalArgumentException("Analysis classpath missing module: " + module);
                try {
                    for (Artifact jar : dependencies.jars()) solver.add(new JarTypeSolver(new ByteArrayInputStream(jar.bytes())));
                } catch (IOException invalid) { throw new IllegalArgumentException("Cannot index analysis dependency JAR", invalid); }
            }
            // Với manifest đã xác minh, không dùng thư viện backend chưa được ghi fingerprint.
            solver.add(new ReflectionTypeSolver(configured()));
            return solver;
        }
    }
    private ParserDependencyClasspath() { }

    static Snapshot load(Path root, Set<String> sourceModules) {
        String property = System.getProperty("greytest.analysis.classpath-file", "").trim();
        String jdk = System.getProperty("java.runtime.version") + ":" + System.getProperty("java.vendor");
        if (property.isEmpty()) return defaultSnapshot(jdk);
        try {
            ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            JsonNode configuration = mapper.readTree(Files.readString(Path.of(property)));
            if (!configuration.path("format").asText().equals("greytest-analysis-classpath-v1")) throw new IllegalArgumentException("Invalid analysis classpath format");
            boolean boundRoot = false;
            for (JsonNode entry : configuration.path("sourceRoots")) if (Path.of(entry.asText()).toRealPath().equals(root.toRealPath())) boundRoot = true;
            if (!boundRoot) return defaultSnapshot(jdk);
            Map<String, String> externalInputs = new TreeMap<>();
            var external = configuration.path("externalInputs").fields();
            while (external.hasNext()) {
                var entry = external.next(); Path path = Path.of(entry.getKey());
                String actual = Files.exists(path) ? hash(Files.readAllBytes(path)) : "absent";
                if (!actual.equals(entry.getValue().asText())) throw new IllegalArgumentException("Analysis build input changed; resolve the classpath again");
                externalInputs.put(entry.getKey(), actual);
            }
            if (configuration.hasNonNull("modelRepository")) {
                Path repository = Path.of(configuration.path("modelRepository").asText()).toRealPath();
                Set<Path> expected = new LinkedHashSet<>();
                for (String name : externalInputs.keySet()) {
                    Path path = Path.of(name).toAbsolutePath().normalize();
                    if (path.startsWith(repository) && modelFile(path)) expected.add(path);
                }
                try (var files = Files.walk(repository)) {
                    Set<Path> actual = files.filter(Files::isRegularFile).filter(ParserDependencyClasspath::modelFile)
                            .map(p -> p.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
                    if (!actual.equals(expected)) throw new IllegalArgumentException("Maven model repository changed; resolve the classpath again");
                }
            }
            Map<String, String> buildHashes = new TreeMap<>();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().equals("pom.xml"))
                        .filter(p -> !excluded(root.relativize(p))).toList()) {
                    checked(root, path);
                    buildHashes.put(relative(root, path), hash(Files.readAllBytes(path)));
                }
            }
            Map<String, String> expectedBuilds = mapper.convertValue(configuration.path("builds"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            if (!buildHashes.equals(expectedBuilds)) throw new IllegalArgumentException("Analysis classpath does not match source POMs; resolve the classpath again");
            Set<String> declaredModules = new LinkedHashSet<>(); configuration.path("modules").fieldNames().forEachRemaining(declaredModules::add);
            if (!declaredModules.equals(sourceModules)) throw new IllegalArgumentException("Analysis classpath does not match source modules");
            Map<Path, Artifact> artifacts = new LinkedHashMap<>();
            Map<String, Module> modules = new TreeMap<>();
            Map<String, Object> identity = new TreeMap<>(); identity.put("builds", buildHashes); identity.put("jdk", jdk); identity.put("externalInputs", externalInputs);
            Map<String, Object> identities = new TreeMap<>();
            Map<String, String> verifiedSources = new TreeMap<>();
            for (String module : sourceModules.stream().sorted().toList()) {
                JsonNode declared = configuration.path("modules").path(module);
                List<Artifact> jars = new ArrayList<>();
                for (JsonNode entry : declared.path("jars")) {
                    Path path = Path.of(entry.asText()).toRealPath();
                    if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) throw new IllegalArgumentException("Analysis dependencies must be JAR files");
                    Artifact artifact = artifacts.get(path);
                    if (artifact == null) {
                        byte[] bytes = Files.readAllBytes(path);
                        Set<String> classes = new LinkedHashSet<>();
                        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                            for (var zipEntry = zip.getNextEntry(); zipEntry != null; zipEntry = zip.getNextEntry())
                                if (zipEntry.getName().endsWith(".class") && !zipEntry.getName().equals("module-info.class") && !zipEntry.getName().startsWith("META-INF/")) classes.add(zipEntry.getName());
                        }
                        artifact = new Artifact(hash(bytes), bytes, Set.copyOf(classes)); artifacts.put(path, artifact);
                    }
                    jars.add(artifact);
                }
                Set<String> available = new LinkedHashSet<>(); available.add(module);
                var bindings = declared.path("sourceBindings").fields();
                while (bindings.hasNext()) {
                    var binding = bindings.next();
                    if (!sourceModules.contains(binding.getKey())) throw new IllegalArgumentException("Unknown source module binding");
                    Path directory = root.resolve(binding.getKey()).normalize(); checked(root, directory);
                    String sourceHash = verifiedSources.get(binding.getKey());
                    if (sourceHash == null) { sourceHash = sourceHash(root, directory); verifiedSources.put(binding.getKey(), sourceHash); }
                    String binaryHash = binding.getValue().path("jarHash").asText();
                    if (!sourceHash.equals(binding.getValue().path("sourceHash").asText()) || jars.stream().noneMatch(a -> a.hash().equals(binaryHash)))
                        throw new IllegalArgumentException("Analysis source/binary binding changed; rebuild and resolve module " + binding.getKey());
                    Artifact binary = jars.stream().filter(a -> a.hash().equals(binaryHash)).findFirst().orElseThrow();
                    // Source binding không được che class trùng tên ở JAR đứng trước nó.
                    for (Artifact earlier : jars) {
                        if (earlier == binary) break;
                        if (earlier.classes().stream().anyMatch(binary.classes()::contains)) throw new IllegalArgumentException("Analysis source binding is shadowed by an earlier dependency JAR");
                    }
                    available.add(binding.getKey());
                }
                modules.put(module, new Module(List.copyOf(jars), Set.copyOf(available)));
                identities.put(module, Map.of("jars", jars.stream().map(Artifact::hash).toList(), "sources", available.stream().sorted().toList(), "bindings", declared.path("sourceBindings")));
            }
            identity.put("modules", identities);
            return new Snapshot(Map.copyOf(modules), GenerationFingerprintService.hash(mapper.writeValueAsString(identity)));
        } catch (IOException invalid) { throw new IllegalArgumentException("Cannot read configured analysis classpath", invalid); }
    }

    private static boolean modelFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".pom") || name.startsWith("maven-metadata") && name.endsWith(".xml");
    }

    private static Snapshot defaultSnapshot(String jdk) {
        return new Snapshot(Map.of(), GenerationFingerprintService.hash("default-runtime:" + jdk + ":" + RuntimeIdentity.VALUE));
    }

    /** Classpath của tiến trình được chốt lúc khởi động, kể cả byte thư viện backend dùng để resolve. */
    private static final class RuntimeIdentity {
        static final String VALUE = capture();
        private static String capture() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
                    Path path = Path.of(entry).toAbsolutePath().normalize();
                    digest.update(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (Files.isRegularFile(path)) update(digest, path);
                    else if (Files.isDirectory(path)) try (var files = Files.walk(path)) {
                        for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                            digest.update(path.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); update(digest, file);
                        }
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (IOException | java.security.NoSuchAlgorithmException invalid) { throw new IllegalStateException("Cannot fingerprint parser runtime", invalid); }
        }
        private static void update(MessageDigest digest, Path path) throws IOException {
            try (var stream = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; for (int n; (n = stream.read(buffer)) >= 0;) digest.update(buffer, 0, n); }
        }
    }

    static String sourceHash(Path root, Path directory) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> !excluded(directory.relativize(p))).toList()) {
                checked(root, path); files.put(relative(directory, path), hash(Files.readAllBytes(path)));
            }
        }
        StringBuilder manifest = new StringBuilder(); files.forEach((path, value) -> manifest.append(path.length()).append(':').append(path).append(':').append(value).append('\n'));
        return GenerationFingerprintService.hash(manifest.toString());
    }
    private static boolean excluded(Path path) {
        for (Path part : path) if (Set.of(".git", "target", "build", "node_modules", ".gradle", "generated-sources", "out").contains(part.toString())) return true;
        return false;
    }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static void checked(Path root, Path path) throws IOException {
        if (!path.toRealPath().startsWith(root.toRealPath()) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("Analysis classpath source path escapes snapshot");
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
