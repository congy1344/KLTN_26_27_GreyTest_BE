package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;

import jakarta.validation.Validation;

class BusinessRuleRepairRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationResponseParser parser = new GenerationResponseParser(mapper,
            Validation.buildDefaultValidatorFactory().getValidator());

    @ParameterizedTest
    @ValueSource(strings = {"commerce-customer-invalid-1.json", "commerce-customer-invalid-2.json"})
    void repairsRecordedCommerceResponseWithAllDecisionIdsAndPreservesValidRules(String invalidFile, @TempDir Path logs) throws Exception {
        var context = context();
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildBusinessRuleGenerationContext(377L, Set.of(95432L, 95433L, 95434L))).thenReturn(context);
        var client = mock(LlmClient.class);
        String corrected = mapper.writeValueAsString(correctedResponse());
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(fixture(invalidFile), corrected);
        var agent = agent(contexts, client, logs);

        BusinessRuleResponseDto response = agent.generateBusinessRules(377L, Set.of(95432L, 95433L, 95434L));

        assertThat(response.rules()).hasSize(9);
        assertThat(response.rules().stream().filter(rule -> rule.methodId().equals(95434L)))
                .extracting(GeneratedBusinessRuleDto::branchId).containsExactly("TERNARY-1", "STMT-4", "TERNARY-2");
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(0)).contains("# Required Business Rule decisions",
                "method_id=95434 (addSpend): required branch_ids=[TERNARY-1, TERNARY-2]");
        String repair = prompts.getAllValues().get(1);
        assertThat(repair).contains("Missing source decisions for method 95434: [TERNARY-2]",
                "# Business Rule decision repair", "5000", "1000", "each TERNARY-n is a separate decision",
                "Preserve every already valid rule", "complete rules array", "not only the additions");
        if (invalidFile.endsWith("1.json")) {
            assertThat(repair).contains("source exception message", "NotFoundException", "Customer");
        }
        var validBefore = parser.parse(fixture("commerce-customer-invalid-2.json"), BusinessRuleResponseDto.class).rules()
                .stream().filter(rule -> !rule.methodId().equals(95434L)).toList();
        assertThat(response.rules()).containsAll(validBefore);
    }

    @Test
    void reportsMissingMethodsAndDuplicateDecisionsTogether(@TempDir Path logs) throws Exception {
        List<GeneratedBusinessRuleDto> rules = new ArrayList<>(correctedResponse().rules());
        rules.removeIf(rule -> rule.methodId().equals(95433L) || "TERNARY-2".equals(rule.branchId()));
        rules.add(rules.stream().filter(rule -> "TERNARY-1".equals(rule.branchId())).findFirst().orElseThrow());

        assertThatThrownBy(() -> agent(mock(GenerationContextBuilder.class), mock(LlmClient.class), logs)
                .validateSnapshotOutput(GenerationProgressStage.BUSINESS_RULE, 377L, context(),
                        new BusinessRuleResponseDto(rules), null))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("Missing business rules for method 95433")
                .hasMessageContaining("Missing source decisions for method 95433: [IF-1]")
                .hasMessageContaining("Missing source decisions for method 95434: [TERNARY-2]")
                .hasMessageContaining("Duplicate source decisions for method 95434");
    }

    @Test
    void stillRejectsMissingDecisionsAfterBoundedCorrection(@TempDir Path logs) throws Exception {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildBusinessRuleGenerationContext(377L, Set.of(95432L, 95433L, 95434L))).thenReturn(context());
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(fixture("commerce-customer-invalid-2.json"));

        assertThatThrownBy(() -> agent(contexts, client, logs).generateBusinessRules(377L, Set.of(95432L, 95433L, 95434L)))
                .isInstanceOfSatisfying(LlmResponseException.class, failure -> {
                    assertThat(failure.getMessage()).contains("Missing source decisions", "TERNARY-2");
                    assertThat(failure.isRetryable()).isFalse();
                });
        verify(client, times(2)).complete(anyString(), any(LlmRequestOptions.class));
    }

    @Test
    void businessRuleCoveringMultipleDecisionsPassesValidation(@TempDir Path logs) throws Exception {
        List<GeneratedBusinessRuleDto> rules = new ArrayList<>(correctedResponse().rules());
        // Thay 2 rule riêng cho TERNARY-1 và TERNARY-2 bằng 1 rule gộp bao phủ cả 2 nhánh
        rules.removeIf(rule -> "TERNARY-1".equals(rule.branchId()) || "TERNARY-2".equals(rule.branchId()));
        rules.add(new GeneratedBusinessRuleDto(95434L,
                "Service xác định hạng theo chi tiêu: PLATINUM nếu >= 5000, GOLD nếu >= 1000, STANDARD nếu < 1000.",
                "BUSINESS_LOGIC", "TERNARY-1, TERNARY-2"));

        var agent = agent(mock(GenerationContextBuilder.class), mock(LlmClient.class), logs);
        // Không ném exception vì cả TERNARY-1 và TERNARY-2 đều được bao phủ
        agent.validateSnapshotOutput(GenerationProgressStage.BUSINESS_RULE, 377L, context(),
                new BusinessRuleResponseDto(rules), null);
    }

    private AIAgentService agent(GenerationContextBuilder contexts, LlmClient client, Path logs) {
        return new AIAgentService(contexts, new PromptManager(mapper), client, parser,
                new AiContextLogService(mapper, logs.toString(), false, false));
    }

    private BusinessRuleGenerationContextDto context() throws Exception {
        return mapper.readValue(fixture("commerce-customer-context.json"), BusinessRuleGenerationContextDto.class);
    }

    private BusinessRuleResponseDto correctedResponse() throws Exception {
        List<GeneratedBusinessRuleDto> rules = new ArrayList<>(parser.parse(
                fixture("commerce-customer-invalid-2.json"), BusinessRuleResponseDto.class).rules());
        rules.replaceAll(rule -> !"TERNARY-1".equals(rule.branchId()) ? rule : new GeneratedBusinessRuleDto(95434L,
                "Khi tổng chi tiêu từ 5000 trở lên, service đặt hạng thành PLATINUM; nếu dưới 5000, hạng phụ thuộc mốc 1000.",
                "BUSINESS_LOGIC", "TERNARY-1"));
        rules.add(new GeneratedBusinessRuleDto(95434L,
                "Khi tổng chi tiêu dưới 5000, service đặt hạng GOLD nếu đạt ít nhất 1000, hoặc STANDARD nếu dưới 1000.",
                "BUSINESS_LOGIC", "TERNARY-2"));
        return new BusinessRuleResponseDto(rules);
    }

    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/business-rule-repair/" + name)) {
            if (stream == null) throw new IllegalArgumentException("Missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
