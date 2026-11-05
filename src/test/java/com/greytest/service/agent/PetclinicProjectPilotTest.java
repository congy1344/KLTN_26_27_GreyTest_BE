package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Kiểm tra hợp đồng baseline và nhãn pilot trước khi gọi mô hình thật. */
class PetclinicProjectPilotTest {
    @Test void baselineRulesExcludedFromLlmOnlyAndIncludedInPromptBased() throws Exception {
        var method=new JavaMethodDto(1L,"find", "String",List.of(),List.of(),"PUBLIC","public String find(){return null;}",1,1,List.of(),List.of(),List.of());
        var owner=new JavaClassDto(1L,"fixture","Service","fixture.Service","SERVICE","src/main/java/fixture/Service.java",
                "package fixture; class Service{}",List.of(),List.of(method));
        var source=new ClassContextDto(1L,"fixture","Service","fixture.Service","SERVICE",owner.filePath(),owner.sourceCode(),List.of(),List.of());
        var rule=new BusinessRuleContextDto(1L,1L,"BR-1","UNIQUE_PRIVATE_RULE_TEXT",null,"AI_GENERATED","APPROVED",false,null);
        String raw=PetclinicProjectPilot.baselinePrompt("llm-only",owner,method,List.of(source),List.of(rule));
        String prompted=PetclinicProjectPilot.baselinePrompt("prompt-based",owner,method,List.of(source),List.of(rule));
        assertThat(raw).doesNotContain("UNIQUE_PRIVATE_RULE_TEXT").contains("plans","cases","unit_tests",owner.sourceCode());
        assertThat(prompted).contains("UNIQUE_PRIVATE_RULE_TEXT",owner.sourceCode());
        assertThat(PetclinicProjectPilot.ORACLE).isEqualTo("SOURCE_DERIVED_UNVERIFIED");
    }
    @Test void failedAttemptDoesNotBecomeMeasuredQualityOrExposeProviderMessage() {
        var result=PetclinicProjectPilot.failure(new IllegalStateException("PRIVATE_PROVIDER_MESSAGE"));
        assertThat(result).containsEntry("status","GENERATION_FAILED").containsEntry("requirementCoverage","N/A").containsEntry("umr","N/A");
        assertThat(result.toString()).doesNotContain("PRIVATE_PROVIDER_MESSAGE");
    }
    @Test void checkpointRejectsDifferentAnalysisAndTargetSet() {
        var metadata=Map.<String,Object>of("model","m","sourceSha256","s","options",Map.of("maxTokens",123),
                "productionAnalysisSha256","a","targets",List.of(1,2));
        var previous=PetclinicProjectPilot.JSON.valueToTree(metadata);
        PetclinicProjectPilot.validateCheckpoint(previous,metadata);
        var changed=new java.util.HashMap<>(metadata);changed.put("productionAnalysisSha256","changed");
        assertThatThrownBy(()->PetclinicProjectPilot.validateCheckpoint(previous,changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productionAnalysisSha256");
        changed.put("productionAnalysisSha256","a");changed.put("targets",List.of(1));
        assertThatThrownBy(()->PetclinicProjectPilot.validateCheckpoint(previous,changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targets");
    }
    @Test void productionExportKeepsDistinctAssertionsWithNameConflicts(@TempDir Path directory) throws Exception {
        var array=PetclinicProjectPilot.JSON.createArrayNode();
        for(int i=1;i<=2;i++) {
            var node=array.addObject();node.put("case_id",i);node.put("test_class_name","SampleTest");node.put("package_name","fixture");
            node.put("test_method_name","case"+i);
            node.put("source_code","package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; "
                    +"class SampleTest { @Test void case"+i+"(){ assertEquals("+i+","+i+"); } }");
        }
        PetclinicProjectPilot.exportUnits(directory.resolve("valid"),array);
        var manifest=PetclinicProjectPilot.JSON.readTree(directory.resolve("valid/export-manifest.json").toFile());
        assertThat(manifest.path("originalDistinctTestBodies").asInt()).isEqualTo(2);
        assertThat(manifest.path("exportedDistinctTestBodies").asInt()).isEqualTo(2);
        assertThat(manifest.path("missingOriginalTestBodies").asInt()).isZero();
        // Cùng chữ ký nhưng assertion khác phải được bảo toàn bằng các class riêng.
        ((com.fasterxml.jackson.databind.node.ObjectNode)array.get(1)).put("test_method_name","case1")
                .put("source_code","package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; "
                    +"class SampleTest { @Test void case1(){ assertEquals(2,2); } }");
        PetclinicProjectPilot.exportUnits(directory.resolve("conflict"),array);
        var conflict=PetclinicProjectPilot.JSON.readTree(directory.resolve("conflict/export-manifest.json").toFile());
        assertThat(conflict.path("originalDistinctTestBodies").asInt()).isEqualTo(2);
        assertThat(conflict.path("exportedDistinctTestBodies").asInt()).isEqualTo(2);
        assertThat(conflict.path("missingOriginalTestBodies").asInt()).isZero();
        // Hai fixture khác nhau vẫn là hai class độc lập, chỉ nằm chung một file.
        assertThat(conflict.path("exports").size()).isEqualTo(1);
    }
}
