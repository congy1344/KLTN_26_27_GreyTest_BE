package com.greytest.service.diff;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.service.analysis.JavaParserHelper;

class DependencySourceDiffTest {
    @TempDir Path temp;
    private final SourceDiffService service = new SourceDiffService(new JavaParserHelper());

    @Test void changedCollaboratorMarksOnlyTypedServiceCaller() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { private Dao dao; int target(int x) { return dao.read(x); } int independent() { return 8; } }");
        both("src/main/java/example/Other.java", "package example; class Other { int read(int x) { return x; } }");
        both("src/main/java/example/Dao.java", "package example; class Dao { int read(int x) { return x; } }");
        candidate("src/main/java/example/Dao.java", "package example; class Dao { int read(int x) { return x + 1; } }");
        var diffs = compare();
        assertThat(method(diffs, "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(method(diffs, "Caller", "independent").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
        assertThat(method(diffs, "Dao", "read").callerMethods()).contains("example.Caller#target(int)");
        assertThat(method(diffs, "Other", "read").callerMethods()).doesNotContain("example.Caller#target(int)");
    }

    @Test void dtoAndBuildChangesMarkDependentServicesWithoutBodyEdits() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { Input target(Input input) { return input; } int independent() { return 8; } }");
        both("src/main/java/example/Input.java", "package example; record Input(String name) {}");
        candidate("src/main/java/example/Input.java", "package example; record Input(String name, int age) {}");
        var dtoDiff = compare();
        assertThat(method(dtoDiff, "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(method(dtoDiff, "Caller", "independent").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
        candidate("pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        assertThat(method(compare(), "Caller", "independent").diffType()).isEqualTo(MethodDiffType.MODIFIED);
    }

    @Test void overloadChangeAffectsOnlyResolvedOverloadCaller() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { private Dao dao; int target(int x) { return dao.read(x); } }");
        both("src/main/java/example/Dao.java", "package example; class Dao { int read(int x) { return x; } int read(String x) { return 1; } }");
        candidate("src/main/java/example/Dao.java", "package example; class Dao { int read(int x) { return x; } int read(String x) { return 2; } }");
        var diffs = compare();
        assertThat(method(diffs, "Caller", "target").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
        assertThat(diffs.stream().filter(item -> item.signature().equals("read(String)")).findFirst().orElseThrow().callerMethods()).isEmpty();
    }

    @Test void sameQualifiedClassAcrossModulesRemainsDistinct() throws Exception {
        both("a/src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { int target(int x) { return x; } }");
        both("b/src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { int target(int x) { return x; } }");
        candidate("a/src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { int target(int x) { return x + 1; } }");
        var diffs = compare();
        assertThat(diffs).hasSize(2);
        assertThat(diffs.stream().filter(item -> item.servicePath().equals("a")).findFirst().orElseThrow().diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(diffs.stream().filter(item -> item.servicePath().equals("b")).findFirst().orElseThrow().diffType()).isEqualTo(MethodDiffType.UNCHANGED);
    }

    @Test void changedLiteralWhitespaceIsDetectedAsBusinessLogic() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { String target() { return \"a b\"; } }");
        candidate("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { String target() { return \"a  b\"; } }");
        assertThat(method(compare(), "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
    }

    @Test void profileConfigurationChangeOnlyAffectsReferencedProperty() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { @org.springframework.beans.factory.annotation.Value(\"${limits.maximum}\") int maximum; int target(int input) { return input > maximum ? maximum : input; } }");
        both("src/main/java/example/Independent.java", "package example; @org.springframework.stereotype.Service class Independent { int target() { return 9; } }");
        both("src/main/resources/application-prod.properties", "limits.maximum=5\nunrelated.label=one\n");
        candidate("src/main/resources/application-prod.properties", "limits.maximum=5\nunrelated.label=two\n");
        assertThat(method(compare(), "Caller", "target").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
        candidate("src/main/resources/application-prod.properties", "limits.maximum=10\nunrelated.label=two\n");
        var diffs = compare();
        assertThat(method(diffs, "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(method(diffs, "Independent", "target").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
    }

    @Test void mapperXmlChangeOnlyAffectsMethodsUsingItsNamespace() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { private Dao dao; int target(int x) { return dao.read(x); } int independent() { return 9; } }");
        both("src/main/java/example/Dao.java", "package example; interface Dao { int read(int value); }");
        both("src/main/resources/mapper.xml", "<mapper namespace=\"example.Dao\"><select id=\"read\" resultType=\"int\">SELECT 1</select></mapper>");
        candidate("src/main/resources/mapper.xml", "<mapper namespace=\"example.Dao\"><select id=\"read\" resultType=\"int\">SELECT 2</select></mapper>");
        var diffs = compare();
        assertThat(method(diffs, "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(method(diffs, "Caller", "independent").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
    }

    @Test void lombokConfigurationChangeAffectsOnlyLombokContractUsers() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { Input target(Input value) { return value; } }");
        both("src/main/java/example/Input.java", "package example; @lombok.Data class Input { private String name; }");
        both("src/main/java/example/Independent.java", "package example; @org.springframework.stereotype.Service class Independent { int target() { return 9; } }");
        both("lombok.config", "lombok.accessors.fluent = false\n");
        candidate("lombok.config", "lombok.accessors.fluent = true\n");
        var diffs = compare();
        assertThat(method(diffs, "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(method(diffs, "Independent", "target").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
    }

    @Test void unchangedProfileResourceSurvivesZipWrapperAlias() throws Exception {
        String source = "package example; @org.springframework.stereotype.Service class Caller { @org.springframework.beans.factory.annotation.Value(\"${limits.maximum}\") int maximum; int target() { return maximum; } }";
        write("base", "outer/module/src/main/java/example/Caller.java", source);
        candidate("module/src/main/java/example/Caller.java", source);
        write("base", "outer/module/src/main/resources/application-prod.properties", "limits.maximum=5\n");
        candidate("module/src/main/resources/application-prod.properties", "limits.maximum=5\n");
        assertThat(compare()).singleElement().satisfies(item -> assertThat(item.diffType()).isEqualTo(MethodDiffType.UNCHANGED));
    }

    @Test void everyReferencedConfigurationDocumentContributesToFingerprint() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { @org.springframework.beans.factory.annotation.Value(\"${limits.maximum}\") int maximum; int target() { return maximum; } }");
        both("src/main/resources/application.yml", "limits:\n  maximum: 5\n---\nlimits:\n  maximum: 9\n");
        candidate("src/main/resources/application.yml", "limits:\n  maximum: 6\n---\nlimits:\n  maximum: 9\n");
        assertThat(method(compare(), "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
    }

    @Test void uniqueInterfaceImplementationBodyChangeReachesServiceCaller() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { private Repo repo; int target(int input) { return repo.save(input); } int independent() { return 4; } }");
        both("src/main/java/example/Repo.java", "package example; interface Repo { int save(int input); }");
        both("src/main/java/example/RepoImpl.java", "package example; class RepoImpl implements Repo { public int save(int input) { return input; } }");
        candidate("src/main/java/example/RepoImpl.java", "package example; class RepoImpl implements Repo { public int save(int input) { return input + 1; } }");
        var diffs = compare();
        assertThat(method(diffs, "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
        assertThat(method(diffs, "Caller", "independent").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
    }

    @Test void inheritedUniqueImplementationBodyChangeReachesServiceCaller() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { private Repo repo; int target(int input) { return repo.save(input); } }");
        both("src/main/java/example/Repo.java", "package example; interface Repo { int save(int input); }");
        both("src/main/java/example/Base.java", "package example; abstract class Base implements Repo { public int save(int input) { return input; } }");
        both("src/main/java/example/Child.java", "package example; class Child extends Base {} ");
        candidate("src/main/java/example/Base.java", "package example; abstract class Base implements Repo { public int save(int input) { return input + 1; } }");
        assertThat(method(compare(), "Caller", "target").diffType()).isEqualTo(MethodDiffType.MODIFIED);
    }

    @Test void changedAmbiguousImplementorBlocksOnlyItsUnresolvedDependencyScope() throws Exception {
        both("src/main/java/example/Caller.java", "package example; @org.springframework.stereotype.Service class Caller { private Repo repo; int target(int input) { return repo.save(input); } }");
        both("src/main/java/example/Repo.java", "package example; interface Repo { int save(int input); }");
        both("src/main/java/example/First.java", "package example; class First implements Repo { public int save(int input) { return input; } }");
        both("src/main/java/example/Second.java", "package example; class Second implements Repo { public int save(int input) { return input; } }");
        both("src/main/java/example/Unrelated.java", "package example; class Unrelated { int value() { return 1; } }");
        candidate("src/main/java/example/Unrelated.java", "package example; class Unrelated { int value() { return 2; } }");
        assertThat(method(compare(), "Caller", "target").diffType()).isEqualTo(MethodDiffType.UNCHANGED);
        candidate("src/main/java/example/First.java", "package example; class First implements Repo { public int save(int input) { return input + 1; } }");
        org.assertj.core.api.Assertions.assertThatThrownBy(this::compare)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dynamic dispatch");
    }

    @Test void concreteBaseOverrideChangeCannotBecomeAnEmptySuccessfulImpact() throws Exception {
        both("src/main/java/example/Caller.java","package example; @org.springframework.stereotype.Service class Caller { private Base base; int target(){ return base.value(); } }");
        both("src/main/java/example/Base.java","package example; class Base { public int value(){ return 1; } }");
        both("src/main/java/example/Child.java","package example; @org.springframework.stereotype.Component class Child extends Base { public int value(){ return 2; } }");
        candidate("src/main/java/example/Child.java","package example; @org.springframework.stereotype.Component class Child extends Base { public int value(){ return 3; } }");
        org.assertj.core.api.Assertions.assertThatThrownBy(this::compare).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dynamic dispatch");
    }

    private List<MethodDiffItem> compare() { return service.compareSnapshots(temp.resolve("base"), temp.resolve("candidate")); }
    private MethodDiffItem method(List<MethodDiffItem> items, String owner, String method) { return items.stream().filter(item -> item.className().equals(owner) && item.methodName().equals(method)).findFirst().orElseThrow(); }
    private void both(String file, String source) throws Exception { write("base", file, source); candidate(file, source); }
    private void candidate(String file, String source) throws Exception { write("candidate", file, source); }
    private void write(String tree, String relative, String source) throws Exception { Path file = temp.resolve(tree).resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, source); }
}
