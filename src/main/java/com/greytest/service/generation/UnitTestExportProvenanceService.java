package com.greytest.service.generation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.entity.UnitTest;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationSourceFingerprint;

/**
 * Xác minh provenance khi áp dụng source và khi xuất Unit Test.
 */
@Service
public class UnitTestExportProvenanceService {
    private static final String ORIGINAL_PREFIX = "// GREYTEST_ORIGINAL_SOURCE_V1 ";
    private final JdbcTemplate jdbc;
    private final AIAgentService agent;
    private final GenerationFingerprintService fingerprints;
    private final ObjectMapper json;

    public UnitTestExportProvenanceService(JdbcTemplate jdbc, AIAgentService agent,
            GenerationFingerprintService fingerprints, ObjectMapper json) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.fingerprints = fingerprints;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<UnitTest> prepare(Long projectId, List<UnitTest> tests, Path sourceRoot) {
        return inExportScope(() -> tests.stream().map(test -> {
                                if (sourceRoot != null && test.getSourceCode() != null && test.getSourceCode().contains(GenerationSourceFingerprint.PREFIX)) { agent.validateExistingUnitTest(projectId, test, sourceRoot); }
                                return prepareOne(projectId, test, sourceRoot);
                            }).toList());
    }

    /** Test đã được caller kiểm tra với cùng sourceRoot thì không kiểm tra lại. */
    @Transactional(readOnly = true)
    public List<UnitTest> prepareValidated(Long projectId, List<UnitTest> tests, Path sourceRoot) {
        return inExportScope(() -> tests.stream().map(test -> prepareOne(projectId, test, sourceRoot)).toList());
    }

    /** Trong một request, source chỉ quét một lần cho mọi test thay vì quét lại từng test. */
    public <T> T inExportScope(java.util.function.Supplier<T> action) {
        Long actor = GenerationJobContext.actorUserId();
        try (var previous = GenerationJobContext.openScope(actor)) {
            // Export phải đọc identity active mới, không mượn overlay/analysis của draft trong caller.
            GenerationJobContext.bind(actor, ignored -> { });
            return GenerationJobContext.withValue("greytest.export-read-only", Boolean.TRUE,
                    () -> com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(action));
        }
    }

