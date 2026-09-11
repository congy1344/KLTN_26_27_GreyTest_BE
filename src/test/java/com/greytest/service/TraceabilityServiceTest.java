package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TraceabilityRow;

class TraceabilityServiceTest {

    private final BusinessRuleRepository rules = mock(BusinessRuleRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ServiceScopeResolver scopes = mock(ServiceScopeResolver.class);
    private final TraceabilityService service = new TraceabilityService(rules, projects, scopes);

    @Test
    void matrixOnlyContainsRulesFromSelectedService() {
        Project project = new Project();
        project.setId(7L);
        project.setStatus(ProjectStatus.COMPLETED);
        BusinessRule billingRule = rule(1L, 101L, "BR-001");
        BusinessRule accountRule = rule(2L, 202L, "BR-002");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(scopes.resolve(7L, "billing-service"))
                .thenReturn(new ServiceScopeResolver.ServiceScope("billing-service", Set.of(), Set.of(101L)));
        when(rules.findByProjectId(7L)).thenReturn(List.of(billingRule, accountRule));
        TraceabilityRow billingTrace = row(1L, "BR-001");
        TraceabilityRow accountTrace = row(2L, "BR-002");
        when(rules.findTraceabilityRows(7L)).thenReturn(List.of(billingTrace, accountTrace));

        var matrix = service.getMatrix(7L, "billing-service");

        assertThat(matrix.rows()).extracting(row -> row.ruleCode()).containsExactly("BR-001");
    }

    private BusinessRule rule(Long id, Long methodId, String code) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setMethodId(methodId);
        rule.setRuleCode(code);
        return rule;
    }

    private TraceabilityRow row(Long ruleId, String code) {
        TraceabilityRow row = mock(TraceabilityRow.class);
        when(row.getRuleId()).thenReturn(ruleId);
        when(row.getRuleCode()).thenReturn(code);
        when(row.getRuleDescription()).thenReturn(code);
        return row;
    }
}
