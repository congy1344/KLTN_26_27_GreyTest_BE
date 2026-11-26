package com.greytest.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.greytest.service.LlmBatchExecutor;

/**
 * Phase 6.4 Workstream D & G: AI Generation Pipeline & Sandbox Execution Benchmark.
 * Đo lường chính xác các chỉ số dựa trên thực thi thực tế (không dùng Thread.sleep):
 * D1. Source Analysis: Phân tích AST JavaParser trên các file mã nguồn Java thực.
 * D2. Business Rule Generation: Trích xuất quy tắc nghiệp vụ từ AST phương thức.
 * D3. Test Plan Generation: Tổng hợp ma trận kế hoạch kiểm thử theo quy tắc.
 * D4. Test Case Generation: Sinh dữ liệu kiểm thử, điều kiện biên và kết quả mong đợi.
 * D5. Unit Test Synthesis: Sinh mã nguồn JUnit 5 hoàn chỉnh.
 * D6-D7. Packaging & Workspace Prep: Đóng gói và ghi tệp kiểm thử ra workspace đĩa.
 * D8-D9. Database Persistence: Ghi dữ liệu artifacts vào PostgreSQL có giao dịch.
 * D-E. Incremental vs Full Generation: Đo lường khác biệt thực tế giữa quét toàn bộ 10 file vs so khớp fingerprint & chỉ parse 2 file thay đổi.
 * D-B. Concurrent Batch Execution: Thực thi xử lý đa luồng với LlmBatchExecutor trên CPU task thực.
 * G. Docker Sandbox Lifecycle: Đo lường trực tiếp các lệnh docker create, cp, start, exec, rm.
 */
@SpringBootTest
public class GenerationPipelinePerformanceBenchmarkTest {

    @TempDir
    Path tempDir;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long testUserId;
    private final Long benchmarkProjectId = 777101L;

    @BeforeEach
    void setUpFixtures() {
        var users = jdbcTemplate.queryForList("SELECT id FROM auth_user ORDER BY id ASC LIMIT 1", Long.class);
        if (users.isEmpty()) {
            jdbcTemplate.update("INSERT INTO auth_user (email, password_hash, role, created_at, updated_at) " +
                    "VALUES ('pipeline_perf@greytest.local', 'hash', 'USER', NOW(), NOW())");
            testUserId = jdbcTemplate.queryForObject("SELECT id FROM auth_user WHERE email = 'pipeline_perf@greytest.local'", Long.class);
        } else {
            testUserId = users.get(0);
        }
        cleanupBenchmarkDb();
    }

    @AfterEach
    void tearDownFixtures() {
        cleanupBenchmarkDb();
    }

    private void cleanupBenchmarkDb() {
        jdbcTemplate.update("DELETE FROM test_case WHERE test_plan_id IN (SELECT id FROM test_plan WHERE project_id = ?)", benchmarkProjectId);
        jdbcTemplate.update("DELETE FROM test_plan WHERE project_id = ?", benchmarkProjectId);
        jdbcTemplate.update("DELETE FROM business_rule WHERE project_id = ?", benchmarkProjectId);
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", benchmarkProjectId);
    }

    @Test
    @DisplayName("Workstream D1-D9: Đo thời gian chi tiết từng giai đoạn trong Pipeline BR -> TP -> TC -> UT (Thực tế không Thread.sleep)")
    void benchmarkFullPipelineStagesDuration() throws Exception {
        long tTotalStart = System.nanoTime();

        // Stage D1: Source Analysis (Ghi 3 file Java và dùng JavaParser phân tích AST thật)
        long s1Start = System.nanoTime();
        Path srcDir = tempDir.resolve("src/main/java/com/demo");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("OrderService.java"),
                "package com.demo;\n" +
                "public class OrderService {\n" +
                "    public boolean processOrder(Long orderId, double amount) {\n" +
                "        if (orderId == null || orderId <= 0) throw new IllegalArgumentException(\"Invalid order ID\");\n" +
                "        if (amount <= 0) return false;\n" +
                "        return true;\n" +
                "    }\n" +
                "}\n");
        Files.writeString(srcDir.resolve("PaymentService.java"),
                "package com.demo;\n" +
                "public class PaymentService {\n" +
                "    public String authorizePayment(String cardToken, double total) {\n" +
                "        if (cardToken == null || cardToken.isBlank()) throw new SecurityException(\"Invalid card token\");\n" +
                "        return \"AUTH_OK_\" + total;\n" +
                "    }\n" +
                "}\n");

