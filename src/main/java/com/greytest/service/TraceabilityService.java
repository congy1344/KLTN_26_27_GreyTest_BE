package com.greytest.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.TraceabilityMatrixDto;
import com.greytest.dto.TraceabilityRowDto;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TraceabilityRow;

/** Truy vấn Traceability Matrix (BR → Plan → Case → Unit Test) từ view v_traceability. */
@Service
public class TraceabilityService {

    private final BusinessRuleRepository rules;
    private final ProjectRepository projects;
    private final ServiceScopeResolver scopeResolver;

    public TraceabilityService(BusinessRuleRepository rules, ProjectRepository projects,
            ServiceScopeResolver scopeResolver) {
        this.rules = rules;
        this.projects = projects;
        this.scopeResolver = scopeResolver;
    }

    @Transactional(readOnly = true)
    public TraceabilityMatrixDto getMatrix(Long projectId) {
        return getMatrix(projectId, null);
    }

    @Transactional(readOnly = true)
    public TraceabilityMatrixDto getMatrix(Long projectId, String servicePath) {
        projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        Set<Long> scopedRuleIds = servicePath == null ? null : scopeRuleIds(projectId, servicePath);
        List<TraceabilityRowDto> rows = rules.findTraceabilityRows(projectId).stream()
                .filter(row -> scopedRuleIds == null || scopedRuleIds.contains(row.getRuleId()))
                .map(this::toDto).toList();
        // Rule không có plan nào cover (planId null do LEFT JOIN) = rule chưa được test
        List<TraceabilityRowDto> uncovered = rows.stream().filter(row -> row.planId() == null).toList();
        return new TraceabilityMatrixDto(projectId, rows, uncovered);
    }

    private Set<Long> scopeRuleIds(Long projectId, String servicePath) {
        Set<Long> methodIds = scopeResolver.resolve(projectId, servicePath).methodIds();
        return rules.findByProjectId(projectId).stream()
                .filter(rule -> methodIds.contains(rule.getMethodId()))
                .map(rule -> rule.getId())
                .collect(Collectors.toSet());
    }

    private TraceabilityRowDto toDto(TraceabilityRow row) {
        return new TraceabilityRowDto(row.getRuleId(), row.getRuleCode(), row.getRuleDescription(),
                row.getPlanId(), row.getPlanCode(), row.getPlanTitle(), row.getTestType(),
                row.getCaseId(), row.getCaseCode(), row.getCaseDescription(),
                row.getUnitTestId(), row.getUnitTestName());
    }
}
