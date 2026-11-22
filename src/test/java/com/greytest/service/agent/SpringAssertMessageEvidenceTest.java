package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Spring Assert(condition, String message) ném exception giữ nguyên message truyền vào. */
class SpringAssertMessageEvidenceTest {

    @Test void provesConcatenatedMessageOfSpringAssertIsNull() {
        var classes = sources("void run(String name) { Assert.isNull(name, \"account already exists: \" + name); }",
                "import org.springframework.util.Assert; ");
        assertThat(validate(classes, "IllegalArgumentException message \"account already exists: \" + name")).isEmpty();
        assertThat(validate(classes, "IllegalArgumentException message \"account already exists: demo\" ")).isEmpty();
        assertThat(validate(classes, "IllegalArgumentException message \"user exists: demo\" ")).isPresent();
    }

    @Test void mapsStateToIllegalStateException() {
        var classes = sources("void run(boolean ok) { Assert.state(ok, \"not ready\"); }", "import org.springframework.util.Assert; ");
        assertThat(validate(classes, "IllegalStateException message \"not ready\" ")).isEmpty();
        assertThat(validate(classes, "IllegalArgumentException message \"not ready\" ")).isPresent();
    }

    @Test void doesNotTrustAnAssertClassThatIsNotSpring() {
        var classes = sources("void run(String name) { Assert.notNull(name, \"missing\"); }", "import other.Assert; ");
        assertThat(validate(classes, "IllegalArgumentException message \"missing\" ")).isPresent();
    }

    @Test void ignoresSpringAssertWithoutAStringMessage() {
        var classes = sources("void run(String name) { Assert.hasLength(name); }", "import org.springframework.util.Assert; ");
        assertThat(validate(classes, "IllegalArgumentException message \"anything\" ")).isPresent();
    }

    private Optional<String> validate(List<ClassContextDto> classes, String claim) {
        return new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of(), true);
    }

    private List<ClassContextDto> sources(String method, String imports) {
        var dto = new MethodContextDto(1L, "sample.Service", "run", "void", List.of(), List.of(), "PUBLIC",
                method, 1, 1, List.of(), List.of(), List.of());
        return List.of(new ClassContextDto(1L, "sample", "Service", "sample.Service", "SERVICE", "Service.java",
                "package sample; " + imports + "class Service { " + method + " }", List.of(), List.of(dto)));
    }
}
