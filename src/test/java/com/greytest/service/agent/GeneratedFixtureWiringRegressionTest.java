package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.github.javaparser.StaticJavaParser;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;

class GeneratedFixtureWiringRegressionTest {
    @Test void rejectsInventedReflectiveFieldButAcceptsADeclaredPrivateField() {
        String test = "package sample; import org.springframework.test.util.ReflectionTestUtils; "
                + "class Check { Store store = new Store(); void test(){ReflectionTestUtils.setField(store,\"cache\",null);} }";
        assertThat(problems(test, "class Store {}" )).anyMatch(s -> s.contains("field 'cache' does not exist"));
        assertThat(problems(test, "class Store { private Object cache; }" )).isEmpty();
    }

    @Test void acceptsKnownInheritedFieldsAndDoesNotGuessUnknownParents() {
        String test = "package sample; class Check { Store store = new Store(); void test(){"
                + "org.springframework.test.util.ReflectionTestUtils.setField(store,\"cache\",null);} }";
        assertThat(problems(test, "class Store extends Base {}", "class Base { private Object cache; }")).isEmpty();
        assertThat(problems(test, "class Store extends ExternalBase {}" )).isEmpty();
        assertThat(problems(test, "class Store extends Base {}", "class Base {}" )).isNotEmpty();
    }

    @Test void doesNotTreatAProjectUtilityAsSpringReflectionTestUtils() {
        String test = "package sample; class Check { Store store; void test(){ReflectionTestUtils.setField(store,\"cache\",null);} }";
        assertThat(problems(test, "class Store {}" )).isEmpty();
    }

    @Test void usesConcreteRuntimeTypeAndAbstainsWhenItIsUnknownOrReassigned() {
        String test = "package sample; class Check { Base store = new Store(); void test(){"
                + "org.springframework.test.util.ReflectionTestUtils.setField(store,\"cache\",null);} }";
        assertThat(problems(test, "class Store extends Base { private Object cache; }", "class Base {}" )).isEmpty();
        assertThat(problems(test.replace("Base store = new Store()", "Base store"),
                "class Store extends Base { private Object cache; }", "class Base {}" )).isEmpty();
        assertThat(problems(test.replace("void test(){", "void test(){store = new Store();"),
                "class Store extends Base { private Object cache; }", "class Base {}" )).isEmpty();
    }

    @Test void rejectsBooleanStubForDeclaredIntegerReturnWithoutChangingBusinessAssertion() {
        for (String type : List.of("int", "Integer")) {
            String source = "class Store { " + type + " rows(){return 1;} }";
            assertThat(problems(stub("true"), source)).anyMatch(s -> s.contains("thenReturn(boolean) will not compile"));
            assertThat(problems(stub("1"), source)).isEmpty();
        }
        assertThat(problems(stub("true"), "class Store { boolean rows(){return true;} }")).isEmpty();
    }

    @Test void doesNotGuessOverloadsExternalApisOrCustomWhen() {
        assertThat(problems(stub("true"), "class Store { int rows(){return 1;} int rows(int n){return n;} }")).isEmpty();
        assertThat(problems(stub("true"), "class Store extends ExternalBase {}" )).isEmpty();
        assertThat(problems(stub("true").replace("rows()", "rows(\"value\")"),
                "class Store extends Base { int rows(int n){return n;} }",
                "class Base { boolean rows(String s){return true;} }")).isEmpty();
        assertThat(problems(stub("true").replace("void test()", "Object when(Object x){return x;} void test()"),
                "class Store { int rows(){return 1;} }")).isEmpty();
    }

    @Test void rejectsOnlyUnconditionalJUnitFailurePlaceholders() throws Exception {
        String prefix = "package sample; import static org.junit.jupiter.api.Assertions.fail; class Check { ";
        assertThat(semanticProblems("validatePlaceholderHelpers", prefix + "void check(){fail(\"no public entry\");} }")).isNotEmpty();
        assertThat(semanticProblems("validatePlaceholderHelpers", "class Check { void check(){org.junit.jupiter.api.Assertions.fail(\"missing\");} }")).isNotEmpty();
        assertThat(semanticProblems("validatePlaceholderHelpers", prefix + "void check(){service.run();fail(\"expected exception\");} }")).isEmpty();
        assertThat(semanticProblems("validatePlaceholderHelpers", prefix + "void fail(String s){} void check(){fail(\"business call\");} }")).isEmpty();
    }

    @Test void rejectsStaticImportsOfTopLevelAnnotationsButKeepsAssertionImports() throws Exception {
        String code = "import static org.junit.jupiter.api.extension.ExtendWith; import static org.junit.jupiter.api.Assertions.assertEquals; "
                + "class Check { void check(){assertEquals(1,1);} }";
        assertThat(semanticProblems("validateAssertionImports", code)).anyMatch(s -> s.contains("normal type import"));
        assertThat(semanticProblems("validateAssertionImports", code.replace("import static org.junit.jupiter.api.extension.ExtendWith;", 
                "import org.junit.jupiter.api.extension.ExtendWith;"))).isEmpty();
    }

    private List<String> semanticProblems(String name, String code) throws Exception {
        var unit = new GeneratedUnitTestDto(1L, "Check", "check", "sample", "NEW_TEST", code);
        var errors = new java.util.ArrayList<String>();
        if (name.equals("validateAssertionImports")) {
            var method = GeneratedUnitTestSemanticValidator.class.getDeclaredMethod(name, GeneratedUnitTestDto.class,
                    ProjectJavaVersionDetector.TestFramework.class, List.class);
            method.setAccessible(true); method.invoke(null, unit, ProjectJavaVersionDetector.TestFramework.JUNIT5, errors);
        } else {
            var method = GeneratedUnitTestSemanticValidator.class.getDeclaredMethod(name, GeneratedUnitTestDto.class, List.class);
            method.setAccessible(true); method.invoke(null, unit, errors);
        }
        return errors;
    }

    private String stub(String value) {
        return "package sample; import static org.mockito.Mockito.when; class Check { Store store; "
                + "void test(){when(store.rows()).thenReturn(" + value + ");} }";
    }

    private List<String> problems(String test, String... sources) {
        var classes = java.util.stream.IntStream.range(0, sources.length).mapToObj(index -> {
            String source = "package sample; " + sources[index];
            String name = StaticJavaParser.parse(source).getType(0).getNameAsString();
            return new ClassContextDto((long) index + 1, "sample", name, "sample." + name,
                    "SERVICE", name + ".java", source, List.of(), List.of());
        }).toList();
        var context = new UnitTestContextDto(null, null, classes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return GeneratedTestApiValidator.validate(context, StaticJavaParser.parse(test));
    }
}
