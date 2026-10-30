package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;

class MockitoOutputDependencyTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {
        "when(client.fetch(1L)).thenReturn(new Payload());",
        "org.mockito.Mockito.when(client.fetch(1L)).thenReturn(new Payload());",
        "when(client.optional()).thenReturn(java.util.Optional.empty());",
        "when(client.fetch(1L)).thenReturn(new Payload()); verifyNoMoreInteractions(new Object[]{client});",
        "when(client.fetch(1L)).thenReturn(new Payload(), new Payload[]{new Payload()});",
        "verify(client).fetch(1L);",
        "doReturn(new Payload()).when(client).fetch(1L);",
        "doThrow(new RuntimeException()).when(client).fetch(1L);",
        "doAnswer(invocation -> new Payload()).when(client).fetch(1L);"})
    void resolvesMockitoWrappersUsingActualApiAndKeepsMockContract(String statement) throws Exception {
        withIndex(index -> {
            var problems = new ArrayList<String>();
            var dependencies = new GeneratedOutputDependencyResolver(index, problems, ".").resolve(List.of(test(statement)));
            assertThat(problems).isEmpty();
            assertThat(dependencies.keySet()).anyMatch(k -> k.contains("Client#fetch(Long)") || k.contains("Client#optional()"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "when(client.fetch(1L)).thenReturn(\"wrong type\");",
        "when(client.fetch(1L)).thenReturn(new Payload(), new String[]{\"wrong\"});",
        "when(client.fetch(\"wrong argument\")).thenReturn(new Payload());",
        "when(client.missing(1L)).thenReturn(new Payload());",
        "org.mockito.ArgumentMatchers.<Long>any(String.class);",
        "when(client.optional()).thenReturn(java.util.Optional.<String>empty());",
        "when(client.optional()).thenReturn(NumberFactory.empty());",
        "when(client.fetch(null)).thenReturn(new Payload());",
        "org.mockito.ArgumentCaptor<Payload> captor = org.mockito.ArgumentCaptor.forClass(Payload.class); captor.missing();",
        "when(client.fetch(1L)).missingFrameworkApi(new Payload());"})
    void rejectsInvalidReturnSignatureOrMissingApi(String statement) throws Exception {
        withIndex(index -> {
            var problems = new ArrayList<String>();
            new GeneratedOutputDependencyResolver(index, problems, ".").resolve(List.of(test(statement)));
            assertThat(problems).as(statement).isNotEmpty();
        });
    }

    @Test
    void doesNotHideUnknownBodiesReachedThroughTheSut() throws Exception {
        withIndex(index -> {
            var problems = new ArrayList<String>();
            new GeneratedOutputDependencyResolver(index, problems, ".").resolve(List.of(test(
                    "when(client.fetch(1L)).thenReturn(new Payload()); subject.run(1L);")));
            assertThat(problems).anyMatch(p -> p.contains("unresolved call"));
        });
    }

    private String test(String statement) {
        return "package sample; import static org.mockito.Mockito.*; class GeneratedTest { "
                + "@org.mockito.Mock Client client; @org.mockito.InjectMocks Subject subject; void test() { " + statement + " } }";
    }

    @Test
    void substitutesGenericReceiverBeforeBindingMatcherAndRejectsWrongReturnElement() throws Exception {
        withIndex(index -> {
            String source = "package sample; import static org.mockito.Mockito.*; import static org.mockito.ArgumentMatchers.any; "
                    + "class GeneratedTest { @org.mockito.Mock GenericClient<Payload> client; void test() { "
                    + "when(client.fetch(any())).thenReturn(java.util.List.of(new Payload())); } }";
            var problems = new ArrayList<String>();
            new GeneratedOutputDependencyResolver(index, problems, ".").resolve(List.of(source));
            assertThat(problems).isEmpty();
            new GeneratedOutputDependencyResolver(index, problems, ".").resolve(List.of(source.replace("new Payload()", "\"wrong\"")));
            assertThat(problems).isNotEmpty();
        });
    }

    @Test
    void spyOrRealReceiverOfAMockedTypeStillTracksExecutedBodies() throws Exception {
        withIndex(index -> {
            for (String receiver : List.of("real", "spy")) {
                var problems = new ArrayList<String>();
                String source = "package sample; import static org.mockito.Mockito.*; class Test { @org.mockito.Mock Client client; "
                        + "@org.mockito.Spy Client spy; Client real; void test() { when(client.fetch(1L)).thenReturn(new Payload()); " + receiver + ".fetch(1L); } }";
                new GeneratedOutputDependencyResolver(index, problems, ".").resolve(List.of(source));
                assertThat(problems).as(receiver).anyMatch(p -> p.contains("unresolved call"));
            }
        });
    }

    private void withIndex(java.util.function.Consumer<SourceDependencyIndex> assertion) throws Exception {
        Path sources = root.resolve("src/main/java/sample"); Files.createDirectories(sources);
        Files.writeString(sources.resolve("Payload.java"), "package sample; public class Payload {}");
        Files.writeString(sources.resolve("GenericClient.java"), "package sample; class GenericClient<T> { "
                + "java.util.List<T> fetch(java.util.List<T> ids) { return ids; } }");
        Files.writeString(sources.resolve("NumberFactory.java"), "package sample; class NumberFactory { "
                + "static <T extends Number> java.util.Optional<T> empty() { return java.util.Optional.empty(); } }");
        Files.writeString(sources.resolve("Client.java"), "package sample; public class Client { "
                + "public Payload fetch(Long id) { return unavailableWebClient.call().map(value -> value.getData()); } "
                + "public Payload fetch(Integer id) { return null; } public java.util.Optional<Payload> optional() { return null; } }");
        Files.writeString(sources.resolve("Subject.java"), "package sample; public class Subject { Client client; "
                + "public Payload run(Long id) { return client.fetch(id); } }");
        Path manifest = root.resolve("classpath.json");
        new ObjectMapper().writeValue(manifest.toFile(), Map.of("format", "greytest-analysis-classpath-v1", "builds", Map.of(),
                "sourceRoots", List.of(root.toRealPath().toString()), "modules", Map.of(".", Map.of("jars", List.of(), "sourceBindings", Map.of()))));
        String previous = System.getProperty("greytest.analysis.classpath-file");
        try {
            System.setProperty("greytest.analysis.classpath-file", manifest.toString());
            assertion.accept(SourceDependencyIndex.load(root));
        } finally {
            if (previous == null) System.clearProperty("greytest.analysis.classpath-file");
            else System.setProperty("greytest.analysis.classpath-file", previous);
        }
    }
}
