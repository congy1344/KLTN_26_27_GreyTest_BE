package com.greytest.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.greytest.service.agent.SnapshotAnalysisLoader;

class ServiceImplementationClassificationTest {
    @Test
    void targetsConcreteComponentImplementingAnnotatedServiceContract(@TempDir Path root) throws Exception {
        source(root, "api/TokenService.java", "package api; @org.springframework.stereotype.Service public interface TokenService { String token(); }");
        source(root, "impl/DefaultTokenService.java", "package impl; import api.TokenService; @org.springframework.stereotype.Component public class DefaultTokenService implements TokenService { public String token() { return \"value\"; } }");
        var classes = SnapshotAnalysisLoader.parse(root, true).classes();
        assertThat(classes).filteredOn(c -> c.className().equals("TokenService")).singleElement()
                .satisfies(c -> assertThat(c.classType()).isEqualTo("OTHER"));
        assertThat(classes).filteredOn(c -> c.className().equals("DefaultTokenService")).singleElement()
                .satisfies(c -> assertThat(c.classType()).isEqualTo("SERVICE"));
    }

    @Test
    void doesNotPromoteComponentUsingDifferentSameNamedContract(@TempDir Path root) throws Exception {
        source(root, "api/TokenService.java", "package api; @org.springframework.stereotype.Service public interface TokenService { String token(); }");
        source(root, "other/TokenService.java", "package other; public interface TokenService { String token(); }");
        source(root, "impl/Unrelated.java", "package impl; import other.TokenService; @org.springframework.stereotype.Component public class Unrelated implements TokenService { public String token() { return \"value\"; } }");
        assertThat(SnapshotAnalysisLoader.parse(root, true).classes()).filteredOn(c -> c.className().equals("Unrelated"))
                .singleElement().satisfies(c -> assertThat(c.classType()).isEqualTo("OTHER"));
    }

    private void source(Path root, String name, String source) throws Exception {
        Path file = root.resolve("src/main/java").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
