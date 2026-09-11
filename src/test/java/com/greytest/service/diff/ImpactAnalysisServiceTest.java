package com.greytest.service.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.MethodParam;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

@ExtendWith(MockitoExtension.class)
class ImpactAnalysisServiceTest {

    @Mock
    private JavaMethodRepository javaMethodRepository;
    @Mock
    private JavaClassRepository javaClassRepository;
    @Mock
    private BusinessRuleRepository businessRuleRepository;
    @Mock
    private TestPlanRepository testPlanRepository;
    @Mock
    private TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private UnitTestRepository unitTestRepository;

    private ImpactAnalysisService impactService;

    @BeforeEach
    void setUp() {
        impactService = new ImpactAnalysisService(
                javaMethodRepository,
                javaClassRepository,
                businessRuleRepository,
                testPlanRepository,
                testPlanCoveredRuleRepository,
                testCaseRepository,
                unitTestRepository
        );
    }

    @Test
    void tracesImpactFromModifiedMethodToBR_TP_TC_UT() {
        Long projectId = 10L;

        MethodDiffItem modifiedMethod = new MethodDiffItem(
                "OrderService",
                "com.example.OrderService",
                "calculateDiscount",
                "calculateDiscount(int)",
                "com.example.OrderService#calculateDiscount(int)",
                MethodDiffType.MODIFIED,
                "Logic changed",
                "code1",
                "code2",
                List.of("com.example.OrderController#checkout(OrderDto)"),
                true
        );

        JavaMethod jm = new JavaMethod();
        jm.setId(101L);
        jm.setClassId(601L);
        jm.setMethodName("calculateDiscount");
        jm.setParameters(List.of(new MethodParam("amount", "int")));

        JavaClass jc = new JavaClass();
        jc.setId(601L);
        jc.setQualifiedName("com.example.OrderService");

        BusinessRule br = new BusinessRule();
        br.setId(201L);
        br.setProjectId(projectId);
        br.setMethodId(101L);

        when(businessRuleRepository.findByProjectId(projectId)).thenReturn(List.of(br));
        when(javaMethodRepository.findById(101L)).thenReturn(Optional.of(jm));
        when(javaClassRepository.findById(601L)).thenReturn(Optional.of(jc));

        TestPlan tp = new TestPlan();
        tp.setId(301L);
        tp.setBusinessRuleId(201L);
        when(testPlanRepository.findByBusinessRuleId(201L)).thenReturn(List.of(tp));
        when(testPlanCoveredRuleRepository.findByBusinessRuleId(201L)).thenReturn(List.of());

        TestCase tc = new TestCase();
        tc.setId(401L);
        tc.setTestPlanId(301L);
        when(testCaseRepository.findByTestPlanId(301L)).thenReturn(List.of(tc));

        UnitTest ut = new UnitTest();
        ut.setId(501L);
        ut.setTestCaseId(401L);
        when(unitTestRepository.findByTestCaseId(401L)).thenReturn(ut);

        ImpactSummaryDto summary = impactService.analyzeImpact(projectId, List.of(modifiedMethod));

        assertThat(summary.totalChangedMethods()).isEqualTo(1);
        assertThat(summary.modifiedMethodsCount()).isEqualTo(1);
        assertThat(summary.affectedServiceMethods()).contains("com.example.OrderService#calculateDiscount(int)");
        assertThat(summary.affectedBusinessRuleIds()).containsExactly(201L);
        assertThat(summary.affectedTestPlanIds()).containsExactly(301L);
        assertThat(summary.affectedTestCaseIds()).containsExactly(401L);
        assertThat(summary.affectedUnitTestIds()).containsExactly(501L);
    }

    @Test
    void doesNotTraceRuleFromAnotherClassWithSameMethodName() {
        MethodDiffItem changedMethod = new MethodDiffItem(
                "OrderService", "com.example.OrderService", "calculateDiscount", "calculateDiscount(int)",
                "com.example.OrderService#calculateDiscount(int)", MethodDiffType.MODIFIED, "Logic changed",
                "before", "after", List.of(), true);

        JavaMethod methodInOtherClass = new JavaMethod();
        methodInOtherClass.setId(701L);
        methodInOtherClass.setClassId(702L);
        methodInOtherClass.setMethodName("calculateDiscount");
        JavaClass otherClass = new JavaClass();
        otherClass.setId(702L);
        otherClass.setQualifiedName("com.example.PricingService");

        BusinessRule unrelatedRule = new BusinessRule();
        unrelatedRule.setId(801L);
        unrelatedRule.setProjectId(10L);
        unrelatedRule.setMethodId(701L);

        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of(unrelatedRule));
        when(javaMethodRepository.findById(701L)).thenReturn(Optional.of(methodInOtherClass));
        when(javaClassRepository.findById(702L)).thenReturn(Optional.of(otherClass));

        ImpactSummaryDto summary = impactService.analyzeImpact(10L, List.of(changedMethod));

        assertThat(summary.affectedBusinessRuleIds()).isEmpty();
        assertThat(summary.affectedTestPlanIds()).isEmpty();
        assertThat(summary.affectedTestCaseIds()).isEmpty();
        assertThat(summary.affectedUnitTestIds()).isEmpty();
    }
}
