package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Không chặn nhầm ngoại lệ Spring/helper, đồng thời không nhận thông báo thiếu bằng chứng. */
class HelperExceptionMessageEvidenceTest {
    private static final String API = "static class ApiException extends RuntimeException { "
            + "ApiException(Object code) { super(\"other\"); } ApiException(String message) { super(message); } }";
    private static final String ASSERTS = "static class Asserts { "
            + "static void fail(Object code) { throw new ApiException(code); } "
            + "static void fail(String message) { throw new ApiException(message); } }";

    private static final String RESULT_ENUM = "enum Result { DENIED(\"无权限！\"), OK(\"success\"); private String result; "
            + "Result(String result) { this.result = result; } String getResult() { return result; } "
            + "void setResult(String result) { this.result = result; } } ";

    @Test void provesMessageFromAnEnumConstantGetter() {
        var classes = sources("void run() { Asserts.fail(Result.DENIED.getResult()); }", API + ASSERTS + RESULT_ENUM);
        assertThat(validate(classes, "ApiException message \"无权限！\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message \"success\" ")).isPresent();
    }

    @Test void provesEnumMessageThroughAStaticFailOnAnExceptionExtendingRuntimeException() {
        var mall = "static class MallException extends RuntimeException { MallException(String message) { super(message); } "
                + "static void fail(String message) { throw new MallException(message); } } ";
        var classes = sources("void run() { MallException.fail(Result.DENIED.getResult()); }", mall + RESULT_ENUM);
        assertThat(validate(classes, "MallException message \"无权限！\" ")).isEmpty();
        assertThat(validate(classes, "MallException message \"success\" ")).isPresent();
    }

    @Test void doesNotTrustAStaticHelperInheritedFromAProjectBaseClass() {
        var classes = sources("void run() { MallException.fail(\"bad\"); }",
                "static class Base extends RuntimeException { } static class MallException extends Base { "
                + "static void fail(String message) { throw new MallException(); } } ");
        assertThat(validate(classes, "MallException message \"bad\" ")).isPresent();
    }

    @Test void provesTheLiteralPartOfAMessageConcatenatedWithALocalVariable() {
        var classes = sources("void run(String raw) { String isbn = raw.trim(); throw new ApiException(\"ISBN exists: \" + isbn); }", API);
        assertThat(validate(classes, "ApiException message \"ISBN exists: 978\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message equals the source expression \"ISBN exists: \" + isbn")).isEmpty();
        assertThat(validate(classes, "ApiException message \"Duplicate ISBN: 978\" ")).isPresent();
    }

    @Test void doesNotInventAMessageWhenTheWholeMessageIsALocalVariable() {
        var classes = sources("void run(String raw) { String text = raw.trim(); throw new ApiException(text); }", API);
        assertThat(validate(classes, "ApiException message \"anything\" ")).isPresent();
    }

    @Test void readsMessagesThrownInsideAMultiStatementPrivateHelper() {
        var classes = sources("void run(int capacity) { if (capacity < 0) throw new ApiException(\"Too small\"); assertValid(capacity); }", API
                + "private static final int MAX = 200; private void assertValid(int capacity) { if (capacity > MAX) { "
                + "throw new ApiException(\"Capacity up to \" + MAX); } }");
        assertThat(validate(classes, "ApiException message \"Capacity up to 200\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message \"Too small\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message \"Invented\" ")).isPresent();
    }

    @Test void followsNestedPrivateHelpers() {
        var classes = sources("void run() { first(); }", API
                + "private void first() { second(); } private void second() { if (true) throw new ApiException(\"Deep\"); }");
        assertThat(validate(classes, "ApiException message \"Deep\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message \"Other\" ")).isPresent();
    }

    @Test void doesNotBlockWhenAHelperMessageCannotBeRead() {
        var classes = sources("void run(String raw) { if (raw == null) throw new ApiException(\"Null\"); check(raw); }", API
                + "private void check(String text) { if (text.isEmpty()) throw new ApiException(text); }");
        assertThat(validate(classes, "ApiException message \"Whatever the helper builds\" ")).isEmpty();
    }

