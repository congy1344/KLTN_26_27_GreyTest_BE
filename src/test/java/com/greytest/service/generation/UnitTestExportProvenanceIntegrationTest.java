package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.UnitTestService;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationSourceFingerprint;
import com.greytest.service.agent.LlmClient;
import com.greytest.service.analysis.AnalysisService;

/** Xác minh export thật giữ lịch sử source, không ghi lại Unit Test và không gọi LLM. */
@SpringBootTest(properties = { "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes",
        "greytest.ai-context-log-enabled=false", "logging.level.root=WARN" })
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL",
        matches = "jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024")
class UnitTestExportProvenanceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectRepository projects;
    @Autowired UnitTestRepository units;
    @Autowired AnalysisService analysis;
    @Autowired AIAgentService agent;
    @Autowired GenerationFingerprintService fingerprints;
    @Autowired UnitTestService unitTests;
    @Autowired ObjectMapper json;
    @MockBean LlmClient provider;

    @Test
    void unrelatedMethodUpdateExportsMergedArchiveWithExplicitRevalidationAndOriginalHistory() throws Exception {
        Fixture fixture = fixture();
        try {
            changeUnrelatedMethod(fixture);
            String storedBefore = units.findById(fixture.unitB()).orElseThrow().getSourceCode();
            jdbc.update("update source_revision set analysis_snapshot=null where id=?", fixture.revision());
            String analysisBefore = jdbc.queryForObject("select analysis_snapshot::text from source_revision where id=?", String.class, fixture.revision());
            Map<String, String> archive = unzip(unitTests.zipFiles(fixture.project()));
            String merged = archive.get("src/test/java/benchmark/ExportServiceTest.java");
            assertThat(merged).contains("testA", "testB", "GREYTEST_REVALIDATED_V1", "GREYTEST_ORIGINAL_SOURCE_V1", fixture.oldHash());
            assertThat(GenerationSourceFingerprint.aggregate(List.of(merged))).contains(GenerationSourceFingerprint.hash(Files.readString(fixture.source())));
            assertThat(units.findById(fixture.unitB()).orElseThrow().getSourceCode()).isEqualTo(storedBefore);
            assertThat(jdbc.queryForObject("select analysis_snapshot::text from source_revision where id=?", String.class, fixture.revision())).isEqualTo(analysisBefore);
            verifyNoInteractions(provider);
        } finally { cleanup(fixture); }
    }

    @Test
    void changedTargetOrDependencyFailsClosedAndKeepsHistoricalUnitSource() throws Exception {
        for (String change : List.of("target", "dependency")) {
            Fixture fixture = fixture();
            try {
                changeUnrelatedMethod(fixture);
                String old = units.findById(fixture.unitB()).orElseThrow().getSourceCode();
                String source = Files.readString(fixture.source());
                Files.writeString(fixture.source(), change.equals("target")
                        ? source.replace("return helper();", "return helper() + 1;")
                        : source.replace("return 42;", "return 43;"));
                assertThatThrownBy(() -> unitTests.zipFiles(fixture.project())).isInstanceOf(RuntimeException.class);
                assertThat(units.findById(fixture.unitB()).orElseThrow().getSourceCode()).isEqualTo(old);
                verifyNoInteractions(provider);
            } finally { cleanup(fixture); }
        }
    }

    @Test
    void modifiedTestBodyOrUnappliedCheckpointCannotRevalidateOldMetadata() throws Exception {
        for (String change : List.of("body", "unapplied", "corrupt-output-proof")) {
            Fixture fixture = fixture();
            try {
                changeUnrelatedMethod(fixture);
                if (change.equals("body")) jdbc.update("update unit_test set source_code=replace(source_code,'assertEquals(42','assertEquals(43') where id=?", fixture.unitB());
                if (change.equals("unapplied")) jdbc.update("update source_update set status='READY_TO_APPLY' where id=?", fixture.update());
                if (change.equals("corrupt-output-proof")) jdbc.update("update generation_checkpoint set output_json=replace(output_json,?,?) where source_update_id=? and stage='UNIT_TEST'", fixture.outputFingerprint(), "0".repeat(64), fixture.update());
                String before = units.findById(fixture.unitB()).orElseThrow().getSourceCode();
                assertThatThrownBy(() -> unitTests.zipFiles(fixture.project())).isInstanceOf(RuntimeException.class);
                assertThat(units.findById(fixture.unitB()).orElseThrow().getSourceCode()).isEqualTo(before);
                verifyNoInteractions(provider);
            } finally { cleanup(fixture); }
        }
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "export-provenance-");
        Path source = root.resolve("src/main/java/benchmark/ExportService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package benchmark; import org.springframework.stereotype.Service; @Service public class ExportService { public int a() { return 40; } public int b() { return helper(); } private int helper() { return 42; } }");
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>benchmark</groupId><artifactId>fixture</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.0</version><scope>test</scope></dependency></dependencies></project>");
        Project project = new Project(); project.setName("export-provenance-" + UUID.randomUUID());
        project.setSourceType(SourceType.ZIP); project.setStoragePath(root.toString()); project.setStatus(ProjectStatus.UPLOADED);
        project = projects.save(project);
        long projectId = project.getId();
        var analyzed = analysis.analyze(projectId);
        Map<String, Long> caseIds = new LinkedHashMap<>();
        for (var method : analyzed.classes().get(0).methods()) {
            if (!Set.of("a", "b").contains(method.methodName())) continue;
            int expected = method.methodName().equals("a") ? 40 : 42;
            long rule = id("insert into business_rule(project_id,method_id,rule_code,description,source,status,is_modified) values(?,?,?,?,'AI_GENERATED','APPROVED',false) returning id", projectId, method.id(), "BR-" + method.methodName(), "Returns " + expected);
            long plan = id("insert into test_plan(project_id,business_rule_id,plan_code,title,description,test_type,status,is_modified) values(?,?,?,?,?,'HAPPY_PATH','APPROVED',false) returning id", projectId, rule, "TP-" + method.methodName(), "Verify " + method.methodName(), "Returns " + expected);
            jdbc.update("insert into test_plan_covered_rule(test_plan_id,business_rule_id) values(?,?)", plan, rule);
            long testCase = id("insert into test_case(test_plan_id,case_code,test_type,description,preconditions,test_data,expected_result,priority,trace_source,status,is_modified) values(?,?,'HAPPY_PATH',?,'Create service',cast(? as jsonb),?,'HIGH',?,'APPROVED',false) returning id", plan, "TC-" + method.methodName(), "Call " + method.methodName(), "{\"input\":{},\"mocks\":{}}", "Returns " + expected, "BR-" + method.methodName() + " -> TP-" + method.methodName());
            caseIds.put(method.methodName(), testCase);
        }
        long revision = id("insert into source_revision(project_id,source_type,storage_path) values(?,'ZIP',?) returning id", projectId, root.toString());
        long update = id("insert into source_update(project_id,base_revision_id,candidate_revision_id,status) values(?,?,?,'APPLIED') returning id", projectId, revision, revision);
        jdbc.update("update project set active_source_revision_id=? where id=?", revision, projectId);
        long unitB = 0; String outputFingerprint = "";
        for (var entry : caseIds.entrySet()) {
            UnitTestContextDto context = (UnitTestContextDto) agent.snapshotContext(GenerationProgressStage.UNIT_TEST, projectId, Set.of(entry.getValue()), root);
            String sourceCode = GenerationSourceFingerprint.attach(testSource(entry.getKey(), entry.getKey().equals("a") ? 40 : 42), GenerationSourceFingerprint.capture(root, context.classes()));
            var test = new GeneratedUnitTestDto(entry.getValue(), "ExportServiceTest", "test" + entry.getKey().toUpperCase(), "benchmark", "NEW_TEST", sourceCode);
            var response = new UnitTestResponseDto(List.of(test));
            agent.validateSnapshotOutput(GenerationProgressStage.UNIT_TEST, projectId, context, response, root);
            var input = fingerprints.fingerprint("unit-test", context, root);
            var output = fingerprints.outputFingerprint(response, root);
            assertThat(input.reusable()).as(input.provenance()).isTrue(); assertThat(output.reusable()).as(output.provenance()).isTrue();
            long unit = id("insert into unit_test(test_case_id,test_class_name,test_method_name,package_name,generation_type,source_code,file_path) values(?,'ExportServiceTest',?,'benchmark','NEW_TEST',?,'src/test/java/benchmark/ExportServiceTest.java') returning id", entry.getValue(), test.testMethodName(), sourceCode);
            String envelope = json.writeValueAsString(Map.of("response", response, "outputFingerprint", output.value(), "outputReusable", true));
            jdbc.update("insert into generation_checkpoint(project_id,source_update_id,stage,target_key,state,fingerprint,provenance,output_json,output_digest) values(?,?,'UNIT_TEST',?,'SUCCEEDED',?,cast(? as jsonb),?,?)", projectId, update, entry.getValue().toString(), input.value(), input.provenance(), envelope, "fixture-output-digest");
            if (entry.getKey().equals("b")) { unitB = unit; outputFingerprint = output.value(); }
        }
        return new Fixture(projectId, update, revision, unitB, caseIds.get("a"), root, source, GenerationSourceFingerprint.hash(Files.readString(source)), outputFingerprint);
    }

    private void changeUnrelatedMethod(Fixture fixture) throws Exception {
        Files.writeString(fixture.source(), Files.readString(fixture.source()).replace("return 40;", "return 41;"));
        jdbc.update("update test_case set expected_result='Returns 41' where id=?", fixture.caseA());
        UnitTestContextDto context = (UnitTestContextDto) agent.snapshotContext(GenerationProgressStage.UNIT_TEST, fixture.project(), Set.of(fixture.caseA()), fixture.root());
        String source = GenerationSourceFingerprint.attach(testSource("a", 41), GenerationSourceFingerprint.capture(fixture.root(), context.classes()));
        jdbc.update("update unit_test set source_code=? where test_case_id=?", source, fixture.caseA());
    }

    private String testSource(String method, int expected) {
        return "package benchmark; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.assertEquals; class ExportServiceTest { @Test void test" + method.toUpperCase() + "() { ExportService service = new ExportService(); assertEquals(" + expected + ", service." + method + "()); } }";
    }
    private Long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return result;
    }
    private void cleanup(Fixture fixture) throws Exception {
        jdbc.update("delete from project where id=?", fixture.project());
        try (var paths = Files.walk(fixture.root())) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private record Fixture(long project, long update, long revision, long unitB, long caseA, Path root, Path source, String oldHash, String outputFingerprint) { }
}
