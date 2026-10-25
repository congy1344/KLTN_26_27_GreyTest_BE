import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verifies the generation source snapshot locally using only the Java 8 standard library. */
public class GreyTestSourceCheck {
    public static void main(String[] args) {
        try {
            verify(Paths.get(".").toRealPath(), Paths.get("greytest-source-version.txt"));
        } catch (Exception exception) {
            System.err.println("[GreyTest] ERROR: " + exception.getMessage());
            System.err.println("[GreyTest] Source version mismatch: use the generation source snapshot or re-analyze and regenerate tests. Approved expected results must not be changed to hide this mismatch.");
            System.exit(1);
        }
    }

    private static void verify(Path root, Path manifest) throws Exception {
        if (!Files.isRegularFile(manifest) || Files.size(manifest) > 131072) {
            throw new IOException("Missing or oversized greytest-source-version.txt; extract the complete ZIP");
        }
        List<String> paths = new ArrayList<String>();
        List<String> hashes = new ArrayList<String>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split(" ", -1);
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid source version metadata");
            }
            String path = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(fields[1]))).toString();
            validatePath(path);
            paths.add(path);
            hashes.add(fields[0]);
            if (paths.size() > 256) throw new IOException("Too many generation source files");
        }
        if (paths.isEmpty()) throw new IOException("Empty source version metadata");

        List<Path> sources = resolveSources(root, paths);
        for (int i = 0; i < paths.size(); i++) {
            if (!hash(sources.get(i)).equals(hashes.get(i))) throw new IOException("Source changed: " + paths.get(i));
        }
        System.out.println("[GreyTest] Source version verified for " + paths.size() + " generation files.");
    }

    private static List<String> modulePrefixes(List<String> paths) {
        Set<String> prefixes = new LinkedHashSet<String>();
        for (String path : paths) {
            int index = path.indexOf("src/main/java/");
            if (index < 0 || (index > 0 && path.charAt(index - 1) != '/')) return new ArrayList<String>();
            prefixes.add(path.substring(0, index));
        }
        return new ArrayList<String>(prefixes);
    }

    private static String commonPrefix(List<String> prefixes) {
        String prefix = prefixes.isEmpty() ? "" : prefixes.get(0);
        for (String module : prefixes) {
            while (!module.startsWith(prefix)) {
                int slash = prefix.lastIndexOf('/', prefix.length() - 2);
                prefix = slash < 0 ? "" : prefix.substring(0, slash + 1);
            }
        }
        return prefix;
    }

    private static List<Path> resolveSources(Path root, List<String> paths) throws IOException {
        List<String> modules = modulePrefixes(paths);
        String common = commonPrefix(modules);
        List<String> prefixes = new ArrayList<String>();
        prefixes.add("");
        for (int i = 0; i < common.length(); i++) {
            if (common.charAt(i) == '/') prefixes.add(common.substring(0, i + 1));
        }
        Set<List<Path>> layouts = new LinkedHashSet<List<Path>>();
        for (String strip : prefixes) {
            addLayout(layouts, root, paths, strip, common);
            for (String module : modules) {
                String suffix = module.substring(strip.length());
                if (suffix.isEmpty()) continue;
                Path relative = Paths.get(suffix);
                if (!root.endsWith(relative)) continue;
                Path ancestor = root;
                for (int i = 0; i < relative.getNameCount() && ancestor != null; i++) ancestor = ancestor.getParent();
                if (ancestor != null) addLayout(layouts, ancestor, paths, strip, common);
            }
        }
        if (layouts.size() > 1) throw new IOException("Ambiguous source layout: multiple complete recorded source trees exist");
        if (layouts.isEmpty()) {
            throw new IOException("Generation source files are missing; extract this ZIP into the selected module root "
                    + "and keep the recorded sibling modules in the original project layout");
        }
        List<Path> sources = layouts.iterator().next();
        Path active = root.resolve("src/main/java");
        if (Files.isDirectory(active)) {
            Path activeRoot = active.toRealPath();
            boolean anchored = false;
            for (Path source : sources) {
                if (source.startsWith(activeRoot)) anchored = true;
            }
            if (!anchored) throw new IOException("Generation source files are missing from the active module layout; "
                    + "restore its recorded dependencies instead of using a nested source copy");
        }
        return sources;
    }

    private static void addLayout(Set<List<Path>> layouts, Path root, List<String> paths,
            String strip, String common) throws IOException {
        for (String path : paths) {
            if (!Files.isRegularFile(root.resolve(path.substring(strip.length())))) return;
        }
        // Giữ nguyên quan hệ module đã ghi nhận; không dò theo basename hay chọn bản có hash trùng.
        Path boundary = root.resolve(common.substring(strip.length())).toRealPath();
        List<Path> sources = new ArrayList<Path>();
        for (String path : paths) {
            Path source = checkedFile(root, path.substring(strip.length())).toRealPath();
            if (!source.startsWith(boundary)) throw new IOException("Unsafe source file outside the recorded project: " + path);
            sources.add(source);
        }
        layouts.add(sources);
    }

    private static Path checkedFile(Path root, String relative) throws IOException {
        validatePath(relative);
        Path source = root.resolve(relative).normalize();
        if (!source.startsWith(root) || !source.toRealPath().startsWith(root)
                || !Files.isRegularFile(source) || Files.size(source) > 2000000) {
            throw new IOException("Unsafe or oversized source file: " + relative);
        }
        return source;
    }

    private static void validatePath(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || path.indexOf(0) >= 0) {
            throw new IOException("Unsafe generation source path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Unsafe generation source path");
            }
        }
    }

    private static String hash(Path source) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(source))).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }
}
