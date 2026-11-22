package com.greytest.service.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class SandboxCompilationFailuresTest {

    @Test
    void attributesRecordedMailCheckedExceptionsToAllEightAffectedTests() throws Exception {
        String source = resource("MailServiceTest.java.txt");
        String log = resource("compiler-errors.txt");
        var result = SandboxCompilationFailures.parse(log,
                archive("io/github/jhipster/sample/service/MailServiceTest.java", source), "/work", 3000);

        assertThat(result.projectError()).isNull();
        assertThat(result.failures()).hasSize(8).allSatisfy(failure -> {
            assertThat(failure.className()).isEqualTo("io.github.jhipster.sample.service.MailServiceTest");
            assertThat(failure.assertion()).isFalse();
            assertThat(failure.detail()).startsWith("COMPILATION ERROR:").contains("unreported exception");
        });
        assertThat(result.failures()).anySatisfy(failure -> {
            assertThat(failure.methodName()).isEqualTo("testSendEmail_DeliversSuppliedEmailDetails");
            assertThat(failure.detail()).contains("jakarta.mail.MessagingException", "java.io.IOException", "Source:");
        });
    }

    @Test
    void routesCheckedExceptionsInSharedSetupToEveryAffectedTest() throws Exception {
        String source = """
                package demo;
                class MailTest {
                    @org.junit.jupiter.api.BeforeEach void setup() {
                        message.getSubject();
                    }
                    @org.junit.jupiter.api.Test void first() {}
                    @org.junit.jupiter.api.Test void second() {}
                }
                """;
        var result = SandboxCompilationFailures.parse(
                "[ERROR] /work/src/test/java/demo/MailTest.java:[4,16] unreported exception jakarta.mail.MessagingException; must be caught or declared to be thrown\n",
                archive("demo/MailTest.java", source), "/work", 3000);

        assertThat(result.projectError()).isNull();
        assertThat(result.failures()).extracting(failure -> failure.methodName()).containsExactly("first", "second");
        assertThat(result.failures()).noneMatch(failure -> failure.assertion());
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/mail-checked-exceptions/" + name)) {
            return new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private byte[] archive(String path, String source) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("src/test/java/" + path));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
