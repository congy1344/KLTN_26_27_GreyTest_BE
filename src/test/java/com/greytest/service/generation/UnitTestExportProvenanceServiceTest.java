package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.UnitTest;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationSourceFingerprint;

class UnitTestExportProvenanceServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AIAgentService agent = mock(AIAgentService.class);
    private final GenerationFingerprintService fingerprints = mock(GenerationFingerprintService.class);
    private final UnitTestExportProvenanceService service = new UnitTestExportProvenanceService(jdbc, agent, fingerprints, new ObjectMapper());

    @Test void alignedSourceHashCannotExportAStoredInvalidFixture(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Service.java"), "class Service {} ");
        UnitTest unit = unit(metadata(GenerationSourceFingerprint.hash(Files.readString(root.resolve("Service.java")))) + "class ServiceTest { void testValue() {} }");
        String original = unit.getSourceCode();
        org.mockito.Mockito.doThrow(new com.greytest.service.agent.LlmResponseException("Missing collection stub"))
                .when(agent).validateExistingUnitTest(1L, unit, root);
        assertThatThrownBy(() -> service.prepare(1L, List.of(unit), root)).hasMessageContaining("Missing collection stub");
        assertThat(unit.getSourceCode()).isEqualTo(original);
        verifyNoInteractions(jdbc, fingerprints);
    }

    @Test
    void alignedMetadataReturnsOriginalEntityWithoutQueryAndRestoresCallerScope(@TempDir Path root) throws Exception {
        Path source = root.resolve("Service.java"); Files.writeString(source, "class Service { int value() { return 1; } }");
        UnitTest unit = unit(metadata(GenerationSourceFingerprint.hash(Files.readString(source))) + "class ServiceTest { void testValue() {} }");
        try (var scope = GenerationJobContext.openScope(42L)) {
            GenerationJobContext.setSourceUpdateId(99L);
            assertThat(service.prepare(1L, List.of(unit), root)).containsExactly(unit);
            assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
            assertThat(GenerationJobContext.sourceUpdateId()).isEqualTo(99L);
        }
        org.mockito.Mockito.verify(agent).validateExistingUnitTest(1L, unit, root);
        verifyNoInteractions(jdbc, fingerprints);
    }

    @Test
    void crlfLineEndingsNormalizeWithoutAstMismatch(@TempDir Path root) throws Exception {
        Path source = root.resolve("Service.java");
        Files.writeString(source, "class Service {\r\n    int value() {\r\n        return 1;\r\n    }\r\n}\r\n");
        UnitTest unit = unit(metadata(GenerationSourceFingerprint.hash(Files.readString(source)))
                + "class ServiceTest {\r\n    void testValue() {\r\n    }\r\n}\r\n");
        assertThat(service.prepare(1L, List.of(unit), root)).containsExactly(unit);
    }

    @Test
    void missingAppliedCheckpointCannotReplaceHistoricalMetadata(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Service.java"), "class Service { int value() { return 2; } }");
        UnitTest unit = unit(metadata("1".repeat(64)) + "class ServiceTest { void testValue() {} }");
        String original = unit.getSourceCode();
        assertThatThrownBy(() -> service.prepare(1L, List.of(unit), root)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no valid applied checkpoint");
        assertThat(unit.getSourceCode()).isEqualTo(original);
        org.mockito.Mockito.verify(agent).validateExistingUnitTest(1L, unit, root);
        verifyNoInteractions(fingerprints);
    }

    @Test
    void appliedCheckpointWithInputDynamicDispatchWarningsCanStillExport(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Service.java"), "class Service { int value() { return 2; } }");
        String testCode = metadata("1".repeat(64)) + "class ServiceTest { void testValue() {} }";
        UnitTest unit = unit(testCode);

        JdbcTemplate testJdbc = org.mockito.Mockito.mock(JdbcTemplate.class, invocation -> {
            if ("query".equals(invocation.getMethod().getName())) {
                org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
                var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                org.mockito.Mockito.when(rs.getLong(1)).thenReturn(405L);
                org.mockito.Mockito.when(rs.getString(2)).thenReturn("matching-input-fp");
                org.mockito.Mockito.when(rs.getString(3)).thenReturn("""
                        {
                            "outputReusable": true,
                            "response": {"unitTests":[{"caseId":3,"testClassName":"ServiceTest","testMethodName":"testValue","packageName":null,"generationType":null,"sourceCode":"class ServiceTest { void testValue() {} }"}]},
                            "outputFingerprint": "matching-output-fp"
                        }
                        """);
                return List.of(mapper.mapRow(rs, 1));
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        UnitTestExportProvenanceService localService = new UnitTestExportProvenanceService(testJdbc, agent, fingerprints, new ObjectMapper());

        var context = org.mockito.Mockito.mock(com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto.class);
        org.mockito.Mockito.when(context.classes()).thenReturn(List.of());
        org.mockito.Mockito.when(agent.snapshotContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(context);
        org.mockito.Mockito.when(agent.parseSnapshotOutput(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto(
                        List.of(new com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto(3L, "ServiceTest", "testValue", null, null, "class ServiceTest { void testValue() {} }"))));

        // currentInput.reusable() = false (mô phỏng cảnh báo dynamic dispatch trong input của Spring Boot)
        org.mockito.Mockito.when(fingerprints.fingerprint(org.mockito.ArgumentMatchers.eq("unit-test"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GenerationFingerprintService.Fingerprint("matching-input-fp", "{}", false));
        // currentOutput.reusable() = true
        org.mockito.Mockito.when(fingerprints.outputFingerprint(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GenerationFingerprintService.Fingerprint("matching-output-fp", "{}", true));

        List<UnitTest> prepared = localService.prepare(1L, List.of(unit), root);
        assertThat(prepared).hasSize(1);
        assertThat(prepared.get(0).getSourceCode()).contains("// GREYTEST_REVALIDATED_V1 applied_update=405");
    }

    private UnitTest unit(String source) {
        UnitTest unit = new UnitTest(); unit.setId(2L); unit.setTestCaseId(3L); unit.setTestClassName("ServiceTest");
        unit.setTestMethodName("testValue"); unit.setSourceCode(source); return unit;
    }
    private String metadata(String hash) {
        return GenerationSourceFingerprint.PREFIX + hash + " "
                + Base64.getEncoder().encodeToString("Service.java".getBytes(StandardCharsets.UTF_8)) + "\n";
    }
}
