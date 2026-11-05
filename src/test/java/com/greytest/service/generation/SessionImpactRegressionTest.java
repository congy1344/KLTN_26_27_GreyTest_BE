package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.greytest.service.analysis.JavaParserHelper;
import com.greytest.service.diff.SourceDiffService;

/** Đối chiếu nguyên vẹn dependency/impact evidence giữa verified-load và session reuse. */
class SessionImpactRegressionTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"added", "body", "signature", "deleted", "dto", "pom", "lombok"})
    void changedSourceProducesExactlyTheSameDiffAndCallers(String change) throws Exception {
        String original = "package example; @org.springframework.stereotype.Service class Subject { int target(int value) { return value; } Input dto(Input input) { return input; } int independent() { return 7; } }";
        for (String snapshot : List.of("base", "candidate")) {
            write(snapshot, "Subject.java", original);
            write(snapshot, "Input.java", "package example; @lombok.Data class Input { private String name; }");
        }
        switch (change) {
            case "added" -> write("candidate", "Subject.java", original.replace("int independent()", "int added() { return 42; } int independent()"));
            case "body" -> write("candidate", "Subject.java", original.replace("return value;", "return value + 1;"));
            case "signature" -> write("candidate", "Subject.java", original.replace("target(int value)", "target(long value)").replace("return value;", "return (int) value;"));
            case "deleted" -> write("candidate", "Subject.java", original.replace("int target(int value) { return value; }", ""));
            case "dto" -> write("candidate", "Input.java", "package example; @lombok.Data class Input { private String name; private int age; }");
            case "pom" -> Files.writeString(temporary.resolve("candidate/pom.xml"), "<project><properties><java.version>21</java.version></properties></project>");
            case "lombok" -> Files.writeString(temporary.resolve("candidate/lombok.config"), "lombok.accessors.fluent = true");
        }
        var diff = new SourceDiffService(new JavaParserHelper());
        var expected = diff.compareSnapshots(temporary.resolve("base"), temporary.resolve("candidate"));
        assertThat(expected).anyMatch(item -> !item.diffType().name().equals("UNCHANGED"));
        var actual = SourceSnapshotSession.withSession(() -> {
            var first = diff.compareSnapshots(temporary.resolve("base"), temporary.resolve("candidate"));
            assertThat(diff.compareSnapshots(temporary.resolve("base"), temporary.resolve("candidate")))
                    .containsExactlyInAnyOrderElementsOf(first);
            return first;
        });
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private void write(String snapshot, String name, String content) throws Exception {
        Path file = temporary.resolve(snapshot + "/src/main/java/example/" + name);
        Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }
}
