package com.greytest.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.diff.ImpactSummaryDto;

/** Không xem impact bị thiếu/hỏng như một phân tích thành công với không có target. */
public final class GenerationImpactValidator {
    private GenerationImpactValidator() { }
    public static ImpactSummaryDto parse(ObjectMapper mapper,String json) {
        if(json==null || json.isBlank()) throw new IllegalArgumentException("Impact analysis is missing; analyze the source before generation");
        try {
            var impact=mapper.readValue(json,ImpactSummaryDto.class);
            if(impact==null || impact.changedMethods()==null || impact.affectedServiceMethods()==null
                || impact.affectedBusinessRuleIds()==null || impact.affectedTestPlanIds()==null
                || impact.affectedTestCaseIds()==null || impact.affectedUnitTestIds()==null)
                throw new IllegalArgumentException("Impact analysis is incomplete; analyze the source again");
            return impact;
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Impact analysis is invalid; analyze the source again",invalid); }
    }
}