    @Test void doesNotTrustAnEnumValueRewrittenThroughItsSetter() {
        var classes = sources("void run() { Result.DENIED.setResult(\"changed\"); Asserts.fail(Result.DENIED.getResult()); }",
                API + ASSERTS + RESULT_ENUM);
        assertThat(validate(classes, "ApiException message \"无权限！\" ")).isPresent();
    }

    @Test void doesNotTrustAnEnumGetterThatIsNotAPlainFieldRead() {
        var classes = sources("void run() { Asserts.fail(Result.DENIED.getResult()); }", API + ASSERTS
                + "enum Result { DENIED(\"a\"); private final String result; Result(String result) { this.result = result; } "
                + "String getResult() { return result + \"b\"; } } ");
        assertThat(validate(classes, "ApiException message \"a\" ")).isPresent();
    }

    @Test void doesNotTrustAConstantWithItsOwnBody() {
        var classes = sources("void run() { Asserts.fail(Result.DENIED.getResult()); }", API + ASSERTS
                + "enum Result { DENIED(\"a\") { String getResult() { return \"b\"; } }; private final String result; "
                + "Result(String result) { this.result = result; } String getResult() { return result; } } ");
        assertThat(validate(classes, "ApiException message \"a\" ")).isPresent();
    }

    @Test void acceptsSpringUsernameMessageButRejectsInventedOrChangedUnicodeText() {
        var classes = sources("void run() { throw new UsernameNotFoundException(\"用户名或密码错误\"); }", "");
        assertThat(validate(classes, "UsernameNotFoundException message \"用户名或密码错误\" ")).isEmpty();
        assertThat(validate(classes, "UsernameNotFoundException message \"User not found\" ")).isPresent();
    }

    @Test void provesOverloadedStaticHelperUsingItsStringArgument() {
        var classes = sources("void run() { Asserts.fail(\"密码不正确\"); }", API + ASSERTS);
        assertThat(validate(classes, "ApiException message \"密码不正确\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message \"other\" ")).isPresent();
    }

    @Test void followsPrivateHelpersAndStaticForwardingWithBoundArguments() {
        var classes = sources("void run() { reject(\"bad\"); }", API + ASSERTS
                + "private void reject(String text) { forward(text); } "
                + "private static void forward(String message) { Asserts.fail(message); }");
        assertThat(validate(classes, "ApiException message \"bad\" ")).isEmpty();
        assertThat(validate(classes, "ApiException message \"different\" ")).isPresent();
    }

    /** Helper không đọc được message: chưa đủ để nói phát biểu đúng hay sai, nên không chặn và chờ người duyệt. */
    @Test void doesNotBlockOnAnOverridableOrRewrittenHelperMessage() {
        var overridable = sources("void run() { reject(\"bad\"); }", API
                + "public void reject(String message) { throw new ApiException(message); }");
        assertThat(validate(overridable, "ApiException message \"bad\" ")).isEmpty();
        var rewritten = sources("void run() { reject(\"bad\"); }", API
                + "private void reject(String message) { message = \"changed\"; throw new ApiException(message); }");
        assertThat(validate(rewritten, "ApiException message \"bad\" ")).isEmpty();
    }

    @Test void doesNotTreatAnInstanceVariableNamedAfterAClassAsAStaticCall() {
        var classes = sources("void run(Asserts Asserts) { Asserts.fail(\"bad\"); }", API
                + "static class Asserts { void fail(String message) { throw new ApiException(message); } }");
        assertThat(validate(classes, "ApiException message \"bad\" ")).isPresent();
    }

    @Test void resolvesOverloadsBeforeDiscardingOverridableMethods() {
        var classes = sources("void run() { reject(\"input\"); }", API
                + "public void reject(String text) { throw new ApiException(\"actual\"); } "
                + "private void reject(Object value) { throw new ApiException(\"wrong\"); }");
        assertThat(validate(classes, "ApiException message \"wrong\" ")).isPresent();
    }

