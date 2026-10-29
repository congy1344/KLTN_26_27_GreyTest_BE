package com.greytest.service.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.JavaClass;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

import com.greytest.service.ServiceScopeResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Service xác định phạm vi ảnh hưởng (Impact Analysis) của các method bị thay đổi
 * tới các artifacts hiện có trong database (BR, TP, TC, UT).
 */
@Slf4j
@Service
public class ImpactAnalysisService {

    private final JavaMethodRepository javaMethodRepository;
    private final JavaClassRepository javaClassRepository;
    private final BusinessRuleRepository businessRuleRepository;
    private final TestPlanRepository testPlanRepository;
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    private final TestCaseRepository testCaseRepository;
    private final UnitTestRepository unitTestRepository;

    public ImpactAnalysisService(
            JavaMethodRepository javaMethodRepository,
            JavaClassRepository javaClassRepository,
            BusinessRuleRepository businessRuleRepository,
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            TestCaseRepository testCaseRepository,
            UnitTestRepository unitTestRepository) {
        this.javaMethodRepository = javaMethodRepository;
        this.javaClassRepository = javaClassRepository;
        this.businessRuleRepository = businessRuleRepository;
        this.testPlanRepository = testPlanRepository;
        this.testPlanCoveredRuleRepository = testPlanCoveredRuleRepository;
        this.testCaseRepository = testCaseRepository;
        this.unitTestRepository = unitTestRepository;
    }

    public ImpactSummaryDto analyzeImpact(Long projectId, List<MethodDiffItem> diffItems) {
        return analyzeImpact(projectId, diffItems, null);
    }

    public ImpactSummaryDto analyzeImpact(Long projectId, List<MethodDiffItem> diffItems, String servicePath) {
        String normalizedScope = (servicePath != null && !servicePath.isBlank())
                ? ServiceScopeResolver.normalizeServicePath(servicePath)
                : null;

        // Chỉ giữ lại các phương thức thuộc tầng Service
        // Nếu có chọn service cụ thể, chỉ giữ lại các phương thức thuộc service đó
        List<MethodDiffItem> scopedItems = normalizedScope == null ? diffItems : alignModulePaths(projectId, diffItems);
        List<MethodDiffItem> effectiveItems = includeResolvedServiceCallers(scopedItems);
        List<MethodDiffItem> changedMethods = effectiveItems.stream()
                .filter(d -> d.diffType() != MethodDiffType.UNCHANGED)
                .filter(MethodDiffItem::isServiceMethod)
                .filter(d -> matchesServiceScope(projectId, d, normalizedScope))
                .toList();

        int addedCount = (int) changedMethods.stream().filter(d -> d.diffType() == MethodDiffType.ADDED).count();
        int modifiedCount = (int) changedMethods.stream().filter(d -> d.diffType() == MethodDiffType.MODIFIED).count();
        int deletedCount = (int) changedMethods.stream().filter(d -> d.diffType() == MethodDiffType.DELETED).count();

        // 1. Xác định affected service methods
        Set<String> affectedServiceMethods = new HashSet<>();
        for (MethodDiffItem item : changedMethods) {
            if (item.isServiceMethod()) {
                affectedServiceMethods.add(item.methodKey());
            }
        }

        // 2. Tìm Business Rules bị ảnh hưởng
        Set<Long> affectedRuleIds = new HashSet<>();
        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId);

        for (BusinessRule rule : existingRules) {
            for (MethodDiffItem changed : changedMethods) {
                if (rule.getMethodId() != null) {
                    javaMethodRepository.findById(rule.getMethodId()).ifPresent(m -> {
                        if (matchesChangedMethod(m, changed)) {
                            affectedRuleIds.add(rule.getId());
                        }
                    });
                }
            }
        }

        // 3. Tìm Test Plans bị ảnh hưởng
        Set<Long> affectedPlanIds = new HashSet<>();
        for (Long ruleId : affectedRuleIds) {
            testPlanRepository.findByBusinessRuleId(ruleId)
                    .forEach(tp -> affectedPlanIds.add(tp.getId()));
            testPlanCoveredRuleRepository.findByBusinessRuleId(ruleId)
                    .forEach(cr -> affectedPlanIds.add(cr.getTestPlanId()));
        }

        // 4. Tìm Test Cases bị ảnh hưởng
        Set<Long> affectedCaseIds = new HashSet<>();
        for (Long planId : affectedPlanIds) {
            testCaseRepository.findByTestPlanId(planId)
                    .forEach(tc -> affectedCaseIds.add(tc.getId()));
        }

        // 5. Tìm Unit Tests bị ảnh hưởng
        Set<Long> affectedUnitTestIds = new HashSet<>();
        for (Long caseId : affectedCaseIds) {
            UnitTest ut = unitTestRepository.findByTestCaseId(caseId);
            if (ut != null) {
                affectedUnitTestIds.add(ut.getId());
            }
        }

        log.info("Phân tích ảnh hưởng cho project {}: {} changed methods, {} affected service methods, {} BRs, {} TPs, {} TCs, {} UTs",
                projectId, changedMethods.size(), affectedServiceMethods.size(),
                affectedRuleIds.size(), affectedPlanIds.size(), affectedCaseIds.size(), affectedUnitTestIds.size());

