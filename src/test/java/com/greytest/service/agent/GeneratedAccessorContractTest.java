package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.github.javaparser.JavaParser;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

class GeneratedAccessorContractTest {
    @Test
    void followsDtoGetterThroughKnownMarkerInterface() {
        var base = context("@lombok.Data class Profile { private boolean active; }", "Profile");
        var context = new UnitTestContextDto(null, null, List.of(base.classes().get(0),
                type("Item", "@lombok.Data class Item implements Node { private Profile profile; }"),
                type("Node", "interface Node {}")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(validate(context, "java.util.Optional<Item> result; void check() { result.get().getProfile().getActive(); }"))
                .anySatisfy(problem -> assertThat(problem).contains("getActive", "isActive"));
    }

    @Test
    void doesNotResolveInheritedEnumAsAnUnrelatedDto() {
        var context = new UnitTestContextDto(null, null, List.of(
                type("Profile", "@lombok.Data class Profile { private boolean active; }"),
                type("Item", "@lombok.Data class Item implements Node { private Profile profile; }"),
                type("Node", "interface Node { enum Profile { A; public boolean getActive(){return true;} } }")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(validate(context, "Item item; void check(){item.getProfile().getActive();}")).isEmpty();
    }

    @Test
    void honorsLombokSettingsAndDoesNotGuessWhenConfigurationIsUnverified() {
        var base = context("@lombok.Data class Profile { private boolean active; }", "Profile");
        var configured = new UnitTestContextDto(null, null, base.classes(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto("LOMBOK_CONFIGURATION", "lombok.config",
                        "lombok.getter.noIsPrefix = true")), List.of());
        assertThat(validate(configured, "Item result; void check() { result.getProfile().getActive(); }")).isEmpty();
        assertThat(validate(configured, "Item result; void check() { result.getProfile().isActive(); }")).isNotEmpty();
        var unverified = new UnitTestContextDto(null, null, base.classes(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of("Lombok configuration imports are not resolved: lombok.config"));
        assertThat(validate(unverified, "Item result; void check() { result.getProfile().getActive(); }")).isEmpty();
    }

    @Test
    void rejectsWrongBooleanGetterThroughOptionalAndNestedDto() {
        var context = context("@lombok.Data class Profile { private boolean active; }", "Profile");
        assertThat(validate(context, "java.util.Optional<Item> result; void check() { result.get().getProfile().getActive(); }"))
                .anySatisfy(problem -> assertThat(problem).contains("getActive", "isActive"));
        assertThat(validate(context, "java.util.Optional<Item> result; void check() { result.get().getProfile().isActive(); }"))
                .isEmpty();
    }

    @Test
    void resolvesGenericPagerListBeforeCheckingBooleanAccessor() {
        var base = context("@lombok.Data class Profile { private boolean active; }", "Profile");
        var classes = new java.util.ArrayList<>(base.classes());
        classes.add(type("Pager", "class Pager<T> { java.util.List<T> getData() { return null; } }"));
        var context = new UnitTestContextDto(null, null, classes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(validate(context, "Pager<Item> result; void check() { result.getData().get(0).getProfile().getActive(); }"))
                .anySatisfy(problem -> assertThat(problem).contains("getActive", "isActive"));
    }

    @Test
    void preservesExplicitAndWrapperAccessorsAndSkipsUnresolvedInheritance() {
        assertThat(validate(context("@lombok.Data class Profile { private boolean active; public boolean getActive() { return active; } }", "Profile"),
                "Item result; void check() { result.getProfile().getActive(); }")).isEmpty();
        assertThat(validate(context("@lombok.Data class Profile { private Boolean active; }", "Profile"),
                "Item result; void check() { result.getProfile().getActive(); }")).isEmpty();
        assertThat(validate(context("@lombok.Data class Profile extends Parent { private boolean active; }", "Profile"),
                "Item result; void check() { result.getProfile().getActive(); }")).isEmpty();
    }

    private UnitTestContextDto context(String declaration, String name) {
        return new UnitTestContextDto(null, null, List.of(type(name, declaration),
                type("Item", "@lombok.Data class Item { private Profile profile; }")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private ClassContextDto type(String name, String source) {
        return new ClassContextDto(1L, "demo", name, "demo." + name, "OTHER", name + ".java",
                "package demo; " + source, List.of(), List.of());
    }

    private List<String> validate(UnitTestContextDto context, String body) {
        return GeneratedTestApiValidator.validate(context, new JavaParser().parse("package demo; class Check { " + body + " }")
                .getResult().orElseThrow());
    }
}
