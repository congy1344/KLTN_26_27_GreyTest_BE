package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.github.javaparser.JavaParser;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

class GeneratedBooleanAssertionValidatorTest {
    @Test
    void approvedBooleanOracleCanExposeAnIncorrectSourceMembershipResult() {
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false)).isEmpty();
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false)).isEmpty();
    }

    @Test
    void preservesApprovedBooleanAssertionsAfterResultReordering() {
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false))
                .isEmpty();
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false))
                .isEmpty();
    }

    @Test
    void acceptsCorrectAssertionsAndPreservesInitialStateForNonMembers() {
        assertThat(validate("assertFalse(result.getData().get(0).getProfile().isActive());"
                + "assertTrue(result.getData().get(1).getProfile().isActive());", false)).isEmpty();
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", true)).isEmpty();
        assertThat(validate("assertFalse(result.getData().get(0).getProfile().isActive());", true)).isEmpty();
    }

    @Test
    void skipsMutatedFixturesAndConditionalOrLateStubs() {
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), code -> code.replace("Pager<Entry> result=", "b.getProfile().setActive(true); Pager<Entry> result="))).isEmpty();
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), code -> code.replace("when(relations.selected())", "if (false) when(relations.selected())"))).isEmpty();
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), code -> code.replace("when(relations.selected()).thenReturn(java.util.Collections.singleton(\"A\"));", "")
                        .replace("Pager<Entry> result=service.browse();", "Pager<Entry> result=service.browse(); when(relations.selected()).thenReturn(java.util.Collections.singleton(\"A\"));"))).isEmpty();
    }

    @Test
    void skipsUnreachableOrAmbiguousSourcePathsAndForeignLambdaTargets() {
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false,
                code -> code.replace("enrich(entries);", "if (false) enrich(entries);"), java.util.function.UnaryOperator.identity())).isEmpty();
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                code -> code.replace("entries=reader.read();", "entries=reader.read(); if (unknown()) return new Pager<>(entries);"),
                java.util.function.UnaryOperator.identity())).isEmpty();
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                code -> code.replace("entry.getProfile()", "other.getProfile()"), java.util.function.UnaryOperator.identity())).isEmpty();
    }

    @Test
    void doesNotConfuseNonNullnessWithReferenceIdentityOrStubArguments() {
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false,
                code -> code.replace("browse()", "browse(Object a, Object b)").replace("enrich(entries);", "if(a==b) enrich(entries);"),
                code -> code.replace("service.browse()", "service.browse(new Object(),new Object())"))).isEmpty();
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false,
                code -> code.replace("relations.selected()", "relations.selected(\"X\")"),
                code -> code.replace("relations.selected()", "relations.selected(\"Y\")"))).isEmpty();
    }

    @Test
    void doesNotGuessInitializerOrCompoundSetterBehavior() {
        String profile = "class Profile { private String id; private boolean active; { active=true; } "
                + "Profile(String id) {this.id=id;} String getId(){return id;} boolean isActive(){return active;} "
                + "void setActive(boolean active){this.active=active;} }";
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), code -> code.replace("new Profile(\"A\",false)", "new Profile(\"A\")")
                        .replace("new Profile(\"B\",false)", "new Profile(\"B\")"), ignored -> profile)).isEmpty();
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), code -> code.replace("new Profile(\"A\",false)", "new Profile(\"A\",true)"),
                code -> code.replace("boolean active;", "boolean active; void setActive(boolean active){this.active ^= active;}"))).isEmpty();
    }

    @Test
    void skipsDeferredEnrichmentAndRepeatedHelperProvenance() {
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false,
                code -> code.replace("entries.forEach(", "Runnable later=()->entries.forEach("),
                java.util.function.UnaryOperator.identity())).isEmpty();
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false,
                code -> code.replace("browse()", "browse(String ignored)")
                        .replace("enrich(entries);", "enrich(new java.util.ArrayList<Entry>(),\"Y\"); enrich(entries,\"X\");")
                        .replace("enrich(java.util.List<Entry> entries)", "enrich(java.util.List<Entry> entries,String user)")
                        .replace("relations.selected()", "relations.selected(user)"),
                code -> code.replace("Relations relations;", "@org.mockito.Mock Relations relations;")
                        .replace("relations.selected()", "relations.selected(\"Y\")")
                        .replace("service.browse()", "service.browse(\"ignored\")")
                        .replace("Pager<Entry> result=service.browse(\"ignored\");", "Pager<Entry> result=service.browse(\"ignored\"); org.mockito.Mockito.verify(relations).selected(\"Y\");"))).isEmpty();
    }

    @Test
    void requiresExecutedExactVerificationForUnknownArguments() {
        java.util.function.UnaryOperator<String> source = code -> code.replace("browse()", "browse(String user)")
                .replace("enrich(entries);", "enrich(entries,user);")
                .replace("enrich(java.util.List<Entry> entries)", "enrich(java.util.List<Entry> entries,String user)")
                .replace("relations.selected()", "relations.selected(user)");
        java.util.function.UnaryOperator<String> setup = code -> code.replace("Relations relations;", "@org.mockito.Mock Relations relations;")
                .replace("relations.selected()", "relations.selected(\"A\")").replace("service.browse()", "service.browse(\"A\")");
        String afterAct = "Pager<Entry> result=service.browse(\"A\");";
        String verify = "org.mockito.Mockito.verify(relations).selected(\"A\");";
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false, source,
                code -> setup.apply(code).replace(afterAct, afterAct + verify)))
                .isEmpty();
        assertThat(validate("assertTrue(result.getData().get(1).getProfile().isActive());", false, source,
                code -> setup.apply(code).replace(afterAct, afterAct + verify))).isEmpty();
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false, source,
                code -> setup.apply(code).replace(afterAct, afterAct + "Runnable later=()->" + verify)))
                .isNotEmpty().allSatisfy(message -> assertThat(message).contains("verify").doesNotContain("fixture key"));
        assertThat(validate("assertFalse(result.getData().get(1).getProfile().isActive());", false, source,
                code -> setup.apply(code).replace(afterAct, "Relations alias=relations; alias.selected(\"A\");" + afterAct + verify))).isEmpty();
    }

    @Test
    void skipsUnqualifiedOrDelegatingConstructorWrites() {
        String profile = "class Profile { private String id; private boolean active; Profile(String id){this.id=id;active=true;} "
                + "String getId(){return id;} boolean isActive(){return active;} void setActive(boolean active){this.active=active;} }";
        java.util.function.UnaryOperator<String> fixture = code -> code.replace("new Profile(\"A\",false)", "new Profile(\"A\")")
                .replace("new Profile(\"B\",false)", "new Profile(\"B\")");
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), fixture, ignored -> profile)).isEmpty();
        assertThat(validate("assertTrue(result.getData().get(0).getProfile().isActive());", false,
                java.util.function.UnaryOperator.identity(), fixture, ignored -> profile.replace("Profile(String id){this.id=id;active=true;}",
                        "Profile(){this.active=true;} Profile(String id){this();this.id=id;}"))).isEmpty();
    }

    private List<String> validate(String assertion, boolean initiallyActive) {
        return validate(assertion, initiallyActive, java.util.function.UnaryOperator.identity(), java.util.function.UnaryOperator.identity());
    }

    private List<String> validate(String assertion, boolean initiallyActive,
            java.util.function.UnaryOperator<String> sourceTransform, java.util.function.UnaryOperator<String> testTransform) {
        return validate(assertion, initiallyActive, sourceTransform, testTransform, java.util.function.UnaryOperator.identity());
    }

    private List<String> validate(String assertion, boolean initiallyActive,
            java.util.function.UnaryOperator<String> sourceTransform, java.util.function.UnaryOperator<String> testTransform,
            java.util.function.UnaryOperator<String> profileTransform) {
        var classes = List.of(
                type("Profile", profileTransform.apply("@lombok.Data @lombok.AllArgsConstructor class Profile { private String id; private boolean active; }")),
                type("Entry", "@lombok.Data @lombok.AllArgsConstructor class Entry { private Profile profile; }"),
                type("Pager", "@lombok.Getter class Pager<T> { private java.util.List<T> data; Pager(java.util.List<T> data) { this.data=data; } }"),
                type("Service", sourceTransform.apply("""
                        class Service {
                            Reader reader; Relations relations;
                            Pager<Entry> browse() { java.util.List<Entry> entries=reader.read(); enrich(entries); return new Pager<>(entries); }
                            void enrich(java.util.List<Entry> entries) {
                                java.util.Set<String> selected=relations.selected();
                                entries.forEach(entry -> { if (selected.contains(entry.getProfile().getId())) entry.getProfile().setActive(true); });
                            }
                        }
                        """)));
        var context = new UnitTestContextDto(null, null, classes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        String test = """
                package demo;
                import static org.junit.jupiter.api.Assertions.*;
                import static org.mockito.Mockito.when;
                class Check { Reader reader; Relations relations; Service service;
                  void check() {
                    Entry a=new Entry(new Profile("A",false));
                    Entry b=new Entry(new Profile("B",%s));
                    when(reader.read()).thenReturn(java.util.Arrays.asList(b,a));
                    when(relations.selected()).thenReturn(java.util.Collections.singleton("A"));
                    Pager<Entry> result=service.browse();
                    %s
                  }
                }
                """.formatted(initiallyActive, assertion);
        return GeneratedBooleanAssertionValidator.validate(context, "demo.Service", "browse",
                new JavaParser().parse(testTransform.apply(test)).getResult().orElseThrow());
    }

    private ClassContextDto type(String name, String source) {
        return new ClassContextDto(1L, "demo", name, "demo." + name, "OTHER", name + ".java",
                "package demo; " + source, List.of(), List.of());
    }
}