    @Test void doesNotInferMessagesFromAnIncompleteSetOfInheritedOverloads() {
        var classes = new ArrayList<>(sources("void run() { reject(\"input\"); }", API
                + "private void reject(Object text) { throw new ApiException(\"wrong\"); }"));
        var original = classes.get(0);
        classes.set(0, new ClassContextDto(original.id(), original.packageName(), original.className(),
                original.qualifiedName(), original.classType(), original.filePath(),
                original.sourceCode().replace("class Service {", "class Service extends Base {"),
                original.annotations(), original.methods()));
        classes.add(new ClassContextDto(2L, "sample", "Base", "sample.Base", "CLASS", "Base.java",
                "package sample; class Base { public void reject(String text) { throw new RuntimeException(\"actual\"); } }",
                List.of(), List.of()));
        assertThat(validate(classes, "ApiException message \"wrong\" ")).isPresent();
    }

    @Test void doesNotBlockOnAVarargsHelperMessage() {
        var classes = sources("void run() { reject(\"bad\"); }", API
                + "private static void reject(String... message) { throw new ApiException(message); }");
        assertThat(validate(classes, "ApiException message \"bad\" ")).isEmpty();
    }

    @Test void doesNotInferTheBaseConstructorMessageForAnAnonymousExceptionSubclass() {
        var helper = sources("void run() { Asserts.fail(\"bad\"); }", API
                + "static class Asserts { static void fail(String message) { throw new ApiException(message) { "
                + "public String getMessage() { return \"changed\"; } }; } }");
        assertThat(validate(helper, "ApiException message \"bad\" ")).isPresent();
        var direct = sources("void run() { throw new UsernameNotFoundException(\"bad\") { "
                + "public String getMessage() { return \"changed\"; } }; }", "");
        assertThat(validate(direct, "UsernameNotFoundException message \"bad\" ")).isPresent();
    }

    @Test void doesNotUseANestedLambdaOrRecursiveHelperAsProof() {
        var deferred = sources("void run() { Runnable action = () -> Asserts.fail(\"bad\"); }", API + ASSERTS);
        assertThat(validate(deferred, "ApiException message \"bad\" ")).isPresent();
        var cycle = sources("void run() { reject(\"bad\"); }", API
                + "private void reject(String message) { reject(message); }");
        assertThat(validate(cycle, "ApiException message \"bad\" ")).isPresent();
    }

    @Test void doesNotAssumeThatEveryExternalExceptionPreservesTheFirstArgument() {
        var classes = sources("void run() { throw new OtherException(\"bad\"); }", "");
        assertThat(validate(classes, "OtherException message \"bad\" ")).isPresent();
    }

    @Test void prefersProjectSourceOverTheKnownFrameworkContract() {
        var classes = new ArrayList<>(sources("void run() { throw new UsernameNotFoundException(\"input\"); }", ""));
        classes.add(new ClassContextDto(2L, "org.springframework.security.core.userdetails", "UsernameNotFoundException",
                "org.springframework.security.core.userdetails.UsernameNotFoundException", "CLASS", "UsernameNotFoundException.java",
                "package org.springframework.security.core.userdetails; public class UsernameNotFoundException extends RuntimeException { "
                + "public UsernameNotFoundException(String input) { super(\"changed\"); } }", List.of(), List.of()));
        assertThat(validate(classes, "UsernameNotFoundException message \"input\" ")).isPresent();
        assertThat(validate(classes, "UsernameNotFoundException message \"changed\" ")).isEmpty();
    }

    private Optional<String> validate(List<ClassContextDto> classes, String claim) {
        return new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, Map.of(), true);
    }

    private List<ClassContextDto> sources(String method, String supporting) {
        var dto = new MethodContextDto(1L, "sample.Service", "run", "void", List.of(), List.of(), "PUBLIC",
                method, 1, 1, List.of(), List.of(), List.of());
        return List.of(new ClassContextDto(1L, "sample", "Service", "sample.Service", "SERVICE", "Service.java",
                "package sample; import org.springframework.security.core.userdetails.UsernameNotFoundException; "
                + "import external.OtherException; class Service { " + method + supporting + " }", List.of(), List.of(dto)));
    }
}
