package com.greytest.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.CoverageReportDto;
import com.greytest.entity.CoverageDetail;
import com.greytest.entity.CoverageReport;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.StorageException;
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
import com.greytest.service.coverage.JacocoXmlParser.ParsedMethod;
import com.greytest.service.coverage.JacocoXmlParser.ParsedReport;
import com.greytest.service.storage.FileStorageService;

/**
 * Xử lý coverage: parse jacoco.xml user upload, lưu report + detail,
 * tính requirement coverage và phát hiện coverage gap.
 */
@Service
public class CoverageService {

    // Ngưỡng gate hiển thị ở frontend: line >= 80%, branch >= 70%
    private static final BigDecimal LINE_THRESHOLD = BigDecimal.valueOf(80);
    private static final BigDecimal BRANCH_THRESHOLD = BigDecimal.valueOf(70);
    private static final BigDecimal HIGH_RISK_THRESHOLD = BigDecimal.valueOf(60);

    private final JacocoXmlParser parser;
    private final CoverageReportRepository reports;
    private final CoverageDetailRepository details;
    private final JavaClassRepository classes;
    private final JavaMethodRepository methods;
    private final BusinessRuleRepository rules;
    private final TestPlanRepository plans;
    private final TestPlanCoveredRuleRepository coveredRules;
    private final TestCaseRepository cases;
    private final ProjectRepository projects;
    private final FileStorageService storage;

    public CoverageService(JacocoXmlParser parser, CoverageReportRepository reports,
            CoverageDetailRepository details, JavaClassRepository classes, JavaMethodRepository methods,
            BusinessRuleRepository rules, TestPlanRepository plans, TestPlanCoveredRuleRepository coveredRules,
            TestCaseRepository cases, ProjectRepository projects, FileStorageService storage) {
        this.parser = parser;
        this.reports = reports;
        this.details = details;
        this.classes = classes;
        this.methods = methods;
        this.rules = rules;
        this.plans = plans;
        this.coveredRules = coveredRules;
        this.cases = cases;
        this.projects = projects;
        this.storage = storage;
    }

