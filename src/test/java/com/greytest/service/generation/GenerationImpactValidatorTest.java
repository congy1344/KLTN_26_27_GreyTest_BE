package com.greytest.service.generation;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
class GenerationImpactValidatorTest {
    @Test void missingCorruptOrPartialImpactCannotBecomeAnEmptySuccessfulStage() {
        for(String value:new String[]{null,"","{","{}","null"})
            assertThatThrownBy(()->GenerationImpactValidator.parse(new ObjectMapper(),value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitlyAnalyzedEmptyImpactRemainsAValidNoOp() {
        var result=GenerationImpactValidator.parse(new ObjectMapper(),"{\"changedMethods\":[],\"affectedServiceMethods\":[],\"affectedBusinessRuleIds\":[],\"affectedTestPlanIds\":[],\"affectedTestCaseIds\":[],\"affectedUnitTestIds\":[]}");
        assertThat(result.changedMethods()).isEmpty();
    }
}