    private UnitTest prepareOne(Long projectId, UnitTest test, Path sourceRoot) {
        String source = test.getSourceCode();
        if (source == null || !source.contains(GenerationSourceFingerprint.PREFIX)) return test;
        String historicalMetadata = GenerationSourceFingerprint.aggregate(List.of(source));
        if (!historicalMetadata.contains(GenerationSourceFingerprint.PREFIX)) return test;
        if (sourceRoot == null) throw new IllegalArgumentException("Generation source root is missing for Unit Test export");
        try {
            GenerationSourceFingerprint.alignToSourceRoot(List.of(source), sourceRoot);
            return test;
        } catch (IllegalArgumentException changed) {
            if (historicalMetadata.contains(GenerationSourceFingerprint.UNVERIFIED)) throw changed;
        }
        if (test.getTestCaseId() == null) throw new IllegalArgumentException("Unit Test has no case identity for provenance verification");
        var checkpoints = jdbc.query("""
                SELECT g.source_update_id,g.fingerprint,g.output_json
                FROM generation_checkpoint g JOIN source_update u ON u.id=g.source_update_id
                WHERE g.project_id=? AND u.project_id=? AND u.status='APPLIED'
                    AND g.stage='UNIT_TEST' AND g.target_key=? AND g.state='SUCCEEDED'
                ORDER BY u.updated_at DESC,g.id DESC
                """, (rs, row) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getString(3)),
                projectId, projectId, test.getTestCaseId().toString());
        RuntimeException lastFailure = null;
        for (var checkpoint : checkpoints) {
            try {
                var envelope = json.readTree(checkpoint.output());
                if (!envelope.path("outputReusable").asBoolean(false)) continue;
                var recorded = (UnitTestResponseDto) agent.parseSnapshotOutput(GenerationProgressStage.UNIT_TEST,
                        envelope.path("response").toString());
                if (recorded.unitTests().size() != 1) continue;
                var original = recorded.unitTests().get(0);
                if (!sameIdentity(test, original) || !ast(source).equals(ast(original.sourceCode()))) continue;
                var context = (UnitTestContextDto) agent.snapshotContext(GenerationProgressStage.UNIT_TEST,
                        projectId, Set.of(test.getTestCaseId()), sourceRoot);
                var currentInput = fingerprints.fingerprint("unit-test", context, sourceRoot);
                if (!currentInput.value().equals(checkpoint.fingerprint())) continue;
                var output = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(test.getTestCaseId(),
                        test.getTestClassName(), test.getTestMethodName(), test.getPackageName(),
                        test.getGenerationType(), source)));
                var currentOutput = fingerprints.outputFingerprint(output, sourceRoot, context);
                if (!currentOutput.reusable() || !currentOutput.value().equals(envelope.path("outputFingerprint").asText())) continue;
                agent.validateSnapshotOutput(GenerationProgressStage.UNIT_TEST, projectId, context, output, sourceRoot);
                String prepared = stamp(test, sourceRoot, context, historicalMetadata, checkpoint.updateId(),
                        currentInput.value(), currentOutput.value());
                UnitTest copy = new UnitTest();
                BeanUtils.copyProperties(test, copy);
                copy.setSourceCode(prepared);
                return copy;
            } catch (RuntimeException failure) {
                lastFailure = failure;
            } catch (java.io.IOException invalid) {
                lastFailure = new IllegalArgumentException("Invalid persisted Unit Test provenance", invalid);
            }
        }
        // Test sinh từ source cũ, không có checkpoint của lần cập nhật source đã áp dụng nào khớp với source hiện tại
        String caseLabel = "#" + test.getTestCaseId();
        try {
            List<String> caseCodes = jdbc.queryForList("SELECT case_code FROM test_case WHERE id=?", String.class, test.getTestCaseId());
            if (caseCodes != null && !caseCodes.isEmpty()) caseLabel = caseCodes.get(0);
        } catch (RuntimeException lookupFailed) { /* chỉ để hiển thị, giữ lỗi provenance gốc */ }
        throw new IllegalArgumentException("Unit Test " + test.getTestClassName() + "#" + test.getTestMethodName()
                + " (Test Case " + caseLabel + ") được sinh từ phiên bản source cũ và không xác minh lại được với source hiện tại."
                + " Hãy sinh lại Unit Test cho Test Case này.", lastFailure);
    }

    /** Chỉ cập nhật entity sau khi đối chiếu nguồn cũ, dependency hai revision và validator tĩnh. */
    void revalidateRetained(Long projectId, long updateId, UnitTest test, Path base, Path active, Path activeWorkspace) {
        String source = test.getSourceCode();
        if (source == null || !source.contains(GenerationSourceFingerprint.PREFIX)) return;
        String history = GenerationSourceFingerprint.aggregate(List.of(source));
        if (history.contains(GenerationSourceFingerprint.UNVERIFIED)) return;
        try {
            GenerationSourceFingerprint.alignToSourceRoot(List.of(source), activeWorkspace);
            return;
        } catch (IllegalArgumentException changed) {
            // Không nối lịch sử qua một revision trung gian chưa từng được xác minh.
            GenerationSourceFingerprint.alignToSourceRoot(List.of(source), base);
        }
        var ids = Set.of(test.getTestCaseId());
        var before = (UnitTestContextDto) agent.snapshotContext(GenerationProgressStage.UNIT_TEST, projectId, ids, base);
        var after = (UnitTestContextDto) agent.snapshotContext(GenerationProgressStage.UNIT_TEST, projectId, ids, active);
        var oldInput = fingerprints.fingerprint("unit-test", before, base);
        var input = fingerprints.fingerprint("unit-test", after, active);
        if (!input.value().equals(oldInput.value()) || !comparableInput(input)) return;

        var output = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(test.getTestCaseId(),
                test.getTestClassName(), test.getTestMethodName(), test.getPackageName(), test.getGenerationType(), source)));
        var oldOutput = fingerprints.outputFingerprint(output, base, before);
        var newOutput = fingerprints.outputFingerprint(output, active, after);
        if (!newOutput.reusable() || !oldOutput.reusable() || !newOutput.value().equals(oldOutput.value())) return;
        if (after.classes().isEmpty()) throw new IllegalArgumentException("Missing target classes");
        // Context của validator dùng đường dẫn từ gốc lưu trữ, kể cả lớp thư mục bọc ngoài ZIP.
        agent.validateExistingUnitTest(projectId, test, activeWorkspace);
        String prepared = stamp(test, active, after, history, updateId, input.value(), newOutput.value());
        if (!active.equals(activeWorkspace)) {
            prepared = GenerationSourceFingerprint.alignToSourceRoot(List.of(prepared), activeWorkspace).get(0);
        }
        test.setSourceCode(prepared);
    }

    /** Resolver ghi digest từng implementation ứng viên; chỉ dung nạp cảnh báo dispatch khi toàn bộ digest khớp. */
    private boolean comparableInput(GenerationFingerprintService.Fingerprint input) {
        if (input.reusable()) return true;
        try {
            var problems = json.readTree(input.provenance()).path("problems");
            if (!problems.isArray() || problems.isEmpty()) return false;
            for (var problem : problems) {
                if (!problem.asText().startsWith("unresolved dynamic dispatch: ")) return false;
            }
            return true;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    /** Ghi dấu cũ ngay trên method để export nhiều case vẫn giữ đủ lịch sử. */
    private String stamp(UnitTest test, Path root, UnitTestContextDto context, String history,
            long updateId, String input, String output) {
        String metadata = GenerationSourceFingerprint.capture(root, context.classes());
        String audit = history.replace(GenerationSourceFingerprint.PREFIX, ORIGINAL_PREFIX)
                + "// GREYTEST_REVALIDATED_V1 applied_update=" + updateId + " input=" + input + " output=" + output + "\n";
        String source = test.getSourceCode();
        String prepared = GenerationSourceFingerprint.attach(withMethodAudit(source, test, audit), metadata);
        if (!ast(prepared).equals(ast(source))) throw new IllegalArgumentException("Export revalidation changed test code");
        GenerationSourceFingerprint.alignToSourceRoot(List.of(prepared), root);
        return prepared;
    }

    private boolean sameIdentity(UnitTest test, GeneratedUnitTestDto recorded) {
        return Objects.equals(test.getTestCaseId(), recorded.caseId())
                && Objects.equals(test.getTestClassName(), recorded.testClassName())
                && Objects.equals(test.getTestMethodName(), recorded.testMethodName())
                && Objects.equals(test.getPackageName(), recorded.packageName())
                && Objects.equals(test.getGenerationType(), recorded.generationType());
    }

    private String withMethodAudit(String source, UnitTest test, String audit) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        var owner = parse(normalized).getTypes().stream().filter(type -> type.getNameAsString().equals(test.getTestClassName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing export test class"));
        var methods = owner.getMethodsByName(test.getTestMethodName());
        if (methods.size() != 1) throw new IllegalArgumentException("Ambiguous export test method identity");
        var begin = methods.get(0).getBegin().orElseThrow();
        int offset = 0;
        for (int line = 1; line < begin.line; line++) offset = normalized.indexOf('\n', offset) + 1;
        offset += begin.column - 1;
        // Gắn audit vào method để merge nhiều case cùng class vẫn giữ đủ lịch sử của từng case.
        return normalized.substring(0, offset) + "/*\n" + audit + "*/\n" + normalized.substring(offset);
    }

    private String ast(String source) {
        CompilationUnit unit = parse(source);
        new ArrayList<>(unit.getAllContainedComments()).forEach(comment -> comment.remove());
        unit.removeComment();
        return unit.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    private CompilationUnit parse(String source) {
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        return parsed.getResult().filter(ignored -> parsed.isSuccessful())
                .orElseThrow(() -> new IllegalArgumentException("Cannot parse Unit Test provenance source"));
    }

    private record Checkpoint(long updateId, String fingerprint, String output) { }
}
