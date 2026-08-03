package com.greytest.service;

import java.util.List;

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

    public TraceabilityService(BusinessRuleRepository rules, ProjectRepository projects) {
        this.rules = rules;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public TraceabilityMatrixDto getMatrix(Long projectId) {
        projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        List<TraceabilityRowDto> rows = rules.findTraceabilityRows(projectId).stream().map(this::toDto).toList();
        // Rule không có plan nào cover (planId null do LEFT JOIN) = rule chưa được test
        List<TraceabilityRowDto> uncovered = rows.stream().filter(row -> row.planId() == null).toList();
        return new TraceabilityMatrixDto(projectId, rows, uncovered);
    }

    private TraceabilityRowDto toDto(TraceabilityRow row) {
        return new TraceabilityRowDto(row.getRuleId(), row.getRuleCode(), row.getRuleDescription(),
                row.getPlanId(), row.getPlanCode(), row.getPlanTitle(), row.getTestType(),
                row.getCaseId(), row.getCaseCode(), row.getCaseDescription(),
                row.getUnitTestId(), row.getUnitTestName());
    }
}
