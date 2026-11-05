package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

class GeneratedImportRepairTest {
    private final UnitTestContextDto context = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .convertValue(Map.of("classes", List.of(
                    Map.of("className", "TokenUtil", "qualifiedName", "com.acme.shop.security.TokenUtil"),
                    Map.of("className", "Item", "qualifiedName", "com.acme.shop.core.Item"),
                    Map.of("className", "Shop", "qualifiedName", "com.acme.shop.app.Shop"),
                    Map.of("className", "Twin", "qualifiedName", "com.acme.shop.a.Twin"),
                    Map.of("className", "Twin", "qualifiedName", "com.acme.shop.b.Twin"))), UnitTestContextDto.class);

    private String repair(String source) {
        var test = new GeneratedUnitTestDto(1L, "ShopTest", "run", "com.acme.shop.app", "NEW_TEST", source);
        return GeneratedImportRepair.repair(context, new UnitTestResponseDto(List.of(test))).unitTests().get(0).sourceCode();
    }

    @Test void replacesAGuessedPackageWhenTheSourceDeclaresOneClassWithThatName() {
        assertThat(repair("package com.acme.shop.app;\nimport com.acme.shop.app.security.TokenUtil;\nclass ShopTest { TokenUtil token; }"))
                .contains("import com.acme.shop.security.TokenUtil;").doesNotContain("app.security");
    }

    @Test void addsMissingImportsOnlyWhenThereIsOneAnswer() {
        String repaired = repair("package com.acme.shop.app;\nclass ShopTest { java.util.List<Item> items = new ArrayList<Item>(); "
                + "Object empty = Collections.emptyList(); java.math.BigDecimal value = BigDecimal.ONE; Shop local; String text; Twin twin; }");
        assertThat(repaired).contains("import com.acme.shop.core.Item;", "import java.util.ArrayList;", "import java.util.Collections;", "import java.math.BigDecimal;")
                // Cùng package, java.lang và tên có hai khai báo đều không được tự thêm.
                .doesNotContain("import com.acme.shop.app.Shop;", "import java.lang.String;", ".Twin;");
    }

    @Test void takesLibraryPackageFromTheImportsOfTheSuppliedSource() {
        var withSource = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).convertValue(Map.of("classes", List.of(
                Map.of("className", "Shop", "qualifiedName", "com.acme.shop.app.Shop", "sourceCode", "package com.acme.shop.app;\nimport vendor.tools.Encoder;\nclass Shop { }"))), UnitTestContextDto.class);
        var test = new GeneratedUnitTestDto(1L, "ShopTest", "run", "com.acme.shop.app", "NEW_TEST", "package com.acme.shop.app;\nclass ShopTest { Encoder encoder; }");
        assertThat(GeneratedImportRepair.repair(withSource, new UnitTestResponseDto(List.of(test))).unitTests().get(0).sourceCode()).contains("import vendor.tools.Encoder;");
    }

    @Test void keepsALibraryImportThatTheSuppliedSourceReallyUses() {
        var withSource = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).convertValue(Map.of("classes", List.of(
                Map.of("className", "Result", "qualifiedName", "com.acme.shop.model.Result"),
                Map.of("className", "Shop", "qualifiedName", "com.acme.shop.app.Shop", "sourceCode", "package com.acme.shop.app;\nimport com.acme.lib.Result;\nclass Shop { }"))), UnitTestContextDto.class);
        String source = "package com.acme.shop.app;\nimport com.acme.lib.Result;\nclass ShopTest { Result result; }";
        var test = new GeneratedUnitTestDto(1L, "ShopTest", "run", "com.acme.shop.app", "NEW_TEST", source);
        assertThat(GeneratedImportRepair.repair(withSource, new UnitTestResponseDto(List.of(test))).unitTests().get(0).sourceCode()).isEqualTo(source);
    }

    @Test void leavesLibraryImportsAndTestBodyUntouched() {
        String source = "package com.acme.shop.app;\nimport vendor.tools.TokenUtil;\nimport java.util.List;\nclass ShopTest { TokenUtil token; List<String> names; }";
        assertThat(repair(source)).isEqualTo(source);
    }

    @Test void keepsBrokenSourceForTheValidatorToReport() {
        assertThat(repair("package com.acme; class Broken {")).isEqualTo("package com.acme; class Broken {");
    }
}
