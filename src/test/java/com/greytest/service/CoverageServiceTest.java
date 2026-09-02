package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.CoverageDetail;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageDetailRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.coverage.JacocoXmlParser;
import com.greytest.service.storage.FileStorageService;

@ExtendWith(MockitoExtension.class)
class CoverageServiceTest {

    @Mock private JacocoXmlParser parser;
    @Mock private CoverageReportRepository reports;
    @Mock private CoverageDetailRepository details;
    @Mock private JavaClassRepository classes;
    @Mock private JavaMethodRepository methods;
    @Mock private BusinessRuleRepository rules;
    @Mock private TestPlanRepository plans;
    @Mock private TestPlanCoveredRuleRepository coveredRules;
    @Mock private TestCaseRepository cases;
    @Mock private ProjectRepository projects;
    @Mock private FileStorageService storage;
    @Mock private MultipartFile file;
    @Mock private ServiceScopeResolver scopeResolver;
    @Mock private ServicePipelineStatusService scopedStatuses;

    @InjectMocks private CoverageService service;

    @Test
    void requirementCoverageTinhTheoRuleDaCoTestCase() {
        // 2 rule approved, chỉ rule 1 được plan (có test case) cover → 50%
        BusinessRule rule1 = rule(1L);
        BusinessRule rule2 = rule(2L);
        TestPlan plan = new TestPlan();
        plan.setId(10L);
        TestPlanCoveredRule link = new TestPlanCoveredRule();
        link.setTestPlanId(10L);
        link.setBusinessRuleId(1L);
        when(rules.findByProjectIdAndStatus(5L, ReviewStatus.APPROVED)).thenReturn(List.of(rule1, rule2));
        when(plans.findByProjectId(5L)).thenReturn(List.of(plan));
        when(cases.findByTestPlanId(10L)).thenReturn(List.of(new TestCase()));
        when(coveredRules.findByTestPlanIdIn(List.of(10L))).thenReturn(List.of(link));

        assertThat(service.calculateRequirementCoverage(5L)).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    void requirementCoverageTra100KhiChuaCoRule() {
        when(rules.findByProjectIdAndStatus(5L, ReviewStatus.APPROVED)).thenReturn(List.of());

        assertThat(service.calculateRequirementCoverage(5L)).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void uploadTuChoiKhiChuaSinhUnitTest() {
        Project project = new Project();
        project.setId(5L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        when(projects.findById(5L)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.upload(5L, file))
                .isInstanceOf(InvalidProjectStatusException.class);
        verify(storage, never()).storeCoverageXml(any(), any());
    }

    @Test
    void nestedSingleModuleKeepsLegacyRootCoverageHistory() {
        var scope = new ServiceScopeResolver.ServiceScope(
                "orders", java.util.Set.of(1L), java.util.Set.of(11L));
        var legacy = report(1L, "70.00");
        when(scopeResolver.resolve(5L, "orders")).thenReturn(scope);
        when(scopeResolver.listScopes(5L)).thenReturn(List.of(scope));
        when(reports.findByProjectIdAndServicePath(5L, "orders")).thenReturn(List.of());
        when(reports.findByProjectIdAndServicePath(5L, ".")).thenReturn(List.of(legacy));
        when(details.findByReportId(1L)).thenReturn(List.of());
        when(classes.findByProjectId(5L)).thenReturn(List.of());
        when(plans.findByProjectId(5L)).thenReturn(List.of());

        var dto = service.latest(5L, "orders").orElseThrow();

        assertThat(dto.servicePath()).isEqualTo("orders");
        assertThat(dto.round()).isEqualTo(1);
        assertThat(dto.lineCoverage()).isEqualTo(new BigDecimal("70.00"));
    }
    @Test
    void latestTraVeSoVongVaSoLieuVongTruoc() {
        Project project = new Project();
        project.setId(5L);
        when(projects.findById(5L)).thenReturn(Optional.of(project));
        var round1 = report(1L, "70.00");
        var round2 = report(2L, "86.67");
        when(reports.findTopByProjectIdOrderByIdDesc(5L)).thenReturn(Optional.of(round2));
        when(details.findByReportId(2L)).thenReturn(List.of());
        when(classes.findByProjectId(5L)).thenReturn(List.of());
        when(reports.findByProjectId(5L)).thenReturn(List.of(round1, round2));

        var dto = service.latest(5L).orElseThrow();

        assertThat(dto.round()).isEqualTo(2);
        assertThat(dto.lineCoverage()).isEqualTo(new BigDecimal("86.67"));
        assertThat(dto.previousLineCoverage()).isEqualTo(new BigDecimal("70.00"));
    }

    @Test
    void latestOnlyMarksApprovedServiceGapsAsRefinable() {
        Project project = new Project();
        project.setId(5L);
        when(projects.findById(5L)).thenReturn(Optional.of(project));

        JavaClass serviceClass = javaClass(1L, "OrderService", ClassType.SERVICE);
        JavaClass controllerClass = javaClass(2L, "OrderController", ClassType.CONTROLLER);
        JavaMethod serviceMethod = javaMethod(11L, 1L, "createOrder");
        JavaMethod controllerMethod = javaMethod(12L, 2L, "createOrder");
        JavaMethod pendingRuleMethod = javaMethod(13L, 1L, "cancelOrder");
        CoverageDetail serviceGap = gap(11L);
        CoverageDetail controllerGap = gap(12L);
        CoverageDetail pendingRuleGap = gap(13L);

        BusinessRule rule = rule(20L);
        rule.setMethodId(11L);
        TestPlan plan = new TestPlan();
        plan.setBusinessRuleId(20L);
        plan.setStatus(ReviewStatus.APPROVED);
        BusinessRule pendingRule = rule(21L);
        pendingRule.setMethodId(13L);
        pendingRule.setStatus(ReviewStatus.PENDING_REVIEW);
        TestPlan pendingRulePlan = new TestPlan();
        pendingRulePlan.setBusinessRuleId(21L);
        pendingRulePlan.setStatus(ReviewStatus.APPROVED);

        var report = report(1L, "50.00");
        when(reports.findTopByProjectIdOrderByIdDesc(5L)).thenReturn(Optional.of(report));
        when(reports.findByProjectId(5L)).thenReturn(List.of(report));
        when(details.findByReportId(1L)).thenReturn(List.of(serviceGap, controllerGap, pendingRuleGap));
        when(classes.findByProjectId(5L)).thenReturn(List.of(serviceClass, controllerClass));
        when(methods.findAllById(List.of(11L, 12L, 13L)))
                .thenReturn(List.of(serviceMethod, controllerMethod, pendingRuleMethod));
        when(plans.findByProjectId(5L)).thenReturn(List.of(plan, pendingRulePlan));
        when(rules.findById(20L)).thenReturn(Optional.of(rule));
        when(rules.findById(21L)).thenReturn(Optional.of(pendingRule));

        var gaps = service.latest(5L).orElseThrow().gaps();

        assertThat(gaps).filteredOn(gap -> gap.methodName().equals("createOrder")
                && gap.className().equals("OrderService"))
                .allMatch(com.greytest.dto.CoverageGapDto::refinable);
        assertThat(gaps).filteredOn(gap -> gap.className().equals("OrderController"))
                .allMatch(gap -> !gap.refinable())
                .allMatch(gap -> gap.suggestion().contains("Ngoài phạm vi"));
        assertThat(gaps).filteredOn(gap -> gap.methodName().equals("cancelOrder"))
                .allMatch(gap -> !gap.refinable())
                .allMatch(gap -> gap.suggestion().contains("Chưa liên kết"));
    }

    @Test
    void latestKhongRefineLaiGapKhongDoiSauVongTruoc() {
        Project project = new Project();
        project.setId(5L);
        when(projects.findById(5L)).thenReturn(Optional.of(project));

        JavaClass serviceClass = javaClass(1L, "RecipientServiceImpl", ClassType.SERVICE);
        JavaMethod method = javaMethod(11L, 1L, "findReadyToNotify");
        CoverageDetail previousGap = gap(11L);
        CoverageDetail currentGap = gap(11L);
        currentGap.setLineCoverage(new BigDecimal("20.00"));
        BusinessRule rule = rule(20L);
        rule.setMethodId(11L);
        TestPlan plan = new TestPlan();
        plan.setBusinessRuleId(20L);
        plan.setStatus(ReviewStatus.APPROVED);
        var round1 = report(1L, "79.00");
        var round2 = report(2L, "79.00");

        when(reports.findTopByProjectIdOrderByIdDesc(5L)).thenReturn(Optional.of(round2));
        when(reports.findByProjectId(5L)).thenReturn(List.of(round1, round2));
        when(details.findByReportId(2L)).thenReturn(List.of(currentGap));
        when(details.findByReportId(1L)).thenReturn(List.of(previousGap));
        when(classes.findByProjectId(5L)).thenReturn(List.of(serviceClass));
        when(methods.findAllById(List.of(11L))).thenReturn(List.of(method));
        when(plans.findByProjectId(5L)).thenReturn(List.of(plan));
        when(rules.findById(20L)).thenReturn(Optional.of(rule));

        var gap = service.latest(5L).orElseThrow().gaps().get(0);

        assertThat(gap.refinable()).isFalse();
        assertThat(gap.suggestion()).contains("Không thể cải thiện tự động");
    }

    private com.greytest.entity.CoverageReport report(Long id, String lineCoverage) {
        var report = new com.greytest.entity.CoverageReport();
        report.setId(id);
        report.setProjectId(5L);
        report.setLineCoverage(new BigDecimal(lineCoverage));
        return report;
    }

    private BusinessRule rule(Long id) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setStatus(ReviewStatus.APPROVED);
        return rule;
    }

    private JavaClass javaClass(Long id, String name, ClassType type) {
        JavaClass javaClass = new JavaClass();
        javaClass.setId(id);
        javaClass.setClassName(name);
        javaClass.setClassType(type);
        return javaClass;
    }

    private JavaMethod javaMethod(Long id, Long classId, String name) {
        JavaMethod method = new JavaMethod();
        method.setId(id);
        method.setClassId(classId);
        method.setMethodName(name);
        return method;
    }

    private CoverageDetail gap(Long methodId) {
        CoverageDetail detail = new CoverageDetail();
        detail.setMethodId(methodId);
        detail.setLineCoverage(new BigDecimal("0.00"));
        detail.setBranchCoverage(new BigDecimal("100.00"));
        detail.setMissedLines(List.of(10));
        detail.setMissedBranches(List.of());
        detail.setHasGap(true);
        return detail;
    }
}
