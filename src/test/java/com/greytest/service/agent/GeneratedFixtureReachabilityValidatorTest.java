package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

class GeneratedFixtureReachabilityValidatorTest {
    private static final String SERVICE = """
            package demo;
            class Service {
              private final Reader reader;
              private final String defaultImage;
              private final Clients clients;
              @org.springframework.beans.factory.annotation.Value("${limit}") private Integer limit;
              Service(Reader reader, @org.springframework.beans.factory.annotation.Value("${image}") String defaultImage, Clients clients) {
                this.reader = reader; this.defaultImage = defaultImage; this.clients = clients;
              }
              java.util.List<String> guardedBlock(String id, User user) {
                java.util.List<String> items = reader.find(id);
                if (items.size() > 0 && user != null) { clients.load(user.getId()); }
                return items;
              }
              java.util.List<String> earlyReturn(String id, User user) {
                java.util.List<String> items = reader.find(id);
                if (items.isEmpty()) { return new java.util.ArrayList<>(); }
                if (user != null) { clients.load(user.getId()); }
                return items;
              }
              java.util.List<String> readsBeforeGuard(String id, User user) {
                clients.load(user.getId());
                java.util.List<String> items = reader.find(id);
                if (items.isEmpty()) { return items; }
                return items;
              }
              String build(String name) { return name + defaultImage; }
              String buildConditionally(String name) { if (name == null) { return defaultImage; } return name; }
              Integer capped() { return limit; }
              String ignoresConfig(String name) { return name; }
            }
            """;

    private List<String> validate(String target, String body) {
        return validate(target, "@org.mockito.Mock Reader reader; @org.mockito.Mock Clients clients; @org.mockito.InjectMocks Service service;", body);
    }

    private List<String> validate(String target, String fields, String body) {
        var owner = StaticJavaParser.parse(SERVICE).findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        var test = StaticJavaParser.parse("package demo; class ServiceTest { " + fields + " void testCase() { " + body + " } }");
        return GeneratedFixtureReachabilityValidator.validate(owner, target, test, "testCase");
    }

    private static final String USER = "User user = org.mockito.Mockito.mock(User.class); when(user.getId()).thenReturn(\"u\"); ";

    @Test void rejectsAStubOnlyReadInsideANonEmptyBranchWhenTheCollectionIsEmpty() {
        assertThat(validate("guardedBlock", USER + "when(reader.find(\"a\")).thenReturn(java.util.Collections.emptyList()); service.guardedBlock(\"a\", user);"))
                .singleElement().asString().contains("UnnecessaryStubbingException", "user.getId()");
    }

    @Test void rejectsAStubOnlyReadAfterAnEarlyReturnOnEmpty() {
        assertThat(validate("earlyReturn", USER + "when(reader.find(\"a\")).thenReturn(new java.util.ArrayList<>()); service.earlyReturn(\"a\", user);"))
                .singleElement().asString().contains("UnnecessaryStubbingException");
    }

    @Test void acceptsTheStubWhenTheCollectionHasElementsOrTheFixtureIsReadEarlier() {
        assertThat(validate("guardedBlock", USER + "when(reader.find(\"a\")).thenReturn(java.util.Collections.singletonList(\"x\")); service.guardedBlock(\"a\", user);")).isEmpty();
        assertThat(validate("readsBeforeGuard", USER + "when(reader.find(\"a\")).thenReturn(java.util.Collections.emptyList()); service.readsBeforeGuard(\"a\", user);")).isEmpty();
    }

    @Test void acceptsAStubTheTestItselfConsumes() {
        assertThat(validate("guardedBlock", USER + "when(reader.find(\"a\")).thenReturn(java.util.Collections.emptyList()); service.guardedBlock(\"a\", user); user.getId();")).isEmpty();
    }

    @Test void rejectsALocalMockThatIsOnlyStubbedAndNeverPassedOn() {
        assertThat(validate("guardedBlock", USER + "when(reader.find(\"a\")).thenReturn(java.util.Collections.singletonList(\"x\")); service.guardedBlock(\"a\", null);"))
                .singleElement().asString().contains("UnnecessaryStubbingException", "mock 'user'");
        assertThat(validate("guardedBlock", USER + "when(reader.find(\"a\")).thenReturn(java.util.Collections.singletonList(\"x\")); service.guardedBlock(\"a\", user);")).isEmpty();
    }

    @Test void rejectsConfigurationThatInjectMocksCannotFill() {
        assertThat(validate("build", "service.build(\"a\");")).singleElement().asString().contains("defaultImage", "@InjectMocks");
        assertThat(validate("capped", "service.capped();")).singleElement().asString().contains("limit");
    }

    @Test void acceptsConfigurationThatIsWiredUnreadOrOnlyReadConditionally() {
        assertThat(validate("build", "org.springframework.test.util.ReflectionTestUtils.setField(service, \"defaultImage\", \"x\"); service.build(\"a\");")).isEmpty();
        assertThat(validate("ignoresConfig", "service.ignoresConfig(\"a\");")).isEmpty();
        assertThat(validate("buildConditionally", "service.buildConditionally(\"a\");")).isEmpty();
        assertThat(validate("build", "Service service;", "service = new Service(null, \"x\", null); service.build(\"a\");")).isEmpty();
    }
}