        return new ImpactSummaryDto(
                changedMethods.size(),
                addedCount,
                modifiedCount,
                deletedCount,
                changedMethods,
                new ArrayList<>(affectedServiceMethods),
                new ArrayList<>(affectedRuleIds),
                new ArrayList<>(affectedPlanIds),
                new ArrayList<>(affectedCaseIds),
                new ArrayList<>(affectedUnitTestIds)
        );
    }

    private List<MethodDiffItem> includeResolvedServiceCallers(List<MethodDiffItem> items) {
        Map<String, MethodDiffItem> methods = new java.util.LinkedHashMap<>();
        for (MethodDiffItem item : items) methods.put(scopedKey(item), item);
        for (MethodDiffItem changed : items) {
            if (changed.diffType() == MethodDiffType.UNCHANGED || changed.callerMethods() == null) continue;
            for (String callerKey : changed.callerMethods()) {
                for (MethodDiffItem caller : items) {
                    if (!caller.isServiceMethod() || !caller.methodKey().equals(callerKey)) continue;
                    // callerMethods của diff chỉ chứa caller cùng module; cross-module được diff dependency riêng.
                    if (!Objects.equals(changed.servicePath(), caller.servicePath())) continue;
                    if (caller.diffType() != MethodDiffType.UNCHANGED) continue;
                    methods.put(scopedKey(caller), new MethodDiffItem(caller.className(), caller.qualifiedClassName(),
                            caller.methodName(), caller.signature(), caller.methodKey(), MethodDiffType.MODIFIED,
                            "Resolved source dependency đã thay đổi", caller.beforeSource(), caller.afterSource(),
                            caller.callerMethods(), true, caller.servicePath()));
                }
            }
        }
        return new ArrayList<>(methods.values());
    }

    private String scopedKey(MethodDiffItem item) {
        return Objects.toString(item.servicePath(), "") + ":" + item.methodKey();
    }

    /** Giữ scope module hiện hành khi ZIP mới chỉ thêm/bớt thư mục bọc ngoài. */
    private List<MethodDiffItem> alignModulePaths(Long projectId, List<MethodDiffItem> diffItems) {
        Map<String, Set<String>> baselineModules = new HashMap<>();
        for (JavaClass javaClass : javaClassRepository.findByProjectId(projectId)) {
            if (javaClass.getQualifiedName() == null || javaClass.getFilePath() == null) continue;
            baselineModules.computeIfAbsent(javaClass.getQualifiedName(), ignored -> new HashSet<>())
                    .add(ServiceScopeResolver.modulePath(javaClass.getFilePath()));
        }
        Map<String, Set<String>> aliases = new HashMap<>();
        // Dùng cả class không đổi làm mốc để method/class mới trong cùng module không bị lọc mất.
        for (MethodDiffItem item : diffItems) {
            if (item.servicePath() == null || item.diffType() == MethodDiffType.DELETED) continue;
            String candidate = ServiceScopeResolver.normalizeServicePath(item.servicePath());
            for (String baseline : baselineModules.getOrDefault(item.qualifiedClassName(), Set.of())) {
                if (candidate.equals(baseline) || candidate.equals(".") || baseline.equals(".")
                        || candidate.endsWith("/" + baseline) || baseline.endsWith("/" + candidate)) {
                    aliases.computeIfAbsent(candidate, ignored -> new HashSet<>()).add(baseline);
                }
            }
        }
        return diffItems.stream().map(item -> {
            if (item.servicePath() == null || item.diffType() == MethodDiffType.DELETED) return item;
            Set<String> matches = aliases.getOrDefault(ServiceScopeResolver.normalizeServicePath(item.servicePath()), Set.of());
            // Không đoán module nếu cùng đường dẫn ứng viên khớp nhiều module baseline.
            if (matches.size() != 1) return item;
            String servicePath = matches.iterator().next();
            return new MethodDiffItem(item.className(), item.qualifiedClassName(), item.methodName(), item.signature(),
                    item.methodKey(), item.diffType(), item.reason(), item.beforeSource(), item.afterSource(),
                    item.callerMethods(), item.isServiceMethod(), servicePath);
        }).toList();
    }

    private boolean matchesChangedMethod(JavaMethod method, MethodDiffItem changed) {
        if (!Objects.equals(method.getMethodName(), changed.methodName())) return false;
        JavaClass javaClass = method.getClassId() == null
                ? null
                : javaClassRepository.findById(method.getClassId()).orElse(null);
        if (javaClass == null || !Objects.equals(javaClass.getQualifiedName(), changed.qualifiedClassName())) {
            return false;
        }
        if (changed.servicePath() != null && !Objects.equals(
                ServiceScopeResolver.modulePath(javaClass.getFilePath()), changed.servicePath())) return false;
        String parameterTypes = method.getParameters() == null
                ? ""
                : method.getParameters().stream().map(param -> param.type()).collect(Collectors.joining(","));
        return Objects.equals(method.getMethodName() + "(" + parameterTypes + ")", changed.signature());
    }

    private boolean matchesServiceScope(Long projectId, MethodDiffItem diffItem, String normalizedScope) {
        if (normalizedScope == null) return true;
        if (diffItem.servicePath() != null) {
            String itemScope = ServiceScopeResolver.normalizeServicePath(diffItem.servicePath());
            return itemScope.equals(normalizedScope);
        }
        // Fallback kiểm tra qua JavaClass trong database nếu diffItem chưa có servicePath
        List<JavaClass> classes = javaClassRepository.findByProjectId(projectId);
        return classes.stream()
                .filter(c -> Objects.equals(c.getQualifiedName(), diffItem.qualifiedClassName()))
                .findFirst()
                .map(c -> {
                    String scope = ServiceScopeResolver.modulePath(c.getFilePath());
                    return ServiceScopeResolver.normalizeServicePath(scope).equals(normalizedScope);
                })
                .orElse(true);
    }
}
