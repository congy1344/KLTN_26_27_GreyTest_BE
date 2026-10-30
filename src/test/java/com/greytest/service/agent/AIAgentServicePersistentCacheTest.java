package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.service.generation.GenerationFingerprintService;
import com.greytest.service.generation.PersistentLlmResponseStore;

import jakarta.validation.Validation;

class AIAgentServicePersistentCacheTest {
    @ParameterizedTest
    @ValueSource(strings = {"plain", "fenced", "prose", "numeric-suffix"})
    void storesValidatedJsonAndReusesItWithoutAnotherLlmCall(String format, @TempDir Path root) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var context = new TestCaseContextDto(new ProjectContextDto(1L, "demo", "PLAN_APPROVED"), null,
                List.of(), List.of(), List.of(new TestPlanContextItemDto(2L, 3L, List.of(3L),
                "TP-001", "plan", "plan", "HAPPY_PATH", "APPROVED", false)), List.of());
        String json = """
                {"cases":[{"plan_id":2,"test_type":"HAPPY_PATH","description":"Giữ dữ liệu",
                "preconditions":"Sẵn sàng","test_data":{"input":{"literal":"1001L ```json"}},
                "expected_result":"Giữ nguyên chuỗi 1001L","priority":"HIGH","trace_source":"TP-001"}]}
                """;
        String response = switch (format) {
            case "fenced" -> "```json\n" + json + "\n```";
            case "prose" -> "Kết quả:\n" + json;
            case "numeric-suffix" -> "```json\n" + json.replace("\"plan_id\":2", "\"plan_id\":2L") + "\n```";
            default -> json;
        };
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildSnapshotTestCaseContext(1L, java.util.Set.of(2L), root)).thenReturn(context);
        LlmClient client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn(response);
        var fingerprints = mock(GenerationFingerprintService.class);
        when(fingerprints.fingerprint(eq("test-case"), eq(context), eq(root)))
                .thenReturn(new GenerationFingerprintService.Fingerprint("input", "{}", true));
        when(fingerprints.outputFingerprint(any(), eq(root), eq(context)))
                .thenReturn(new GenerationFingerprintService.Fingerprint("output", "{}", true));
        var cached = new AtomicReference<PersistentLlmResponseStore.CachedEntry>();
        var cache = mock(PersistentLlmResponseStore.class);
        when(cache.getEntry(1L, "test-case", "input")).thenAnswer(call -> Optional.ofNullable(cached.get()));
        // Giữ validation thật của cache; chỉ thay hạ tầng DB bằng mock.
        var strictStore = new PersistentLlmResponseStore(mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class), mapper);
        doAnswer(call -> {
            String stored = call.getArgument(3);
            String provenance = call.getArgument(4);
            strictStore.putValidated(1L, "test-case", "input", stored, provenance);
            cached.set(new PersistentLlmResponseStore.CachedEntry(stored, provenance));
            return null;
        }).when(cache).putValidated(eq(1L), eq("test-case"), eq("input"), anyString(), anyString());
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var agent = new AIAgentService(contexts, new PromptManager(mapper), client,
                    new GenerationResponseParser(mapper, validatorFactory.getValidator()),
                    new AiContextLogService(mapper, root.resolve("logs").toString()));
            ReflectionTestUtils.setField(agent, "persistentCache", cache);
            ReflectionTestUtils.setField(agent, "fingerprints", fingerprints);
            ReflectionTestUtils.setField(agent, "generationJson", mapper);

            var first = agent.generateSnapshotTestCases(1L, java.util.Set.of(2L), root);
            var second = agent.generateSnapshotTestCases(1L, java.util.Set.of(2L), root);

            assertThat(second).isEqualTo(first);
            assertThat(mapper.readTree(cached.get().response())).isEqualTo(mapper.readTree(mapper.writeValueAsString(first)));
            assertThat(first.cases().get(0).testData()).containsEntry("input", java.util.Map.of("literal", "1001L ```json"));
            verify(client, times(1)).complete(anyString(), any());
            verify(cache, never()).evict(anyLong(), anyString(), anyString());
        }
    }
}