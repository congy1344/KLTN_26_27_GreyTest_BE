package com.greytest.service.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.service.analysis.JavaParserHelper;

class SourceDiffServiceTest {

    private final SourceDiffService diffService = new SourceDiffService(new JavaParserHelper());

    @Test
    void detectsAddedModifiedAndDeletedMethods(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("base");
        Path candDir = tempDir.resolve("cand");

        Files.createDirectories(baseDir.resolve("src/main/java/com/example"));
        Files.createDirectories(candDir.resolve("src/main/java/com/example"));

        Files.writeString(baseDir.resolve("pom.xml"), "<project/>");
        Files.writeString(candDir.resolve("pom.xml"), "<project/>");

        // Base version:
        // - calculateDiscount(int) [will be modified]
        // - oldMethod() [will be deleted]
        String baseClass = """
                package com.example;
                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    public double calculateDiscount(int amount) {
                        if (amount > 100) {
                            return 0.1;
                        }
                        return 0.0;
                    }

                    public void oldMethod() {
                        System.out.println("old");
                    }
                }
                """;
        Files.writeString(baseDir.resolve("src/main/java/com/example/OrderService.java"), baseClass);

        // Candidate version:
        // - calculateDiscount(int) [modified: condition changed to amount >= 100]
        // - newMethod() [added]
        String candClass = """
                package com.example;
                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    public double calculateDiscount(int amount) {
                        if (amount >= 100) {
                            return 0.15;
                        }
                        return 0.0;
                    }

                    public void newMethod() {
                        System.out.println("new");
                    }
                }
                """;
        Files.writeString(candDir.resolve("src/main/java/com/example/OrderService.java"), candClass);

        List<MethodDiffItem> diffs = diffService.compareSnapshots(baseDir, candDir);

        assertThat(diffs).isNotEmpty();

        MethodDiffItem modified = diffs.stream()
                .filter(d -> d.methodName().equals("calculateDiscount"))
                .findFirst().orElseThrow();
        assertThat(modified.diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(modified.isServiceMethod()).isTrue();

        MethodDiffItem deleted = diffs.stream()
                .filter(d -> d.methodName().equals("oldMethod"))
                .findFirst().orElseThrow();
        assertThat(deleted.diffType()).isEqualTo(MethodDiffType.DELETED);

        MethodDiffItem added = diffs.stream()
                .filter(d -> d.methodName().equals("newMethod"))
                .findFirst().orElseThrow();
        assertThat(added.diffType()).isEqualTo(MethodDiffType.ADDED);
    }

    @Test
    void identifiesUnchangedMethodWhenAstIdentical(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("base");
        Path candDir = tempDir.resolve("cand");

        Files.createDirectories(baseDir.resolve("src/main/java/com/example"));
        Files.createDirectories(candDir.resolve("src/main/java/com/example"));

        Files.writeString(baseDir.resolve("pom.xml"), "<project/>");
        Files.writeString(candDir.resolve("pom.xml"), "<project/>");

        String code = """
                package com.example;
                import org.springframework.stereotype.Service;

                @Service
                public class UserService {
                    public String findName(Long id) {
                        return "User" + id;
                    }
                }
                """;
        Files.writeString(baseDir.resolve("src/main/java/com/example/UserService.java"), code);
        Files.writeString(candDir.resolve("src/main/java/com/example/UserService.java"), code);

        List<MethodDiffItem> diffs = diffService.compareSnapshots(baseDir, candDir);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).diffType()).isEqualTo(MethodDiffType.UNCHANGED);
    }

    @Test
    void rejectsCandidateWhenProductionFileCannotBeParsed(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("base");
        Path candidateDir = tempDir.resolve("candidate");
        Files.createDirectories(baseDir.resolve("src/main/java/com/example"));
        Files.createDirectories(candidateDir.resolve("src/main/java/com/example"));
        Files.writeString(baseDir.resolve("pom.xml"), "<project/>");
        Files.writeString(candidateDir.resolve("pom.xml"), "<project/>");
        Files.writeString(baseDir.resolve("src/main/java/com/example/OrderService.java"),
                "package com.example; class OrderService { void calculate() {} }");
        Files.writeString(candidateDir.resolve("src/main/java/com/example/OrderService.java"),
                "package com.example; class OrderService { void calculate( {} }");
        Files.writeString(candidateDir.resolve("src/main/java/com/example/Other.java"),
                "package com.example; class Other {} ");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> diffService.compareSnapshots(baseDir, candidateDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse");
    }
}