    @Transactional
    public CoverageReportDto upload(Long projectId, MultipartFile file) {
        Project project = ensure(projectId);
        if (project.getStatus() != ProjectStatus.TEST_GENERATED
                && project.getStatus() != ProjectStatus.COVERAGE_ANALYZED
                && project.getStatus() != ProjectStatus.COMPLETED) {
            throw new InvalidProjectStatusException("Chỉ upload coverage sau khi đã sinh Unit Test.");
        }
        Path xmlPath = storage.storeCoverageXml(projectId, file);
        ParsedReport parsed = parseStoredXml(xmlPath);

        CoverageReport report = new CoverageReport();
        report.setProjectId(projectId);
        report.setTotalLines(parsed.totalLines());
        report.setCoveredLines(parsed.coveredLines());
        report.setTotalBranches(parsed.totalBranches());
        report.setCoveredBranches(parsed.coveredBranches());
        report.setLineCoverage(percent(parsed.coveredLines(), parsed.totalLines()));
        report.setBranchCoverage(percent(parsed.coveredBranches(), parsed.totalBranches()));
        report.setRequirementCoverage(calculateRequirementCoverage(projectId));
        report.setXmlFilePath(xmlPath.toString());
        reports.save(report);

        List<CoverageDetail> saved = details.saveAll(matchMethods(projectId, report.getId(), parsed.methods()));
        // Upload lại vẫn giữ status COMPLETED nếu project đã hoàn tất
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            project.setStatus(ProjectStatus.COVERAGE_ANALYZED);
            projects.save(project);
        }
        return toDto(report, saved, projectId);
    }

    @Transactional(readOnly = true)
    public Optional<CoverageReportDto> latest(Long projectId) {
        ensure(projectId);
        return reports.findTopByProjectIdOrderByIdDesc(projectId)
                .map(report -> toDto(report, details.findByReportId(report.getId()), projectId));
    }

    /** % Business Rule đã duyệt có ít nhất 1 Test Case (qua test_plan_covered_rule). */
    @Transactional(readOnly = true)
    public BigDecimal calculateRequirementCoverage(Long projectId) {
        var approvedRules = rules.findByProjectIdAndStatus(projectId, ReviewStatus.APPROVED);
        if (approvedRules.isEmpty()) {
            return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        List<Long> planIdsWithCases = plans.findByProjectId(projectId).stream()
                .map(TestPlan::getId)
                .filter(planId -> !cases.findByTestPlanId(planId).isEmpty())
                .toList();
        Set<Long> coveredRuleIds = planIdsWithCases.isEmpty() ? Set.of()
                : coveredRules.findByTestPlanIdIn(planIdsWithCases).stream()
                        .map(link -> link.getBusinessRuleId())
                        .collect(Collectors.toSet());
        long covered = approvedRules.stream().filter(rule -> coveredRuleIds.contains(rule.getId())).count();
        return percent((int) covered, approvedRules.size());
    }

    /**
     * Khớp method trong jacoco.xml với java_method đã phân tích: theo qualifiedName
     * của class + tên method, khử overload bằng vị trí dòng. Method không khớp thì
     * bỏ qua (không có tên hiển thị); tổng coverage vẫn đúng nhờ counter cấp report.
     */
    private List<CoverageDetail> matchMethods(Long projectId, Long reportId, List<ParsedMethod> parsedMethods) {
        Map<String, Long> classIdByName = classes.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(JavaClass::getQualifiedName, JavaClass::getId, (a, b) -> a));
        List<Long> classIds = List.copyOf(new HashSet<>(classIdByName.values()));
        Map<Long, List<JavaMethod>> methodsByClassId = classIds.isEmpty() ? Map.of()
                : methods.findByClassIdIn(classIds).stream().collect(Collectors.groupingBy(JavaMethod::getClassId));
        return parsedMethods.stream()
                .map(pm -> toDetail(pm, reportId, findMethod(pm, classIdByName, methodsByClassId)))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private JavaMethod findMethod(ParsedMethod pm, Map<String, Long> classIdByName,
            Map<Long, List<JavaMethod>> methodsByClassId) {
        Long classId = classIdByName.get(pm.qualifiedClassName());
        if (classId == null) {
            return null;
        }
        List<JavaMethod> candidates = methodsByClassId.getOrDefault(classId, List.of()).stream()
                .filter(m -> m.getMethodName().equals(pm.methodName()))
                .toList();
        // Overload: ưu tiên method có khoảng dòng chứa dòng đầu tiên trong jacoco
        return candidates.stream()
                .filter(m -> m.getLineStart() != null && m.getLineEnd() != null
                        && pm.firstLine() >= m.getLineStart() && pm.firstLine() <= m.getLineEnd())
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
    }

    private CoverageDetail toDetail(ParsedMethod pm, Long reportId, JavaMethod method) {
        if (method == null) {
            return null;
        }
        CoverageDetail detail = new CoverageDetail();
        detail.setReportId(reportId);
        detail.setMethodId(method.getId());
        detail.setLineCoverage(pm.lineCoverage());
        detail.setBranchCoverage(pm.branchCoverage());
        detail.setMissedLines(pm.missedLines());
        detail.setMissedBranches(pm.missedBranches());
        detail.setHasGap(pm.lineCoverage().compareTo(LINE_THRESHOLD) < 0
                || pm.branchCoverage().compareTo(BRANCH_THRESHOLD) < 0);
        return detail;
    }

    private CoverageReportDto toDto(CoverageReport report, List<CoverageDetail> reportDetails, Long projectId) {
        Map<Long, JavaClass> classById = classes.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(JavaClass::getId, Function.identity()));
        List<Long> methodIds = reportDetails.stream().map(CoverageDetail::getMethodId).toList();
        Map<Long, JavaMethod> methodById = methodIds.isEmpty() ? Map.of()
                : methods.findAllById(methodIds).stream()
                        .collect(Collectors.toMap(JavaMethod::getId, Function.identity()));
        Set<Long> refinableMethodIds = plans.findByProjectId(projectId).stream()
                .filter(plan -> plan.getStatus() == ReviewStatus.APPROVED)
                .map(TestPlan::getBusinessRuleId)
                .map(rules::findById)
                .flatMap(Optional::stream)
                .filter(rule -> rule.getStatus() == ReviewStatus.APPROVED)
                .map(rule -> rule.getMethodId())
                .collect(Collectors.toSet());
        // Vòng upload thứ mấy + số liệu vòng liền trước để hiển thị tiến bộ sau khi đóng gap
        List<CoverageReport> history = reports.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(CoverageReport::getId))
                .toList();
        int round = history.stream().map(CoverageReport::getId).toList().indexOf(report.getId()) + 1;
        CoverageReport previous = round > 1 ? history.get(round - 2) : null;
        Map<Long, CoverageDetail> previousDetailByMethod = previous == null ? Map.of()
                : details.findByReportId(previous.getId()).stream()
                        .collect(Collectors.toMap(CoverageDetail::getMethodId, Function.identity(), (a, b) -> a));
        List<CoverageGapDto> gaps = reportDetails.stream()
                .filter(d -> Boolean.TRUE.equals(d.getHasGap()))
                .sorted(Comparator.comparing(CoverageDetail::getLineCoverage))
                .map(d -> toGap(d, previousDetailByMethod.get(d.getMethodId()),
                        methodById.get(d.getMethodId()), classById, refinableMethodIds))
                .toList();
        return new CoverageReportDto(report.getId(), report.getProjectId(), round <= 0 ? 1 : round,
                report.getLineCoverage(), report.getBranchCoverage(), report.getRequirementCoverage(),
                previous == null ? null : previous.getLineCoverage(),
                previous == null ? null : previous.getBranchCoverage(),
                previous == null ? null : previous.getRequirementCoverage(),
                report.getTotalLines(), report.getCoveredLines(), report.getTotalBranches(),
                report.getCoveredBranches(), report.getUploadedAt(), gaps);
    }

    private CoverageGapDto toGap(CoverageDetail detail, CoverageDetail previousDetail, JavaMethod method,
            Map<Long, JavaClass> classById, Set<Long> refinableMethodIds) {
        JavaClass cls = method == null ? null : classById.get(method.getClassId());
        String className = cls == null ? "" : cls.getClassName();
        String methodName = method == null ? "" : method.getMethodName();
        String risk = detail.getLineCoverage().compareTo(HIGH_RISK_THRESHOLD) < 0 ? "HIGH" : "MEDIUM";
        boolean linkedService = cls != null && cls.getClassType() == ClassType.SERVICE
                && refinableMethodIds.contains(detail.getMethodId());
        boolean unchanged = linkedService && previousDetail != null
                && Objects.equals(detail.getMissedLines(), previousDetail.getMissedLines())
                && Objects.equals(detail.getMissedBranches(), previousDetail.getMissedBranches());
        boolean refinable = linkedService && !unchanged;
        String gapSuggestion = unchanged
                ? "Không thể cải thiện tự động sau vòng trước; cần kiểm tra nhánh không thể tiếp cận hoặc bổ sung test thủ công"
                : refinable
                ? suggestion(detail)
                : cls != null && cls.getClassType() == ClassType.SERVICE
                        ? "Chưa liên kết với Business Rule và Test Plan đã duyệt"
                        : "Ngoài phạm vi sinh Service Unit Test của GreyTest";
        return new CoverageGapDto(detail.getMethodId(), className, methodName,
                detail.getLineCoverage(), detail.getBranchCoverage(),
                detail.getMissedLines(), detail.getMissedBranches(), risk, gapSuggestion, refinable);
    }

    /** Gợi ý heuristic dựa trên phần bị miss (không gọi AI). */
    private String suggestion(CoverageDetail detail) {
        List<Integer> missedBranches = detail.getMissedBranches();
        List<Integer> missedLines = detail.getMissedLines();
        if (missedBranches != null && !missedBranches.isEmpty()) {
            return "Bổ sung test case cho các nhánh điều kiện chưa cover (dòng " + joinLines(missedBranches) + ")";
        }
        if (missedLines != null && !missedLines.isEmpty()) {
            return "Bổ sung test case cho các dòng chưa được thực thi (dòng " + joinLines(missedLines) + ")";
        }
        return "Bổ sung test case để tăng coverage cho method này";
    }

    private String joinLines(List<Integer> lines) {
        String joined = lines.stream().limit(5).map(String::valueOf).collect(Collectors.joining(", "));
        return lines.size() > 5 ? joined + ", ..." : joined;
    }

    private ParsedReport parseStoredXml(Path xmlPath) {
        try (InputStream in = Files.newInputStream(xmlPath)) {
            return parser.parse(in);
        } catch (IOException e) {
            throw new StorageException("Không đọc được file coverage XML đã lưu", e);
        }
    }

    private BigDecimal percent(int covered, int total) {
        if (total == 0) {
            return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(covered * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    private Project ensure(Long id) {
        return projects.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }
}