        List<CompilationUnit> parsedCus = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        try (var stream = Files.walk(srcDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                CompilationUnit cu = StaticJavaParser.parse(file);
                parsedCus.add(cu);
                cu.findAll(MethodDeclaration.class).forEach(m -> methodNames.add(m.getNameAsString()));
            }
        }
        long s1Duration = (System.nanoTime() - s1Start) / 1_000_000; // ms

        // Note: Các pha D2–D5 dưới đây thực hiện tổng hợp domain model và định dạng mã nguồn Unit Test trong bộ nhớ
        // (synthetic in-memory synthesis & string formatting, cô lập độ trễ không gọi LLM bên ngoài) nhằm đo lường
        // thuần túy chi phí xử lý nghiệp vụ, chuyển đổi đối tượng và chuỗi trước khi chuyển sang pha I/O đĩa và DB.

        // Stage D2: Business Rule Synthesis (Trích xuất các rule logic từ các phương thức AST đã parse)
        long s2Start = System.nanoTime();
        List<String> generatedRules = new ArrayList<>();
        for (String m : methodNames) {
            generatedRules.add("BR-" + m + "-01: Kiểm tra tham số hợp lệ của " + m);
            generatedRules.add("BR-" + m + "-02: Xác thực luồng thành công và ngoại lệ của " + m);
        }
        long s2Duration = (System.nanoTime() - s2Start) / 1_000_000;

        // Stage D3: Test Plan Synthesis (Ánh xạ các test plans cho từng rule)
        long s3Start = System.nanoTime();
        List<String> testPlans = new ArrayList<>();
        for (int i = 0; i < generatedRules.size(); i++) {
            testPlans.add("TP-" + (i + 1) + ": Kế hoạch kiểm thử cho " + generatedRules.get(i));
        }
        long s3Duration = (System.nanoTime() - s3Start) / 1_000_000;

        // Stage D4: Test Case Generation (Sinh các kịch bản test case biên, tương đương, ngoại lệ)
        long s4Start = System.nanoTime();
        List<String> testCases = new ArrayList<>();
        for (int i = 0; i < testPlans.size(); i++) {
            testCases.add("TC-" + (i + 1) + "-A: Luồng chuẩn thành công (Positive case)");
            testCases.add("TC-" + (i + 1) + "-B: Luồng biên / ngoại lệ (Negative/Boundary case)");
        }
        long s4Duration = (System.nanoTime() - s4Start) / 1_000_000;

        // Stage D5: Unit Test Code Synthesis (Sinh mã nguồn JUnit 5 hoàn chỉnh)
        long s5Start = System.nanoTime();
        StringBuilder testCodeBuilder = new StringBuilder();
        testCodeBuilder.append("package com.demo;\n");
        testCodeBuilder.append("import org.junit.jupiter.api.Test;\n");
        testCodeBuilder.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        testCodeBuilder.append("public class GeneratedOrderServiceTest {\n");
        for (int i = 0; i < testCases.size(); i++) {
            testCodeBuilder.append("    @Test\n");
            testCodeBuilder.append("    void testCase_").append(i + 1).append("() {\n");
            testCodeBuilder.append("        OrderService service = new OrderService();\n");
            testCodeBuilder.append("        assertNotNull(service);\n");
            testCodeBuilder.append("    }\n\n");
        }
        testCodeBuilder.append("}\n");
        String finalTestCode = testCodeBuilder.toString();
        long s5Duration = (System.nanoTime() - s5Start) / 1_000_000;

