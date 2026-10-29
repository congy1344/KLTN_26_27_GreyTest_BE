package com.greytest.service.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.MethodParam;
import com.greytest.repository.*;

class DependencyImpactAnalysisTest {
    @Test void changedNonServiceDependencyReachesCallerRulesWithinCorrectModule() {
        var methods = mock(JavaMethodRepository.class);
        var classes = mock(JavaClassRepository.class);
        var rules = mock(BusinessRuleRepository.class);
        var service = new ImpactAnalysisService(methods, classes, rules, mock(TestPlanRepository.class), mock(TestPlanCoveredRuleRepository.class), mock(TestCaseRepository.class), mock(UnitTestRepository.class));
        var dao = new MethodDiffItem("Dao", "example.Dao", "read", "read(int)", "example.Dao#read(int)", MethodDiffType.MODIFIED, "changed", "before", "after", List.of("example.Caller#target(int)"), false, "a");
        var caller = new MethodDiffItem("Caller", "example.Caller", "target", "target(int)", "example.Caller#target(int)", MethodDiffType.UNCHANGED, "unchanged", "same", "same", List.of(), true, "a");
        var otherModule = new MethodDiffItem("Caller", "example.Caller", "target", "target(int)", "example.Caller#target(int)", MethodDiffType.UNCHANGED, "unchanged", "same", "same", List.of(), true, "b");
        JavaClass local = javaClass(10L, "a");
        JavaClass remote = javaClass(20L, "b");
        when(classes.findById(10L)).thenReturn(Optional.of(local));
        when(classes.findById(20L)).thenReturn(Optional.of(remote));
        when(methods.findById(100L)).thenReturn(Optional.of(javaMethod(100L, 10L)));
        when(methods.findById(200L)).thenReturn(Optional.of(javaMethod(200L, 20L)));
        when(rules.findByProjectId(1L)).thenReturn(List.of(rule(1000L, 100L), rule(2000L, 200L)));
        var impact = service.analyzeImpact(1L, List.of(dao, caller, otherModule));
        assertThat(impact.affectedBusinessRuleIds()).containsExactly(1000L);
        assertThat(impact.changedMethods()).hasSize(1);
        assertThat(impact.changedMethods().get(0).isServiceMethod()).isTrue();
        assertThat(impact.changedMethods().get(0).servicePath()).isEqualTo("a");
    }

    private JavaClass javaClass(Long id, String module) { var value = new JavaClass(); value.setId(id); value.setQualifiedName("example.Caller"); value.setFilePath(module + "/src/main/java/example/Caller.java"); return value; }
    private JavaMethod javaMethod(Long id, Long owner) { var value = new JavaMethod(); value.setId(id); value.setClassId(owner); value.setMethodName("target"); value.setParameters(List.of(new MethodParam("x", "int"))); return value; }
    private BusinessRule rule(Long id, Long method) { var value = new BusinessRule(); value.setId(id); value.setMethodId(method); return value; }
}
