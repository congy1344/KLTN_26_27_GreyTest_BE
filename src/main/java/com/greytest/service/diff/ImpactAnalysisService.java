package com.greytest.service.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        List<MethodDiffItem> changedMethods = diffItems.stream()
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
            // Thêm các caller là service methods
            if (item.callerMethods() != null) {
                affectedServiceMethods.addAll(item.callerMethods());
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

    private boolean matchesChangedMethod(JavaMethod method, MethodDiffItem changed) {
        if (!Objects.equals(method.getMethodName(), changed.methodName())) return false;
        JavaClass javaClass = method.getClassId() == null
                ? null
                : javaClassRepository.findById(method.getClassId()).orElse(null);
        if (javaClass == null || !Objects.equals(javaClass.getQualifiedName(), changed.qualifiedClassName())) {
            return false;
        }
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