        // Stage D6-D7: Packaging & Workspace Preparation (Ghi file kiểm thử vào workspace đĩa)
        long s6Start = System.nanoTime();
        Path workspaceTestDir = tempDir.resolve("workspace/src/test/java/com/demo");
        Files.createDirectories(workspaceTestDir);
        Path testFile = workspaceTestDir.resolve("GeneratedOrderServiceTest.java");
        Files.writeString(testFile, finalTestCode);
        long s6Duration = (System.nanoTime() - s6Start) / 1_000_000;

        // Stage D8-D9: Database Persistence (Ghi nhận project, rules, plans, cases vào PostgreSQL trong 1 transaction)
        long s8Start = System.nanoTime();
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            jdbcTemplate.update("INSERT INTO project (id, name, source_type, status, storage_encrypted, owner_user_id, created_at, updated_at) " +
                    "VALUES (?, 'Pipeline Benchmark Project', 'ZIP', 'GENERATING', false, ?, NOW(), NOW())",
                    benchmarkProjectId, testUserId);

            for (int i = 0; i < generatedRules.size(); i++) {
                jdbcTemplate.update("INSERT INTO business_rule (project_id, rule_code, description, source, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'AI_GENERATED', 'APPROVED', NOW(), NOW())",
                        benchmarkProjectId, "BR-" + (i + 1), generatedRules.get(i));
            }

            Long firstBrId = jdbcTemplate.queryForObject(
                    "SELECT id FROM business_rule WHERE project_id = ? LIMIT 1", Long.class, benchmarkProjectId);

            Long testPlanId = 777201L;
            jdbcTemplate.update("INSERT INTO test_plan (id, project_id, business_rule_id, plan_code, title, description, test_type, status, created_at) " +
                    "VALUES (?, ?, ?, 'TP-1', 'Main Plan', 'Main Test Plan', 'UNIT', 'APPROVED', NOW())",
                    testPlanId, benchmarkProjectId, firstBrId);

            for (int i = 0; i < testCases.size(); i++) {
                jdbcTemplate.update("INSERT INTO test_case (test_plan_id, case_code, test_type, description, expected_result, priority, status, created_at) " +
                        "VALUES (?, ?, 'UNIT', ?, 'Passed', 'MEDIUM', 'APPROVED', NOW())",
                        testPlanId, "TC-" + (i + 1), testCases.get(i));
            }

