package com.greytest.service.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.JavaClass;
import com.greytest.repository.*;
import com.greytest.service.analysis.JavaParserHelper;

class SourceUpdateScopeRegressionTest {
    private static final String SELECTED = "hospital-management-demo-main/backend/patient-service";
    private final JavaClassRepository classes = mock(JavaClassRepository.class);
    private final ImpactAnalysisService impact = new ImpactAnalysisService(mock(JavaMethodRepository.class), classes,
            mock(BusinessRuleRepository.class), mock(TestPlanRepository.class), mock(TestPlanCoveredRuleRepository.class),
            mock(TestCaseRepository.class), mock(UnitTestRepository.class));

    @Test
    void changedZipWrapperDoesNotHideAddedMethodsFromTheSelectedService(@TempDir Path root) throws Exception {
        Path baseline = root.resolve("baseline");
        Path candidate = root.resolve("candidate");
        String before = "package demo; @org.springframework.stereotype.Service class PatientService { public void existing() {} }";
        String after = before.replace("public void existing() {}", "public void existing() {} "
                + "public long countActivePatients() { return repository.countActivePatients(); } "
                + "public java.util.List<String> getPatientsByBloodGroup(String bloodGroup) { return repository.findActiveByBloodGroup(bloodGroup); }");
        write(baseline, SELECTED, "PatientService", before);
        write(candidate, "backend/patient-service", "PatientService", after);
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", SELECTED)));

        var diff = new SourceDiffService(new JavaParserHelper()).compareSnapshots(baseline, candidate);
        var summary = impact.analyzeImpact(811L, diff, SELECTED);

        assertThat(summary.totalChangedMethods()).isEqualTo(2);
        assertThat(summary.addedMethodsCount()).isEqualTo(2);
        assertThat(summary.changedMethods()).extracting(MethodDiffItem::methodName)
                .containsExactlyInAnyOrder("countActivePatients", "getPatientsByBloodGroup");
        assertThat(summary.changedMethods()).allSatisfy(item -> assertThat(item.servicePath()).isEqualTo(SELECTED));
    }

    @Test
    void newClassUsesTheModuleAliasEstablishedByAnUnchangedClass() {
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", SELECTED)));
        var unchanged = item("demo.PatientService", "backend/patient-service", MethodDiffType.UNCHANGED);
        var added = item("demo.NewService", "backend/patient-service", MethodDiffType.ADDED);

        var summary = impact.analyzeImpact(811L, List.of(unchanged, added), SELECTED);

        assertThat(summary.addedMethodsCount()).isEqualTo(1);
        assertThat(summary.changedMethods()).singleElement().satisfies(value -> {
            assertThat(value.qualifiedClassName()).isEqualTo("demo.NewService");
            assertThat(value.servicePath()).isEqualTo(SELECTED);
        });
    }

    @Test
    void ambiguousModuleAliasDoesNotIncludeAnotherService() {
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", SELECTED),
                owner("other.PatientService", "another-project/backend/patient-service")));
        var first = item("demo.PatientService", "backend/patient-service", MethodDiffType.UNCHANGED);
        var second = item("other.PatientService", "backend/patient-service", MethodDiffType.MODIFIED);

        assertThat(impact.analyzeImpact(811L, List.of(first, second), SELECTED).changedMethods()).isEmpty();
    }

    @Test
    void removedWrapperCanExposeTheRootModule() {
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", "project-main")));

        var summary = impact.analyzeImpact(811L,
                List.of(item("demo.PatientService", ".", MethodDiffType.MODIFIED)), "project-main");

        assertThat(summary.changedMethods()).singleElement()
                .satisfies(value -> assertThat(value.servicePath()).isEqualTo("project-main"));
    }

    @Test
    void addedWrapperCanEncloseTheRootModule() {
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", ".")));

        var summary = impact.analyzeImpact(811L,
                List.of(item("demo.PatientService", "project-main", MethodDiffType.MODIFIED)), ".");

        assertThat(summary.changedMethods()).singleElement()
                .satisfies(value -> assertThat(value.servicePath()).isEqualTo("."));
    }

    @Test
    void rootModuleDoesNotMergeClassesFromDifferentBaselineModules() {
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", SELECTED),
                owner("demo.DoctorService", "backend/doctor-service")));
        var patient = item("demo.PatientService", ".", MethodDiffType.MODIFIED);
        var doctor = item("demo.DoctorService", ".", MethodDiffType.UNCHANGED);

        assertThat(impact.analyzeImpact(811L, List.of(patient, doctor), SELECTED).changedMethods()).isEmpty();
    }

    @Test
    void classMovedToAnotherModuleDoesNotBecomeAZipWrapperAlias() {
        when(classes.findByProjectId(811L)).thenReturn(List.of(owner("demo.PatientService", SELECTED)));
        var moved = item("demo.PatientService", "backend/doctor-service", MethodDiffType.MODIFIED);

        assertThat(impact.analyzeImpact(811L, List.of(moved), SELECTED).changedMethods()).isEmpty();
    }

    private JavaClass owner(String name, String module) {
        var result = new JavaClass();
        result.setQualifiedName(name);
        result.setFilePath(module + "/src/main/java/" + name.replace('.', '/') + ".java");
        return result;
    }

    private MethodDiffItem item(String owner, String module, MethodDiffType type) {
        return new MethodDiffItem(owner.substring(owner.lastIndexOf('.') + 1), owner, "run", "run()", owner + "#run()",
                type, "Source changed", "before", "after", List.of(), true, module);
    }

    private void write(Path root, String module, String name, String code) throws Exception {
        Path file = root.resolve(module + "/src/main/java/demo/" + name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
    }
}
