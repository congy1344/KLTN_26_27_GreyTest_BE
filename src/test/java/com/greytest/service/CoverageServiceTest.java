package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
import com.greytest.service.coverage.JacocoXmlParser.ParsedClass;
import com.greytest.service.coverage.JacocoXmlParser.ParsedReport;
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
    void discardXoaReportConTonTaiVaBoQuaReportDaBiXoa() {
        when(reports.existsById(41L)).thenReturn(true);

        service.discard(41L);
        service.discard(42L);

        org.mockito.Mockito.verify(reports).deleteById(41L);
        org.mockito.Mockito.verify(reports, org.mockito.Mockito.never()).deleteById(42L);
    }

    private static final java.time.LocalDateTime T0 = java.time.LocalDateTime.of(2026, 11, 24, 10, 0);

    @Test
    void latestKhongRefineLaiGapKhongDoiSauLuotBoSung() {
        var gap = unchangedGap(List.of(T0, T0.plusMinutes(10)), T0.plusMinutes(5));

        assertThat(gap.refinable()).isFalse();
        assertThat(gap.suggestion()).contains("Không thể cải thiện tự động");
    }

    @Test
    void latestVanChoBoSungKhiChuaTungBoSungDuCoNhieuBaoCaoGiongNhau() {
        // Sandbox nạp coverage sau mỗi lần chạy (AI sửa test, tự "Tiếp tục sinh") nên báo cáo trước chưa chắc là một vòng bổ sung.
        assertThat(unchangedGap(List.of(T0, T0.plusMinutes(10)), null).refinable()).isTrue();
    }

    @Test
    void latestSoVoiBaoCaoTruocLuotBoSungDuLuotXacMinhNapNhieuBaoCao() {
        var gap = unchangedGap(List.of(T0, T0.plusMinutes(10), T0.plusMinutes(12)), T0.plusMinutes(5));

        assertThat(gap.refinable()).isFalse();
    }

    @Test
    void latestVanChoBoSungKhiLuotBoSungChuaDuocDoLai() {
        assertThat(unchangedGap(List.of(T0, T0.plusMinutes(10)), T0.plusMinutes(15)).refinable()).isTrue();
    }

    /** Gap giống hệt nhau ở mọi báo cáo; supplementedAt là lúc tạo Test Case bổ sung cho method, null nếu chưa có. */
    private com.greytest.dto.CoverageGapDto unchangedGap(
            List<java.time.LocalDateTime> uploadedAt, java.time.LocalDateTime supplementedAt) {
        Project project = new Project();
        project.setId(5L);
        when(projects.findById(5L)).thenReturn(Optional.of(project));

        JavaClass serviceClass = javaClass(1L, "RecipientServiceImpl", ClassType.SERVICE);
        JavaMethod method = javaMethod(11L, 1L, "findReadyToNotify");
        BusinessRule rule = rule(20L);
        rule.setMethodId(11L);
        TestPlan plan = new TestPlan();
        plan.setId(30L);
        plan.setBusinessRuleId(20L);
        plan.setStatus(ReviewStatus.APPROVED);
        var history = new java.util.ArrayList<com.greytest.entity.CoverageReport>();
        for (int i = 0; i < uploadedAt.size(); i++) {
            var stored = report(i + 1L, "79.00");
            stored.setUploadedAt(uploadedAt.get(i));
            history.add(stored);
            org.mockito.Mockito.lenient().when(details.findByReportId(i + 1L)).thenReturn(List.of(gap(11L)));
        }
        var original = testCase(30L, "BR-001 -> TP-001", T0.minusHours(1));
        when(cases.findByProjectId(5L)).thenReturn(supplementedAt == null ? List.of(original)
                : List.of(original, testCase(30L, "BR-001 -> TP-001 -> JaCoCo round 2", supplementedAt)));

        when(reports.findTopByProjectIdOrderByIdDesc(5L)).thenReturn(Optional.of(history.get(history.size() - 1)));
        when(reports.findByProjectId(5L)).thenReturn(history);
        when(classes.findByProjectId(5L)).thenReturn(List.of(serviceClass));
        when(methods.findAllById(List.of(11L))).thenReturn(List.of(method));
        when(plans.findByProjectId(5L)).thenReturn(List.of(plan));
        when(rules.findById(20L)).thenReturn(Optional.of(rule));

        return service.latest(5L).orElseThrow().gaps().get(0);
    }

    private static com.greytest.entity.TestCase testCase(Long planId, String traceSource, java.time.LocalDateTime createdAt) {
        var testCase = new com.greytest.entity.TestCase();
        testCase.setTestPlanId(planId);
        testCase.setTraceSource(traceSource);
        testCase.setCreatedAt(createdAt);
        return testCase;
    }

    @Test
    void latestTinhCoverageRiengChoClassServiceTuXmlDaLuu(@TempDir Path dir) throws Exception {
        stubServiceCoverage(dir, List.of(
                javaClass(1L, "UserServiceImpl", ClassType.SERVICE, "com.acme.UserServiceImpl"),
                javaClass(2L, "UserController", ClassType.CONTROLLER, "com.acme.UserController")),
                List.of(
                        new ParsedClass("com.acme.UserServiceImpl", 10, 9, 4, 3),
                        // class lồng/ẩn danh của Service phải tính vào Service
                        new ParsedClass("com.acme.UserServiceImpl$Helper", 3, 3, 0, 0),
                        new ParsedClass("com.acme.UserController", 4, 0, 2, 0),
                        new ParsedClass("com.acme.config.WebConfig", 33, 0, 0, 0)));

        var coverage = service.latest(5L).orElseThrow().serviceCoverage();

        assertThat(coverage.totalLines()).isEqualTo(13);
        assertThat(coverage.coveredLines()).isEqualTo(12);
        assertThat(coverage.lineCoverage()).isEqualTo(new BigDecimal("92.31"));
        assertThat(coverage.totalBranches()).isEqualTo(4);
        assertThat(coverage.coveredBranches()).isEqualTo(3);
        assertThat(coverage.branchCoverage()).isEqualTo(new BigDecimal("75.00"));
    }

    @Test
    void latestTraBranchNullKhiServiceKhongCoNhanh(@TempDir Path dir) throws Exception {
        stubServiceCoverage(dir,
                List.of(javaClass(1L, "UserServiceImpl", ClassType.SERVICE, "com.acme.UserServiceImpl")),
                List.of(new ParsedClass("com.acme.UserServiceImpl", 10, 10, 0, 0)));

        var coverage = service.latest(5L).orElseThrow().serviceCoverage();

        assertThat(coverage.lineCoverage()).isEqualTo(new BigDecimal("100.00"));
        assertThat(coverage.branchCoverage()).isNull();
    }

    @Test
    void latestTraServiceCoverageNullKhiXmlKhongCoDongCodeCuaService(@TempDir Path dir) throws Exception {
        stubServiceCoverage(dir,
                List.of(javaClass(1L, "UserServiceImpl", ClassType.SERVICE, "com.acme.UserServiceImpl")),
                List.of(new ParsedClass("com.acme.UserController", 4, 0, 0, 0)));

        assertThat(service.latest(5L).orElseThrow().serviceCoverage()).isNull();
    }

    @Test
    void latestTraServiceCoverageNullKhiFileXmlKhongConTonTai(@TempDir Path dir) {
        Project project = new Project();
        project.setId(5L);
        when(projects.findById(5L)).thenReturn(Optional.of(project));
        var stored = report(1L, "25.68");
        stored.setXmlFilePath(dir.resolve("da-xoa.xml").toString());
        when(reports.findTopByProjectIdOrderByIdDesc(5L)).thenReturn(Optional.of(stored));
        when(reports.findByProjectId(5L)).thenReturn(List.of(stored));
        when(details.findByReportId(1L)).thenReturn(List.of());
        when(classes.findByProjectId(5L)).thenReturn(List.of());
        when(plans.findByProjectId(5L)).thenReturn(List.of());

        var dto = service.latest(5L).orElseThrow();

        assertThat(dto.lineCoverage()).isEqualTo(new BigDecimal("25.68"));
        assertThat(dto.serviceCoverage()).isNull();
    }

    @Test
    void latestVanTraSoToanModuleKhiFileXmlDaLuuBiHong(@TempDir Path dir) throws Exception {
        stubServiceCoverage(dir,
                List.of(javaClass(1L, "UserServiceImpl", ClassType.SERVICE, "com.acme.UserServiceImpl")),
                List.of());
        when(parser.parse(any())).thenThrow(new IllegalArgumentException("File không phải JaCoCo XML hợp lệ"));

        var dto = service.latest(5L).orElseThrow();

        assertThat(dto.lineCoverage()).isEqualTo(new BigDecimal("25.68"));
        assertThat(dto.serviceCoverage()).isNull();
    }

    @Test
    void latestTheoModuleChiTinhServiceThuocModuleDo(@TempDir Path dir) throws Exception {
        var scope = new ServiceScopeResolver.ServiceScope("auth", java.util.Set.of(1L), java.util.Set.of());
        Path xml = Files.createFile(dir.resolve("jacoco.xml"));
        var stored = report(1L, "50.00");
        stored.setXmlFilePath(xml.toString());
        when(scopeResolver.resolve(5L, "auth")).thenReturn(scope);
        when(scopeResolver.listScopes(5L)).thenReturn(List.of(scope));
        when(reports.findByProjectIdAndServicePath(5L, "auth")).thenReturn(List.of(stored));
        when(reports.findByProjectIdAndServicePath(5L, ".")).thenReturn(List.of());
        when(details.findByReportId(1L)).thenReturn(List.of());
        when(classes.findByProjectId(5L)).thenReturn(List.of(
                javaClass(1L, "AuthService", ClassType.SERVICE, "com.acme.AuthService"),
                javaClass(2L, "OrderService", ClassType.SERVICE, "com.acme.OrderService")));
        when(plans.findByProjectId(5L)).thenReturn(List.of());
        when(parser.parse(any())).thenReturn(parsed(List.of(
                new ParsedClass("com.acme.AuthService", 4, 4, 0, 0),
                new ParsedClass("com.acme.OrderService", 6, 0, 0, 0))));

        var coverage = service.latest(5L, "auth").orElseThrow().serviceCoverage();

        assertThat(coverage.totalLines()).isEqualTo(4);
        assertThat(coverage.coveredLines()).isEqualTo(4);
    }

    private void stubServiceCoverage(Path dir, List<JavaClass> javaClasses, List<ParsedClass> parsedClasses)
            throws Exception {
        Project project = new Project();
        project.setId(5L);
        when(projects.findById(5L)).thenReturn(Optional.of(project));
        var stored = report(1L, "25.68");
        stored.setXmlFilePath(Files.createFile(dir.resolve("jacoco.xml")).toString());
        when(reports.findTopByProjectIdOrderByIdDesc(5L)).thenReturn(Optional.of(stored));
        when(reports.findByProjectId(5L)).thenReturn(List.of(stored));
        when(details.findByReportId(1L)).thenReturn(List.of());
        when(classes.findByProjectId(5L)).thenReturn(javaClasses);
        when(plans.findByProjectId(5L)).thenReturn(List.of());
        when(parser.parse(any())).thenReturn(parsed(parsedClasses));
    }

    private ParsedReport parsed(List<ParsedClass> parsedClasses) {
        return new ParsedReport(0, 0, 0, 0, List.of(), parsedClasses);
    }

    private JavaClass javaClass(Long id, String name, ClassType type, String qualifiedName) {
        JavaClass javaClass = javaClass(id, name, type);
        javaClass.setQualifiedName(qualifiedName);
        return javaClass;
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