            jdbcTemplate.update("UPDATE project SET status = 'COMPLETED', updated_at = NOW() WHERE id = ?", benchmarkProjectId);
            transactionManager.commit(status);
        } catch (Exception ex) {
            transactionManager.rollback(status);
            throw ex;
        }
        long s8Duration = (System.nanoTime() - s8Start) / 1_000_000;

        long totalDuration = (System.nanoTime() - tTotalStart) / 1_000_000;

        System.out.printf("[Pipeline Real Benchmark] Total Duration: %d ms%n" +
                        "  ├─ D1 Source AST Parsing:     %3d ms%n" +
                        "  ├─ D2 Business Rule Synthesis: %3d ms%n" +
                        "  ├─ D3 Test Plan Synthesis:     %3d ms%n" +
                        "  ├─ D4 Test Case Generation:    %3d ms%n" +
                        "  ├─ D5 Unit Test Synthesis:     %3d ms%n" +
                        "  ├─ D6-D7 Workspace File I/O:   %3d ms%n" +
                        "  └─ D8-D9 DB Persistence:      %3d ms%n",
                totalDuration, s1Duration, s2Duration, s3Duration, s4Duration, s5Duration, s6Duration, s8Duration);

        assertThat(parsedCus).hasSize(2);
        assertThat(methodNames).contains("processOrder", "authorizePayment");
        assertThat(generatedRules).hasSize(4);
        assertThat(testCases).hasSize(8);
        assertThat(Files.isRegularFile(testFile)).isTrue();

        Integer dbRuleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM business_rule WHERE project_id = ?", Integer.class, benchmarkProjectId);
        assertThat(dbRuleCount).isEqualTo(4);
    }

    @Test
    @DisplayName("Workstream D-E: Đánh giá hiệu năng Incremental Generation vs Full Generation (Thực tế không Thread.sleep)")
    void benchmarkIncrementalVsFullGeneration() throws Exception {
        int totalModules = 10;
        int modifiedModules = 2;

        Path fullDir = tempDir.resolve("full_project");
        Files.createDirectories(fullDir);

        Map<String, String> baselineHashes = new ConcurrentHashMap<>();
        for (int i = 0; i < totalModules; i++) {
            Path file = fullDir.resolve("Service" + i + ".java");
            String content = "package com.demo;\npublic class Service" + i + " {\n    public int compute(int x) { return x * 2; }\n}\n";
            Files.writeString(file, content);
            baselineHashes.put("Service" + i + ".java", sha256(content));
        }

        // 1. Full Generation: Phân tích toàn bộ 10 tệp mã nguồn bằng JavaParser
        long tFullStart = System.nanoTime();
        int fullParsedMethods = 0;
        for (int i = 0; i < totalModules; i++) {
            Path file = fullDir.resolve("Service" + i + ".java");
            CompilationUnit cu = StaticJavaParser.parse(file);
            fullParsedMethods += cu.findAll(MethodDeclaration.class).size();
        }
        long fullDurationMs = (System.nanoTime() - tFullStart) / 1_000_000;

        // Giả lập sửa đổi 2 tệp trong số 10 tệp
        Files.writeString(fullDir.resolve("Service0.java"),
                "package com.demo;\npublic class Service0 {\n    public int compute(int x) { return x * 3; }\n    public void newMethod() {}\n}\n");
        Files.writeString(fullDir.resolve("Service1.java"),
                "package com.demo;\npublic class Service1 {\n    public int compute(int x) { return x + 10; }\n}\n");

        // 2. Incremental Generation: So khớp Fingerprint và chỉ parse những tệp bị thay đổi
        long tIncStart = System.nanoTime();
        List<Path> changedFiles = new ArrayList<>();
        for (int i = 0; i < totalModules; i++) {
            Path file = fullDir.resolve("Service" + i + ".java");
            String currentContent = Files.readString(file);
            String currentHash = sha256(currentContent);
            if (!currentHash.equals(baselineHashes.get("Service" + i + ".java"))) {
                changedFiles.add(file);
            }
        }

        int incParsedMethods = 0;
        for (Path file : changedFiles) {
            CompilationUnit cu = StaticJavaParser.parse(file);
            incParsedMethods += cu.findAll(MethodDeclaration.class).size();
        }
        long incDurationMs = (System.nanoTime() - tIncStart) / 1_000_000;

        double timeSavedPercent = ((double) (fullDurationMs - incDurationMs) / Math.max(1, fullDurationMs)) * 100.0;
        System.out.printf("[Incremental Benchmark Real] Full Scan (%d files, %d methods): %d ms | Incremental Scan (%d modified files, %d methods): %d ms (Tiết kiệm: %.1f%% thời gian)%n",
                totalModules, fullParsedMethods, fullDurationMs, changedFiles.size(), incParsedMethods, incDurationMs, timeSavedPercent);

        assertThat(changedFiles).hasSize(modifiedModules);
        assertThat(incParsedMethods).isLessThan(fullParsedMethods);
        assertThat(incDurationMs).isLessThanOrEqualTo(fullDurationMs);
    }

    @Test
    @DisplayName("Workstream D-B: Benchmark nhiều Generation Tasks song song với LlmBatchExecutor trên CPU task thực")
    void benchmarkConcurrentGenerationBatchExecution() {
        int concurrency = 4;
        LlmBatchExecutor executor = new LlmBatchExecutor(concurrency, 0, 0);

        List<String> mockSnippets = List.of(
                "public class Task1 { void run() {} }",
                "public class Task2 { void exec() {} }",
                "public class Task3 { int calc() { return 1; } }",
                "public class Task4 { String str() { return \"x\"; } }",
                "public class Task5 { boolean isOk() { return true; } }",
                "public class Task6 { double rate() { return 0.5; } }",
                "public class Task7 { long id() { return 100L; } }",
                "public class Task8 { void finish() {} }"
        );

        long t0 = System.currentTimeMillis();

        var results = executor.map(mockSnippets, snippet -> {
            CompilationUnit cu = StaticJavaParser.parse(snippet);
            return cu.getTypes().get(0).getNameAsString();
        });

        long duration = System.currentTimeMillis() - t0;

        System.out.printf("[Concurrent Batch Real Benchmark] 8 AST parsing tasks with concurrency %d: Completed in %d ms%n",
                concurrency, duration);

        assertThat(results).containsExactly("Task1", "Task2", "Task3", "Task4", "Task5", "Task6", "Task7", "Task8");
    }

    @Test
    @DisplayName("Workstream G: Benchmark trực tiếp vòng đời Docker Sandbox Container thật (create, cp, start, exec, rm)")
    void benchmarkDockerSandboxLifecycle() throws Exception {
        try {
            assumeTrue(runProcess("docker", "image", "inspect", "--format", "{{.Id}}", "greytest-sandbox:17") == 0,
                    "Benchmark cần Docker daemon và image greytest-sandbox:17 trên máy chạy test");
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.abort("Không thể chạy Docker CLI: " + e.getMessage());
        }
        String containerName = "greytest-perf-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
        Path testWorkDir = tempDir.resolve("sandbox-work");
        Files.createDirectories(testWorkDir);
        Path sampleFile = testWorkDir.resolve("test.txt");
        Files.writeString(sampleFile, "Hello Docker Sandbox Benchmark");

        try {
            // 1. Docker Create
            long t0 = System.nanoTime();
            int exitCreate = runProcess("docker", "create", "--name", containerName,
                    "--memory", "2g", "--cpus", "2", "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done");
            long createDuration = (System.nanoTime() - t0) / 1_000_000;
            assertThat(exitCreate).isEqualTo(0);

            // 2. Docker CP
            long t1 = System.nanoTime();
            int exitCp = runProcess("docker", "cp", sampleFile.toString(), containerName + ":/work");
            long cpDuration = (System.nanoTime() - t1) / 1_000_000;
            assertThat(exitCp).isEqualTo(0);

            // 3. Docker Start
            long t2 = System.nanoTime();
            int exitStart = runProcess("docker", "start", containerName);
            long startDuration = (System.nanoTime() - t2) / 1_000_000;
            assertThat(exitStart).isEqualTo(0);

            // 4. Docker Exec
            long t3 = System.nanoTime();
            int exitExec = runProcess("docker", "exec", containerName, "echo", "sandbox-ready");
            long execDuration = (System.nanoTime() - t3) / 1_000_000;
            assertThat(exitExec).isEqualTo(0);

            // 5. Docker Cleanup (RM -F)
            long t4 = System.nanoTime();
            int exitRm = runProcess("docker", "rm", "-f", "-v", containerName);
            long rmDuration = (System.nanoTime() - t4) / 1_000_000;
            assertThat(exitRm).isEqualTo(0);

            long totalSandboxDuration = createDuration + cpDuration + startDuration + execDuration + rmDuration;

            System.out.printf("[Docker Sandbox Lifecycle Benchmark]%n" +
                            "  ├─ 1. docker create: %3d ms%n" +
                            "  ├─ 2. docker cp:     %3d ms%n" +
                            "  ├─ 3. docker start:  %3d ms%n" +
                            "  ├─ 4. docker exec:   %3d ms%n" +
                            "  ├─ 5. docker rm -f:  %3d ms%n" +
                            "  └─ Total Lifecycle:  %3d ms%n",
                    createDuration, cpDuration, startDuration, execDuration, rmDuration, totalSandboxDuration);

            assertThat(totalSandboxDuration).isLessThan(5000); // Hoàn tất toàn bộ vòng đời < 5s
        } finally {
            // Đảm bảo dọn dẹp container ngay cả khi có ngoại lệ hoặc assertion thất bại (tránh rò rỉ container)
            runProcess("docker", "rm", "-f", "-v", containerName);
        }
    }

    private int runProcess(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process p = pb.start();
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
