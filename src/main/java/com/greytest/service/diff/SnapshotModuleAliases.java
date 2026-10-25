package com.greytest.service.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greytest.service.ServiceScopeResolver;
import com.greytest.service.diff.SourceDiffService.MethodSnapshotInfo;
import com.greytest.service.generation.GenerationFingerprintService.Fingerprint;

/** Chỉ nhận đổi thư mục ZIP bọc ngoài khi ánh xạ module hai chiều là duy nhất. */
final class SnapshotModuleAliases {
    private final Map<String, String> aliases = new HashMap<>();

    SnapshotModuleAliases(Map<String, MethodSnapshotInfo> baseline, Map<String, MethodSnapshotInfo> candidate) {
        Map<String, Set<String>> candidates = new HashMap<>();
        for (MethodSnapshotInfo after : candidate.values()) {
            String module = ServiceScopeResolver.modulePath(after.relativePath());
            Set<String> matches = candidates.computeIfAbsent(module, ignored -> new HashSet<>());
            for (MethodSnapshotInfo before : baseline.values()) {
                if (!before.qualifiedClassName().equals(after.qualifiedClassName())) continue;
                String previous = ServiceScopeResolver.modulePath(before.relativePath());
                if (previous.equals(module) || previous.equals(".") || module.equals(".")
                        || previous.endsWith("/" + module) || module.endsWith("/" + previous)) matches.add(previous);
            }
        }
        candidates.forEach((module, matches) -> {
            if (matches.contains(module)) aliases.put(module, module);
            else if (matches.size() == 1) aliases.put(module, matches.iterator().next());
        });
        Set<String> duplicated = new HashSet<>();
        Map<String, String> inverse = new HashMap<>();
        aliases.forEach((after, before) -> { if (inverse.putIfAbsent(before, after) != null) duplicated.add(before); });
        aliases.entrySet().removeIf(entry -> duplicated.contains(entry.getValue()));
    }

    <T> Map<String, T> keys(Map<String, T> values) {
        Map<String, T> result = new TreeMap<>();
        values.forEach((key, value) -> result.put(key(key), value));
        return result;
    }

    Map<String, Fingerprint> evidence(Map<String, Fingerprint> values) {
        Map<String, Fingerprint> result = new TreeMap<>();
        ObjectMapper mapper = new ObjectMapper();
        values.forEach((key, value) -> {
            try {
                ObjectNode manifest = (ObjectNode) mapper.readTree(value.provenance());
                Map<String, JsonNode> dependencies = new TreeMap<>();
                manifest.path("sourceDependencies").fields().forEachRemaining(entry -> dependencies.put(key(entry.getKey()), entry.getValue()));
                manifest.set("sourceDependencies", mapper.valueToTree(dependencies));
                Map<String, JsonNode> resources = new TreeMap<>();
                manifest.path("resources").fields().forEachRemaining(entry -> resources.put(resource(entry.getKey()), entry.getValue()));
                manifest.set("resources", mapper.valueToTree(resources));
                String normalized = manifest.toString();
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                result.put(key(key), new Fingerprint(digest, normalized, value.reusable()));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException("Cannot normalize dependency module evidence", exception);
            }
        });
        return result;
    }

    private String key(String key) {
        String dispatch = "dispatch-source-contract:";
        if (key.startsWith(dispatch)) return dispatch + key(key.substring(dispatch.length()));
        String candidate = "dispatch-candidate:";
        if (key.startsWith(candidate) && key.contains("|")) {
            int separator = key.indexOf('|');
            return candidate + key(key.substring(candidate.length(), separator)) + "|" + key(key.substring(separator + 1));
        }
        int delimiter = key.indexOf(':');
        if (delimiter < 0) return key;
        String module = key.substring(0, delimiter);
        return aliases.getOrDefault(module, module) + key.substring(delimiter);
    }

    private String resource(String path) {
        int kindDelimiter = path.indexOf(':');
        if (kindDelimiter >= 0) return path.substring(0, kindDelimiter + 1) + resource(path.substring(kindDelimiter + 1));
        List<String> modules = new ArrayList<>(aliases.keySet());
        modules.sort(java.util.Comparator.comparingInt(String::length).reversed());
        for (String module : modules) {
            if (!module.equals(".") && path.startsWith(module + "/")) {
                String before = aliases.get(module);
                return (before.equals(".") ? "" : before + "/") + path.substring(module.length() + 1);
            }
            if (module.equals(".") && path.startsWith("src/main/resources/")) {
                String before = aliases.get(module);
                return (before.equals(".") ? "" : before + "/") + path;
            }
        }
        return path;
    }
}
