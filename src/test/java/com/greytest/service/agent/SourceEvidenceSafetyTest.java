package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;

/** Kiểm tra không biến suy luận thiếu bằng chứng thành lỗi giả của BR/TC. */
class SourceEvidenceSafetyTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Ném IllegalArgumentException khi template \"welcome\" không tồn tại.",
            "Ném IllegalArgumentException khi mẫu \"welcome\" không tồn tại.",
            "IllegalArgumentException khi template \"welcome\" không tồn tại, message \"Unknown template\".",
            "IllegalArgumentException khi template không tồn tại, message \"Unknown template\"."})
    void doesNotInterpretDomainTemplatesAsExceptionMessageTemplates(String claim) {
        var classes = sources("void run() { throw new IllegalArgumentException(\"Unknown template\"); }", "");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of(), true)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "`", "new ", "`new "})
    void distinguishesConstructorArgumentsFromTheRenderedExceptionMessage(String constructorPrefix) {
        var classes = resourceNotFoundSources();
        String claim = "Method ném ResourceNotFoundException với message được tạo bởi constructor "
                + constructorPrefix + "ResourceNotFoundException(\"Appointment\", \"id\", 999L)"
                + (constructorPrefix.startsWith("`") ? "`" : "")
                + ", tức chuỗi \"Appointment not found with id: '999'\".";
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of("id", 999), true))
                .isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "`", "new ", "`new "})
    void stillRejectsAnIncorrectMessageAfterAConstructorDescription(String constructorPrefix) {
        var classes = resourceNotFoundSources();
        String claim = "ResourceNotFoundException với message được tạo bởi constructor "
                + constructorPrefix + "ResourceNotFoundException(\"Appointment\", \"id\", 999L)"
                + (constructorPrefix.startsWith("`") ? "`" : "") + ", tức chuỗi \"Appointment missing\".";
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of("id", 999), true))
                .hasValueSatisfying(error -> assertThat(error).contains("quoted message \"Appointment missing\""));
    }

    @Test
    void preservesConstructorLikeTextInsideTheActualMessage() {
        var classes = sources("void run() { throw new IllegalArgumentException(\"ResourceNotFoundException(Appointment)\"); }", "");
        var evidence = new SourceExceptionMessageEvidence(classes);
        assertThat(evidence.validate(classes, 1L,
                "IllegalArgumentException message \"ResourceNotFoundException(Appointment)\"", Map.of(), true)).isEmpty();
        assertThat(evidence.validate(classes, 1L,
                "IllegalArgumentException message \"ResourceNotFoundException(Other)\"", Map.of(), true)).isPresent();
    }

    private List<ClassContextDto> resourceNotFoundSources() {
        return sources("void run(Long id) { throw new ResourceNotFoundException(\"Appointment\", \"id\", id); }",
                "static class ResourceNotFoundException extends RuntimeException { "
                + "ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) { "
                + "super(String.format(\"%s not found with %s: '%s'\", resourceName, fieldName, fieldValue)); } }");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"IllegalArgumentException message does not contain \"other\"",
            "IllegalArgumentException có thông điệp không chứa \"other\""})
    void verifiesAnExplicitNegativeSubstringAgainstAKnownMessage(String claim) {
        var classes = sources("void run(String username) { throw new IllegalArgumentException(\"User with username: \" + username + \" not found\"); }", "");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of("username", "minh"), true)).isEmpty();
    }

    @Test void rejectsANegativeSubstringContradictedBySourceAndRequiresProofForUnknownParts() {
        var classes = sources("void run(String username) { throw new IllegalArgumentException(\"User with username: \" + username + \" not found\"); }", "");
        var evidence = new SourceExceptionMessageEvidence(classes);
        assertThat(evidence.validate(classes, 1L, "IllegalArgumentException message does not contain \"minh\"", Map.of("username", "minh"), true)).isPresent();
        assertThat(evidence.validate(classes, 1L, "IllegalArgumentException message does not contain \"other\"", Map.of(), true)).isPresent();
    }

    @Test void unknownRuntimeTextDoesNotDisproveAnAlreadyApprovedSubstring() {
        var classes = sources("void run(String username) { throw new IllegalArgumentException(\"User with username: \" + username + \" not found\"); }", "");
        var evidence = new SourceExceptionMessageEvidence(classes);
        String claim = "IllegalArgumentException message contains \"minh\"";
        assertThat(evidence.validate(classes, 1L, claim, Map.of(), false)).isEmpty();
        assertThat(evidence.validate(classes, 1L, claim, Map.of(), true)).isPresent();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"IllegalArgumentException message contains username \"minh\"",
            "IllegalArgumentException có thông điệp chứa username \"minh\""})
    void verifiesAnExplicitMessageSubstringWithoutTreatingItAsTheWholeMessage(String claim) {
        var classes = sources("void run(String username) { throw new IllegalArgumentException(\"User with username: \" + username + \" not found\"); }", "");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of("username", "minh"), true)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"IllegalArgumentException message \"minh\"",
            "IllegalArgumentException message contains username \"other\""})
    void stillRejectsAnIncorrectExactMessageOrUnsupportedSubstring(String claim) {
        var classes = sources("void run(String username) { throw new IllegalArgumentException(\"User with username: \" + username + \" not found\"); }", "");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of("username", "minh"), true)).isPresent();
    }

    @Test void recognizesAnExplicitRepositoryFieldThroughOptionalMap() {
        var classes = sources("void run(Long id) { this.repo.findById(id).map(v -> v).orElseThrow(() -> new IllegalArgumentException(\"Missing: \" + id)); }",
                "Repo repo; interface Repo extends org.springframework.data.jpa.repository.JpaRepository<String, Long> {} ");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"Missing: 7\"", Map.of("id", 7), true)).isEmpty();
    }

    @Test void resolvesTheStringOverloadOfACustomExceptionConstructor() {
        var classes = sources("void run(String name) { throw new MissingException(\"Missing: \" + name); }",
                "static class MissingException extends RuntimeException { MissingException(String message) { super(message); } MissingException(Throwable cause) { super(cause); } }");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "MissingException message \"Missing: user\"", Map.of("name", "user"), true)).isEmpty();
    }

    @Test void doesNotGuessLocaleDependentDecimalFormatting() {
        var classes = sources("void run(Integer id) { throw new IllegalArgumentException(String.format(\"Missing: %d!\", id)); }", "");
        var evidence = new SourceExceptionMessageEvidence(classes);
        assertThat(evidence.validate(classes, 1L, "IllegalArgumentException message \"Missing: 42!\"", Map.of("id", 42), true)).isPresent();
        assertThat(evidence.validate(classes, 1L, "IllegalArgumentException message \"Missing: \u0664\u0662!\"", Map.of("id", 42), true)).isPresent();
    }

    @Test void acceptsOnlyProvenOptionalReceiversIncludingInheritedJpaFindById() {
        String method = "void run(Long id) { repo.findById(id).orElseThrow(() -> new IllegalArgumentException(\"Missing: \" + id)); }";
        var classes = sources(method, "Repo repo; interface Repo extends org.springframework.data.jpa.repository.JpaRepository<String, Long> {} ");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"Missing: 7\"", Map.of("id", 7), true)).isEmpty();
        classes = sources(method, "Repo repo; class Repo { Object findById(Long id) { return null; } } ");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"Missing: 7\"", Map.of("id", 7), true)).isPresent();
    }

    @Test void aKnownLiteralIsNotHiddenByAnotherUnknownMessageOfTheSameType() {
        var classes = sources("void run(boolean ok) { if(ok) throw new IllegalArgumentException(\"known\"); throw new IllegalArgumentException(dynamic()); }", "String dynamic() { return \"runtime\"; }");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"known\"", Map.of(), true)).isEmpty();
    }

    @Test void aShadowedJpaRepositoryFieldDoesNotProveTheParameterReturnsOptional() {
        var classes = sources("void run(FakeRepo repo, Long id) { repo.findById(id).orElseThrow(() -> new IllegalArgumentException(\"not escaping\")); }",
                "Repo repo; interface Repo extends org.springframework.data.jpa.repository.JpaRepository<String, Long> {} "
                + "class FakeRepo { Box findById(Long id) { return null; } } "
                + "class Box { Object orElseThrow(java.util.function.Supplier<IllegalArgumentException> supplier) { return null; } }");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"not escaping\"", Map.of(), true)).isPresent();
    }

    @Test void anOutOfScopeLaterLocalDoesNotProveTheFieldReturnsOptional() {
        var classes = sources("void run(Long id) { repo.findById(id).orElseThrow(() -> new IllegalArgumentException(\"not escaping\")); { Repo repo = null; } }",
                "FakeRepo repo; interface Repo extends org.springframework.data.jpa.repository.JpaRepository<String, Long> {} "
                + "class FakeRepo { Box findById(Long id) { return null; } } "
                + "class Box { Object orElseThrow(java.util.function.Supplier<IllegalArgumentException> supplier) { return null; } }");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"not escaping\"", Map.of(), true)).isPresent();
    }

    @Test void aCatchParameterCannotBorrowTheJpaFieldType() {
        var classes = sources("void run(Long id) { try { } catch (FakeRepo repo) { repo.findById(id).orElseThrow(() -> new IllegalArgumentException(\"not escaping\")); } }",
                "Repo repo; interface Repo extends org.springframework.data.jpa.repository.JpaRepository<String, Long> {} "
                + "class FakeRepo extends RuntimeException { Box findById(Long id) { return null; } } "
                + "class Box { Object orElseThrow(java.util.function.Supplier<IllegalArgumentException> supplier) { return null; } }");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"not escaping\"", Map.of(), true)).isPresent();
    }

    @Test void anAnonymousClassFieldIsNotALocalOfTheEnclosingMethod() {
        var classes = sources("void run(Long id) { new Object() { Repo repo; }; repo.findById(id).orElseThrow(() -> new IllegalArgumentException(\"not escaping\")); }",
                "FakeRepo repo; interface Repo extends org.springframework.data.jpa.repository.JpaRepository<String, Long> {} "
                + "class FakeRepo { Box findById(Long id) { return null; } } "
                + "class Box { Object orElseThrow(java.util.function.Supplier<IllegalArgumentException> supplier) { return null; } }");
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, 1L,
                "IllegalArgumentException message \"not escaping\"", Map.of(), true)).isPresent();
    }
    @Test void detectsAProvenListArgumentConflict() {
        assertThat(collection("void run(List<String> ids) { Collections.reverse(ids); sink.read(ids); }",
                "", Map.of("ids", List.of("a", "b")), List.of("a", "b"))).isPresent();
    }

    @Test void detectsAProvenStreamProjectionConflict() {
        assertThat(collection("void run(List<Item> items) { var ids = items.stream().map(Item::getId); Collections.reverse(items); sink.read(ids.collect(Collectors.toList())); }",
                "class Item { String id; String getId() { return id; } }",
                Map.of("items", List.of(Map.of("id", "a"), Map.of("id", "b"))), List.of("a", "b"))).isPresent();
    }
    @Test void doesNotTreatSetRemoveAsListIndexRemoval() {
        assertThat(collection("void run(Set<Integer> numbers) { numbers.remove(0); sink.read(numbers); }",
                "", Map.of("numbers", List.of(1, 2)), List.of(1, 2))).isEmpty();
    }

    @Test void evaluatesStreamProjectionAtTheTerminalOperation() {
        assertThat(collection("void run(List<Item> items) { var ids = items.stream().map(Item::getId); Collections.reverse(items); sink.read(ids.collect(Collectors.toList())); }",
                "class Item { String id; String getId() { return id; } }",
                Map.of("items", List.of(Map.of("id", "a"), Map.of("id", "b"))), List.of("b", "a"))).isEmpty();
    }

    @Test void doesNotExecuteANonTerminalStreamLambda() {
        assertThat(collection("void run(List<String> ids) { Collections.reverse(ids); ids.stream().map(x -> sink.read(ids)); }",
                "", Map.of("ids", List.of("a", "b")), List.of("a", "b"))).isEmpty();
    }

    @Test void doesNotGuessReferenceEqualityForStrings() {
        assertThat(collection("void run(List<String> ids, String direction) { if (direction == \"PREV\") Collections.reverse(ids); sink.read(ids); }",
                "", Map.of("ids", List.of("a", "b"), "direction", "PREV"), List.of("a", "b"))).isEmpty();
    }

    @Test void preservesListAliasingAcrossRepeatedGetters() {
        assertThat(collection("void run(Holder holder) { Collections.reverse(holder.getIds()); sink.read(holder.getIds()); }",
                "class Holder { List<String> ids; List<String> getIds() { return ids; } }",
                Map.of("holder", Map.of("ids", List.of("a", "b"))), List.of("b", "a"))).isEmpty();
    }

    @Test void stopsAtAnUnknownCallThatMightMutateTheCollection() {
        assertThat(collection("void run(List<String> ids) { var ignored = External.rotate(ids); Collections.reverse(ids); sink.read(ids); }",
                "", Map.of("ids", List.of("a", "b")), List.of("a", "b"))).isEmpty();
    }

    @Test void stopsAtAConstructorThatMightMutateTheCollection() {
        assertThat(collection("void run(List<String> ids) { var ignored = new Rotator(ids); Collections.reverse(ids); sink.read(ids); }",
                "class Rotator { Rotator(List<String> ids) { Collections.reverse(ids); } }",
                Map.of("ids", List.of("a", "b")), List.of("a", "b"))).isEmpty();
    }

    @Test void doesNotSelectAPrivateOverloadByIgnoringThePublicOverload() {
        assertThat(collection("void run(List<String> ids) { dispatch(ids, true); }",
                "public void dispatch(List<String> ids, boolean flag) { sink.read(ids); } "
                + "private void dispatch(List<String> ids, int flag) { Collections.reverse(ids); sink.read(ids); }",
                Map.of("ids", List.of("a", "b")), List.of("a", "b"))).isEmpty();
    }

    @Test void doesNotCompareJsonNumbersInsteadOfRoundedJavaFloats() {
        assertThat(collection("void run(List<String> ids, float a, float b) { if (a > b) Collections.reverse(ids); sink.read(ids); }",
                "", Map.of("ids", List.of("a", "b"), "a", 16777217, "b", 16777216), List.of("a", "b"))).isEmpty();
    }

    @Test void doesNotResolveAStaticUtilityWhenAParameterShadowsItsName() {
        assertThat(collection("void run(List<String> ids, Object Collections) { Collections.reverse(ids); sink.read(ids); }",
                "", Map.of("ids", List.of("a", "b"), "Collections", Map.of()), List.of("a", "b"))).isEmpty();
    }

    @Test void doesNotResolvePrivateHelpersAcrossAnUnprovenHierarchy() {
        String method = "void run(List<String> ids) { dispatch(ids, true); }";
        var original = sources(method, "Sink sink; interface Sink { void read(List<String> ids); } "
                + "private void dispatch(List<String> ids, int flag) { Collections.reverse(ids); sink.read(ids); }").get(0);
        var source = new ClassContextDto(original.id(), original.packageName(), original.className(), original.qualifiedName(),
                original.classType(), original.filePath(), original.sourceCode().replace("class Service {", "class Service extends Base {"),
                original.annotations(), original.methods());
        var tc = new GeneratedTestCaseDto(1L, "HAPPY_PATH", "case", null,
                Map.of("input", Map.of("ids", List.of("a", "b")), "mocks", Map.of("sink.read", Map.of("arguments", List.of(List.of("a", "b"))))),
                "Expected result", "HIGH", "SOURCE");
        assertThat(new SourceCollectionCallEvidence(new SourceEvidenceIndex(List.of(source)), tc).validate(source.methods().get(0))).isEmpty();
    }

    @Test void doesNotUseInitialInputAsTheValueOfAMutatedExceptionParameter() {
        assertThat(message("void run(int n) { n++; throw new IllegalArgumentException(\"n=\" + n); }",
                "IllegalArgumentException message \"n=2\"", Map.of("n", 1))).isEmpty();
    }

    @Test void associatesEachQuotedMessageWithItsOwnException() {
        assertThat(message("void run(int n) { if(n == 0) throw new IllegalArgumentException(\"empty\"); throw new IllegalStateException(\"bad\"); }",
                "IllegalArgumentException message \"empty\"; IllegalStateException message \"bad\"", Map.of())).isEmpty();
    }

    @Test void doesNotTreatACauseExceptionAsTheThrownException() {
        assertThat(message("void run() { throw new IllegalStateException(new IllegalArgumentException(\"cause\")); }",
                "IllegalArgumentException message \"unrelated\"", Map.of())).isEmpty();
    }

    @Test void retainsARealPeriodAndRejectsItsRemoval() {
        assertThat(message("void run() { throw new IllegalArgumentException(\"bad.\"); }",
                "IllegalArgumentException message \"bad\"", Map.of())).isPresent();
    }

    @Test void checksManyUnknownMessageSegmentsWithoutRegexBacktracking() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertThat(message("void run(String a, String b, String c, String d, String e, String f, String g, String h) { "
                    + "throw new IllegalArgumentException(String.format(\"%s%s%s%s%s%s%s%s!\", a,b,c,d,e,f,g,h)); }",
                    "IllegalArgumentException message \"" + "a".repeat(2000) + "\"", Map.of())).isPresent();
        });
    }

    private Optional<String> collection(String method, String supporting, Map<String, Object> input, List<?> expected) {
        var classes = sources(method, "Sink sink; " + supporting + " interface Sink { List<String> read(List<String> ids); } ");
        var tc = new GeneratedTestCaseDto(1L, "HAPPY_PATH", "case", null,
                Map.of("input", input, "mocks", Map.of("sink.read", Map.of("arguments", List.of(expected)))),
                "Returns the expected result", "HIGH", "SOURCE");
        return new SourceCollectionCallEvidence(new SourceEvidenceIndex(classes), tc).validate(classes.get(0).methods().get(0));
    }

    private Optional<String> message(String method, String claim, Map<String, Object> input) {
        var classes = sources(method, "");
        return new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, input, false);
    }

    private List<ClassContextDto> sources(String method, String supporting) {
        var dto = new MethodContextDto(1L, "sample.Service", "run", "void", List.of(), List.of(), "PUBLIC", method,
                1, 1, List.of(), List.of(), List.of());
        return List.of(new ClassContextDto(1L, "sample", "Service", "sample.Service", "SERVICE", "Service.java",
                "package sample; import java.util.*; import java.util.stream.Collectors; class Service { " + supporting + method + " }",
                List.of(), List.of(dto)));
    }
}
