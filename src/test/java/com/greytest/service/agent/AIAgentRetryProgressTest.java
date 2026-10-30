package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.service.GenerationJobContext;

class AIAgentRetryProgressTest {
    @Test
    void internalRetryReportsTheConnectionFailureToProgress() {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(1L)).thenReturn(new TestCaseContextDto(
                new ProjectContextDto(1L, "demo", "ANALYZED"), null,
                List.of(), List.of(), List.of(), List.of()));
        var prompts = mock(PromptManager.class);
        when(prompts.render(anyString(), anyMap())).thenReturn("prompt");
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any()))
                .thenThrow(new LlmResponseException("EOF reached while reading", true))
                .thenReturn("{\"cases\":[]}");
        var parser = mock(GenerationResponseParser.class);
        var response = new TestCaseResponseDto(List.of());
        when(parser.parse(anyString(), eq(TestCaseResponseDto.class))).thenReturn(response);
        var agent = new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));
        var logs = new java.util.ArrayList<String>();
        GenerationJobContext.bind(42L, logs::add);
        try {
            assertThat(agent.generateTestCases(1L)).isEqualTo(response);
            assertThat(logs).anySatisfy(message -> assertThat(message)
                    .contains("EOF reached while reading", "thử lại", "2/2"));
            verify(client, times(2)).complete(anyString(), any());
        } finally {
            GenerationJobContext.clear();
        }
    }
}
