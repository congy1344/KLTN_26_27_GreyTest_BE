package com.greytest.service.coverage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

import com.greytest.entity.Project;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.UnitTestService;
import com.greytest.service.agent.ProjectJavaVersionDetector;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

/**
 * Chạy Unit Test đã sinh ngay trên server, trong một container Docker riêng cho mỗi lần chạy,
 * rồi nạp jacoco.xml vào đúng luồng upload coverage sẵn có.
 * Dùng lại nguyên gói ZIP export (test + script run-greytest-coverage.sh) nên kết quả giống hệt khi người dùng tự chạy ở máy.
 */
@Service
public class SandboxTestRunService {
    public enum Status { IDLE, RUNNING, SUCCEEDED, TESTS_FAILED, FAILED }

    /** Trạng thái lần chạy gần nhất của một project; chỉ giữ trong bộ nhớ, mất khi backend khởi động lại. */
    public record RunState(Status status, String message, int tests, int failed, String logTail,
                           boolean compilationError, List<FailedTestDto> failures) {
        public RunState(Status status, String message, int tests, int failed, String logTail) {
            this(status, message, tests, failed, logTail, false, List.of());
        }
    }

    public record FailedTestDto(String className, String methodName, String testCaseCode, String message) { }

    public record Result(int exitCode) { }

    /** Điểm cắt để test không cần Docker thật: chạy `docker <args>`, ghi output vào log. */
    public interface Docker {
        Result run(List<String> args, Path log) throws IOException;
    }

    public static final Set<String> JAVA_IMAGES = Set.of("8", "11", "17", "21");
    public static final Set<String> ALLOWED_IMAGES = Set.of(
            "greytest-sandbox:8", "greytest-sandbox:11", "greytest-sandbox:17", "greytest-sandbox:21",
            "maven:3.9-eclipse-temurin-8", "maven:3.9-eclipse-temurin-11",
            "maven:3.9-eclipse-temurin-17", "maven:3.9-eclipse-temurin-21"
    );
    public static final String NETWORK_NONE = "none";
    public static final String NETWORK_ISOLATED = "greytest_sandbox_net";
    public static final Set<String> ALLOWED_NETWORKS = Set.of(NETWORK_NONE, NETWORK_ISOLATED);

    private static final org.slf4j.Logger auditLogger = org.slf4j.LoggerFactory.getLogger("com.greytest.security.audit");
    private static final long TIMEOUT_MINUTES = 20;
    private static final int LOG_TAIL_LINES = 60;
    private static final int MAX_FAILURE_DETAIL = 3000;
    private static final int MAX_REPAIR_ROUNDS = 2;
    private static final int MAX_LISTED_ASSERTIONS = 5;
    private static final int MAX_REPORTED_FAILURES = 20;
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 300;
    private static final Pattern SUITE = Pattern.compile("<testsuite\\b[^>]*>");

    private final UnitTestService units;
    private final ProjectRepository projects;
    private final CoverageService coverage;
    private final GenerationJobService jobs;
    private final ProjectJavaVersionDetector javaVersions;
    private final Executor executor;
    private final Docker docker;
    @org.springframework.beans.factory.annotation.Value("${greytest.sandbox.maven-args:}")
    private String mavenArgs = "";
    private final Map<Long, RunState> states = new ConcurrentHashMap<>();
    private final Map<Long, Path> liveLogs = new ConcurrentHashMap<>();
    // Service của lượt chạy gần nhất theo project ("" = toàn project), để report theo service không lấy nhầm kết quả
    private final Map<Long, String> runScopes = new ConcurrentHashMap<>();
    // Báo cáo coverage của lượt xác minh đang xét, theo project + module; lượt chạy lại trong cùng lượt thay báo cáo này
    private final Map<String, Long> runReports = new ConcurrentHashMap<>();
    private final Map<Long, Sandbox> activeSandboxes = new ConcurrentHashMap<>();
    /** Container đã ngắt mạng: `docker network disconnect` lần hai trả lỗi nên chỉ ngắt một lần. */
    private final Set<String> airGapped = ConcurrentHashMap.newKeySet();
    private static final Object IMAGE_LOCK = new Object();

    @PostConstruct
    public void cleanupLeftoverSandboxes() {
        // 1. Dọn dẹp thư mục temp greytest-run-* còn sót trên host sau sự cố crash
        try {
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
            if (Files.isDirectory(tmpDir)) {
                try (var stream = Files.list(tmpDir)) {
                    stream.filter(p -> p.getFileName().toString().startsWith("greytest-run-"))
                            .forEach(SandboxTestRunService::deleteTree);
                }
            }
        } catch (Exception ignored) {}

        // 2. Dọn dẹp container greytest-run-* và anonymous volumes mồ côi trên Docker daemon
        try {
            Process process = new ProcessBuilder("docker", "ps", "-a", "--filter", "name=greytest-run-", "--format", "{{.Names}}")
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String containerName = line.trim();
                    if (containerName.startsWith("greytest-run-")) {
                        new ProcessBuilder("docker", "rm", "-f", "-v", containerName)
                                .redirectErrorStream(true)
                                .start()
                                .waitFor(5, TimeUnit.SECONDS);
                        recordSecurityAudit("ORPHANED_CONTAINER_REMOVED", null, containerName, NETWORK_NONE, "REMOVED", 0,
                                "Dọn dẹp container và volume mồ côi khi khởi động lại backend");
                    }
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    @Autowired
    public SandboxTestRunService(UnitTestService units, ProjectRepository projects, CoverageService coverage,
            GenerationJobService jobs, ProjectJavaVersionDetector javaVersions) {
        // ponytail: một luồng, mỗi lúc một sandbox để không quá tải máy; thêm hàng đợi song song khi có nhiều người dùng cùng lúc.
        this(units, projects, coverage, jobs, javaVersions, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "greytest-sandbox");
            thread.setDaemon(true);
            return thread;
        }), SandboxTestRunService::dockerCli);
    }

    public SandboxTestRunService(UnitTestService units, ProjectRepository projects, CoverageService coverage,
            GenerationJobService jobs, ProjectJavaVersionDetector javaVersions, Executor executor, Docker docker) {
        this.units = units;
        this.projects = projects;
        this.coverage = coverage;
        this.jobs = jobs;
        this.javaVersions = javaVersions;
        this.executor = executor;
        this.docker = docker;
    }

    @Autowired(required = false)
    private StorageWorkspaceService storageWorkspaceService;

    public void setStorageWorkspaceService(StorageWorkspaceService storageWorkspaceService) {
        this.storageWorkspaceService = storageWorkspaceService;
    }

    public RunState start(Long projectId, String servicePath) {
        return start(projectId, servicePath, null);
    }

    /** @param repairActorId người yêu cầu AI sửa test không đạt (tính quota cho người này); null thì chỉ chạy, không gọi AI. */
    public RunState start(Long projectId, String servicePath, Long repairActorId) {
        projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        RunState queued = new RunState(Status.RUNNING, "Đang chuẩn bị sandbox...", 0, 0, "");
        RunState previous = states.put(projectId, queued);
        if (previous != null && previous.status() == Status.RUNNING) {
            states.put(projectId, previous);
            throw new GenerationInProgressException("Project này đang chạy test trong sandbox. Vui lòng chờ lượt hiện tại xong.");
        }
        runScopes.put(projectId, java.util.Objects.toString(servicePath, ""));
        executor.execute(() -> run(projectId, servicePath, repairActorId));
        return queued;
    }

    /** Service của lượt chạy gần nhất; "" là toàn project, null khi chưa chạy. */
    public String lastRunServicePath(Long projectId) {
        return runScopes.get(projectId);
    }

    public RunState state(Long projectId) {
        RunState state = states.getOrDefault(projectId, new RunState(Status.IDLE, "", 0, 0, ""));
        Path live = liveLogs.get(projectId);
        // Đang chạy thì trả đuôi log trực tiếp để người dùng thấy Maven đang làm gì.
        return state.status() == Status.RUNNING && live != null
                ? new RunState(state.status(), state.message(), 0, 0, tail(live)) : state;
    }

    /**
     * Worker giữ độc quyền sandbox từ pre-warm đến xác minh; thread sinh chỉ chờ sau khi đã lưu test.
     *
     * @param continuation true khi "Tiếp tục sinh": vẫn là vòng coverage cũ nên báo cáo mới thay báo cáo của lượt trước.
     */
    public void generateAndVerify(Long projectId, String servicePath, boolean continuation, Supplier<Boolean> generation) {
        CompletableFuture<Boolean> generated = new CompletableFuture<>();
        runScopes.put(projectId, java.util.Objects.toString(servicePath, ""));
        CompletableFuture<Void> verified = new CompletableFuture<>();
        var worker = GenerationJobContext.wrap(() -> {
            if (!continuation) startCoverageRound(projectId, servicePath);
            verifyWhenGenerated(projectId, servicePath, generated, verified);
            return null;
        });
        try {
            executor.execute(worker::get);
        } catch (RuntimeException unavailable) {
            if (generation.get()) warnUnverified(projectId, unavailable);
            return;
        }
        try {
            generated.complete(generation.get());
        } finally {
            generated.complete(false);
            verified.join();
        }
    }

    /**
     * Xác minh nền các Unit Test đã lưu (vòng bổ sung coverage), cùng quy tắc với lượt sinh chính: sửa lỗi kỹ thuật,
     * loại test không biên dịch được rồi nạp coverage. Gọi sau khi đã nhả khóa project; không ném lỗi vì test đã được lưu.
     */
    public void verifyInBackground(Long projectId, String servicePath, Long actorUserId) {
        runScopes.put(projectId, java.util.Objects.toString(servicePath, ""));
        states.put(projectId, new RunState(Status.RUNNING, "Đang chờ sandbox để xác minh Unit Test...", 0, 0, ""));
        try {
            executor.execute(() -> verifySaved(projectId, servicePath, actorUserId));
        } catch (RuntimeException unavailable) {
            warnUnverified(projectId, unavailable);
        }
    }

    private void verifySaved(Long projectId, String servicePath, Long actorUserId) {
        Sandbox sandbox = null;
        startCoverageRound(projectId, servicePath);
        try (var scope = GenerationJobContext.openScope(actorUserId)) {
            sandbox = prepare(projectId, servicePath);
            verifyGenerated(sandbox, false);
        } catch (Exception failure) {
            warnUnverified(projectId, failure);
        } finally {
            liveLogs.remove(projectId);
            cleanup(sandbox);
        }
    }

    private void verifyWhenGenerated(Long projectId, String servicePath, CompletableFuture<Boolean> generated,
            CompletableFuture<Void> verified) {
        Sandbox sandbox = null;
        Exception warmFailure = null;
        try {
            if (generated.isDone() && !generated.join()) return;
            try { sandbox = prewarm(projectId, servicePath, generated); }
            catch (Exception failure) { warmFailure = failure; }
            GenerationJobContext.log(sandbox != null
                    ? "Sandbox đã sẵn sàng; đang chờ AI lưu Unit Test."
                    : "Không chuẩn bị trước được sandbox; sẽ dùng lượt xác minh thông thường nếu có thể.");
            if (!generated.join()) return;
            if (warmFailure != null) throw warmFailure;
            if (sandbox == null) sandbox = prepare(projectId, servicePath);
            Sandbox ready = sandbox;
            boolean warmed = Files.isRegularFile(ready.work().resolve("container-ready"));
            // Không bọc trong một transaction: pha này chạy Docker và gọi AI nhiều phút, giữ transaction mở sẽ khoá dòng project
            // và làm các worker sửa test (transaction riêng) đọc dữ liệu cũ.
            verifyGenerated(ready, warmed);
        } catch (Exception failure) {
            if (generated.join()) warnUnverified(projectId, failure);
        } finally {
            liveLogs.remove(projectId);
            cleanup(sandbox);
            verified.complete(null);
        }
    }

    private Sandbox prewarm(Long projectId, String servicePath, CompletableFuture<Boolean> generated) throws IOException {
        Sandbox sandbox = prepare(projectId, servicePath);
        try {
            if (!generated.isDone() && Files.isRegularFile(exportedModule(sandbox).resolve("pom.xml"))) {
                GenerationJobContext.log("Đang chuẩn bị sandbox và biên dịch source trong khi AI sinh Unit Test...");
                createContainer(sandbox, sandbox.root(), sandbox.work().resolve("docker.log"));
                boolean compiled;
                try {
                    compiled = docker.run(List.of("exec", "-w", sandbox.workdir(), "-e", "MAVEN_CONFIG=", "-e", "MAVEN_ARGS=" + mavenArgs,
                                    sandbox.container(), "sh", "-c", warmCompile()),
                            sandbox.work().resolve("docker.log")).exitCode() == 0;
                } catch (IOException | RuntimeException unavailable) {
                    compiled = false;
                }
                if (compiled && !generated.isDone()) return sandbox;
            }
        } catch (IOException | RuntimeException failure) {
            cleanup(sandbox);
            throw failure;
        }
        cleanup(sandbox);
        return null;
    }

    private void verifyGenerated(Sandbox sandbox, boolean warmed) {
        Long projectId = sandbox.projectId();
        announce(projectId, "Đang chuẩn bị sandbox để xác minh Unit Test...");
        Outcome first = attempt(sandbox, warmed, false);
        requireVerified(first);
        RepairResult result = technicalErrors(first.failures()).isEmpty()
                ? new RepairResult(first, "", "") : repair(sandbox, GenerationJobContext.actorUserId(), first, true);
        Outcome current = result.outcome();
        int removed = 0;
        for (int pass = 0; pass < 2; pass++) {
            var compilation = current.failures().stream()
                    .filter(failure -> failure.detail().startsWith("COMPILATION ERROR:")).toList();
            if (compilation.isEmpty()) break;
            int count = jobs.executeMutation(projectId, () -> units.removeUncompilable(projectId, compilation));
            if (count == 0) break;
            removed += count;
            if (pass == 1) {
                String message = "Đã loại tổng cộng " + removed + " Unit Test không biên dịch được. "
                        + "Phần còn lại chưa được xác minh lại; hãy dùng 'Tiếp tục sinh' để sinh lại các test đã loại.";
                announce(projectId, message);
                GenerationProgressService.verificationFinished(message);
                states.put(projectId, new RunState(Status.FAILED, message, 0, 0, current.state().logTail(), true, List.of()));
                return;
            }
            announce(projectId, "Đã loại " + count + " Unit Test không biên dịch được. Đang xác minh lại phần còn lại...");
            current = attempt(sandbox, true, Files.isRegularFile(exportedModule(sandbox).resolve("pom.xml")), true);
            requireVerified(current);
        }
        long runtimeErrors = technicalErrors(current.failures()).stream()
                .filter(failure -> !failure.detail().startsWith("COMPILATION ERROR:")).count();
        String note = result.note() + (removed == 0 ? "" : " Đã loại " + removed + " Unit Test không biên dịch được; hãy dùng 'Tiếp tục sinh' để sinh lại.")
                + (runtimeErrors == 0 ? "" : " Giữ lại " + runtimeErrors + " test còn lỗi kỹ thuật khi chạy.");
        RunState finished = finish(current, result.prefix(), note);
        states.put(projectId, finished);
        GenerationJobContext.log(finished.message());
        GenerationProgressService.verificationFinished(finished.message());
    }

    private static void requireVerified(Outcome outcome) {
        if (outcome.state().status() == Status.FAILED && outcome.failures().isEmpty())
            throw new UnverifiedRunException(outcome.state());
    }

    private static class UnverifiedRunException extends IllegalStateException {
        private final RunState state;

        UnverifiedRunException(RunState state) {
            super(state.message());
            this.state = state;
        }
    }

    private void warnUnverified(Long projectId, Exception failure) {
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage().split("\\R", 2)[0];
        if (reason.length() > 220) reason = reason.substring(0, 217) + "...";
        String message = "Cảnh báo: Unit Test CHƯA được xác minh: " + reason + " Các Unit Test đã lưu được giữ lại.";
        Path log = liveLogs.get(projectId);
        // Giữ chẩn đoán build trước khi sandbox bị dọn, để lỗi cấu hình không che mất lỗi compiler.
        String logTail = log == null ? "" : tail(log);
        RunState state = failure instanceof UnverifiedRunException unverified ? unverified.state : null;
        states.put(projectId, state == null ? new RunState(Status.FAILED, message, 0, 0, logTail)
                : new RunState(Status.FAILED, message, state.tests(), state.failed(), logTail, state.compilationError(), state.failures()));
        if (!GenerationProgressService.verificationFinished(message)) GenerationJobContext.log(message);
    }

    private void announce(Long projectId, String message) {
        states.put(projectId, new RunState(Status.RUNNING, message, 0, 0, ""));
        GenerationJobContext.log(message);
    }

    private static String warmCompile() {
        return "scan=\"$PWD\"; while [ \"$scan\" != / ]; do "
                + "if [ -f \"$scan/mvnw\" ]; then exec sh \"$scan/mvnw\" -B -ntp -q compile; fi; "
                + "scan=$(dirname \"$scan\"); done; exec mvn -B -ntp -q compile";
    }

    /** Kết quả một lượt chạy: trạng thái cuối và các test không đạt (rỗng nếu không có báo cáo). */
    private record Outcome(RunState state, List<UnitTestService.TestFailure> failures) { }

    private record Sandbox(Long projectId, String servicePath, Path work, Path root, String workdir,
            String container, Set<String> generatedFiles, String networkPolicy) { }

    private void run(Long projectId, String servicePath, Long repairActorId) {
        Sandbox sandbox = null;
        startCoverageRound(projectId, servicePath);
        try (var scope = GenerationJobContext.openScope(repairActorId)) {
            sandbox = prepare(projectId, servicePath);
            Outcome first = attempt(sandbox, false);
            if (repairActorId == null || first.failures().isEmpty()) {
                states.put(projectId, finish(first, "", ""));
            } else if (technicalErrors(first.failures()).isEmpty()) {
                states.put(projectId, finish(first, "", " Không có lỗi kỹ thuật nào để AI sửa."));
            } else {
                states.put(projectId, repair(sandbox, repairActorId, first, false).state());
            }
        } catch (Exception failure) {
            states.put(projectId, failed(failure).state());
        } finally {
            liveLogs.remove(projectId);
            cleanup(sandbox);
        }
    }

    private void cleanup(Sandbox sandbox) {
        if (sandbox == null) return;
        activeSandboxes.remove(sandbox.projectId(), sandbox);
        airGapped.remove(sandbox.container());
        long startTime = System.currentTimeMillis();
        try {
            if (Files.exists(sandbox.work().resolve("container-created"))) {
                docker.run(List.of("rm", "-f", sandbox.container(), "-v"), sandbox.work().resolve("docker.log"));
                recordSecurityAudit("SANDBOX_CLEANED", sandbox.projectId(), sandbox.container(), NETWORK_NONE, "REMOVED",
                        System.currentTimeMillis() - startTime, "Container và volume tạm đã được xóa sạch");
            }
        } catch (IOException | RuntimeException ignored) {
            // Tên có tiền tố greytest-run- để dọn tay nếu Docker không phản hồi.
        }
        deleteTree(sandbox.work());
    }

    /**
     * Hủy bỏ tác vụ sandbox đang chạy cho một project và dọn dẹp sạch sẽ tài nguyên.
     */
    public void cancel(Long projectId) {
        if (projectId == null) return;
        Sandbox sandbox = activeSandboxes.remove(projectId);
        if (sandbox != null) {
            cleanup(sandbox);
            recordSecurityAudit("SANDBOX_CANCELLED", projectId, sandbox.container(), NETWORK_NONE, "CANCELLED", 0,
                    "Hủy bỏ sandbox theo yêu cầu người dùng");
        }
        states.put(projectId, new RunState(Status.FAILED, "Tác vụ chạy test trong sandbox đã bị hủy.", 0, 0, ""));
        liveLogs.remove(projectId);
    }

    /**
     * Xóa sạch toàn bộ tài nguyên sandbox (container, volume tạm, per-project maven cache volume) của project khi xóa project.
     */
    public void cleanupProjectResources(Long projectId) {
        if (projectId == null) return;
        cancel(projectId);
        // Xóa per-project Maven cache volume: greytest_sandbox_m2_p{projectId}
        try {
            new ProcessBuilder("docker", "volume", "rm", "-f", "greytest_sandbox_m2_p" + projectId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
            recordSecurityAudit("MAVEN_CACHE_REMOVED", projectId, "greytest_sandbox_m2_p" + projectId, NETWORK_NONE, "REMOVED", 0,
                    "Xóa per-project Maven cache volume khi xóa project");
        } catch (Exception ignored) {}
        states.remove(projectId);
        liveLogs.remove(projectId);
    }

    private record RepairResult(Outcome outcome, String prefix, String note) {
        RunState state() { return finish(outcome, prefix, note); }
    }

    /**
     * Giai đoạn 2: AI sửa các test lỗi kỹ thuật theo lỗi chạy thật rồi chạy lại, tối đa MAX_REPAIR_ROUNDS vòng.
     * Test fail ở assertion không bao giờ đưa cho AI: Business Rule/Test Case đã duyệt là chuẩn, nên đó là phát hiện cho người dùng.
     * Dừng sớm khi hết lỗi kỹ thuật, AI không sửa thêm được, hoặc số lỗi kỹ thuật không giảm (tránh đốt quota vô ích).
     */
    private RepairResult repair(Sandbox sandbox, Long actorId, Outcome first, boolean automatic) {
        Long projectId = sandbox.projectId();
        Outcome current = first;
        int repaired = 0, rounds = 0;
        String note = "";
        try {
            while (rounds < MAX_REPAIR_ROUNDS && !technicalErrors(current.failures()).isEmpty()) {
                List<UnitTestService.TestFailure> failing = technicalErrors(current.failures());
                announce(projectId, "AI đang sửa " + failing.size()
                        + " test lỗi kỹ thuật theo lỗi chạy thật (vòng " + (rounds + 1) + "/" + MAX_REPAIR_ROUNDS + ")...");
                List<UnitTest> previous = jobs.executeAiMutation(projectId, actorId, ActivityAction.GENERATE_UNIT_TEST,
                        () -> units.repairFailing(projectId, failing));
                if (GenerationJobContext.repairQuotaExhausted())
                    note = " Đã hết quota LLM; dừng AI sửa Unit Test. Các test đã đạt được giữ lại.";
                if (previous.isEmpty()) break;
                Outcome next = attempt(sandbox, true);
                if (next.state().status() == Status.FAILED && next.failures().isEmpty()) {
                    // Bản sửa làm hỏng cả lượt build: trả lại bản cũ để project không tệ hơn trước vòng này.
                    jobs.executeMutation(projectId, () -> units.restore(projectId, previous));
                    if (automatic) requireVerified(next);
                    note = " Bản AI sửa ở vòng " + (rounds + 1) + " không chạy được nên đã khôi phục bản trước đó.";
                    break;
                }
                if (technicalErrors(next.failures()).size() > failing.size()) {
                    // Bản sửa làm test khác hỏng theo (thường do đổi setup dùng chung khi gộp lớp): trả lại bản cũ và chạy lại.
                    jobs.executeMutation(projectId, () -> units.restore(projectId, previous));
                    Outcome restored = attempt(sandbox, true);
                    if (automatic) requireVerified(restored);
                    current = restored;
                    note += " Bản AI sửa ở vòng " + (rounds + 1) + " làm tăng lỗi kỹ thuật nên đã khôi phục bản trước đó.";
                    break;
                }
                rounds++;
                repaired += previous.size();
                boolean improved = technicalErrors(next.failures()).size() < failing.size();
                current = next;
                if (!improved) {
                    note += " Số lỗi kỹ thuật không giảm; dừng tự sửa.";
                    break;
                }
                if (GenerationJobContext.repairQuotaExhausted()) break;
            }
            if (rounds == MAX_REPAIR_ROUNDS && !technicalErrors(current.failures()).isEmpty())
                note += " Đã đạt giới hạn " + MAX_REPAIR_ROUNDS + " vòng sửa lỗi kỹ thuật.";
        } catch (RuntimeException failure) {
            if (automatic && !(failure instanceof com.greytest.exception.UsageQuotaExceededException)) throw failure;
            note = " Không tự sửa tiếp được: " + failure.getMessage();
            if (failure instanceof com.greytest.exception.UsageQuotaExceededException)
                note = " Đã hết quota LLM; dừng AI sửa Unit Test. Các test đã đạt được giữ lại.";
            if (automatic) GenerationJobContext.log(note.strip());
        }
        String prefix = repaired == 0 ? "" : "AI đã sửa " + repaired + " lượt test qua " + rounds + " vòng. ";
        return new RepairResult(current, prefix, repaired == 0 && note.isEmpty() ? " AI chưa sửa được test nào." : note);
    }

    private static List<UnitTestService.TestFailure> technicalErrors(List<UnitTestService.TestFailure> failures) {
        return failures.stream().filter(failure -> !failure.assertion()).toList();
    }

    /** Thông báo cuối: ghép phần AI sửa, rồi luôn báo các test fail ở assertion (kể cả khi không yêu cầu AI sửa). */
    private static RunState finish(Outcome outcome, String prefix, String note) {
        RunState state = outcome.state();
        List<String> names = outcome.failures().stream().filter(UnitTestService.TestFailure::assertion)
                .map(failure -> failure.className().substring(failure.className().lastIndexOf('.') + 1) + "#" + failure.methodName())
                .toList();
        // Test fail ở assertion = code chạy ra kết quả khác Test Case đã duyệt, có thể là lệch nghiệp vụ cần người xem
        String assertions = names.isEmpty() ? "" : " Phát hiện " + names.size() + " test cho kết quả khác mong đợi: "
                + String.join(", ", names.subList(0, Math.min(names.size(), MAX_LISTED_ASSERTIONS)))
                + (names.size() > MAX_LISTED_ASSERTIONS ? " … và " + (names.size() - MAX_LISTED_ASSERTIONS) + " test khác" : "")
                + ". Code production đang trả kết quả khác với Test Case đã duyệt, có thể code chưa đúng Business Rule."
                + " Hãy kiểm tra lại code hoặc Business Rule/Test Case tương ứng; AI không tự sửa các test này để không che mất lỗi nghiệp vụ.";
        List<String> compileNames = outcome.failures().stream().filter(failure -> failure.detail().startsWith("COMPILATION ERROR:"))
                .map(failure -> failure.className().substring(failure.className().lastIndexOf('.') + 1) + "#" + failure.methodName()).toList();
        String message = compileNames.isEmpty() ? state.message() : "Còn " + compileNames.size() + " test không biên dịch được: "
                + String.join(", ", compileNames.subList(0, Math.min(compileNames.size(), MAX_LISTED_ASSERTIONS)))
                + (compileNames.size() > MAX_LISTED_ASSERTIONS ? " … và " + (compileNames.size() - MAX_LISTED_ASSERTIONS) + " test khác" : "") + ".";
        return new RunState(state.status(), prefix + message + note + assertions, state.tests(), state.failed(),
                state.logTail(), state.compilationError(), state.failures());
    }

    private Sandbox prepare(Long projectId, String servicePath) throws IOException {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            throw new IllegalStateException("Project chưa có source trên server.");
        }
        Path work = createSecureTempDirectory("greytest-run-p" + projectId + "-");
        Path sourceRoot = Path.of(project.getStoragePath()).toAbsolutePath().normalize();
        if (Boolean.TRUE.equals(project.getStorageEncrypted()) && storageWorkspaceService != null) {
            Path decryptedRoot = work.resolve("decrypted-source");
            Files.createDirectories(decryptedRoot);
            storageWorkspaceService.decryptToDirectory(project, decryptedRoot);
            sourceRoot = decryptedRoot;
        }
        Path module = Path.of(servicePath == null || servicePath.isBlank() ? "." : servicePath).normalize();
        // servicePath đến từ request: không cho trỏ ra ngoài thư mục source của project.
        if (module.isAbsolute() || !sourceRoot.resolve(module).normalize().startsWith(sourceRoot)) {
            throw new IllegalStateException("Đường dẫn module không hợp lệ.");
        }
        String defaultPolicy = System.getProperty("greytest.sandbox.network-policy",
                System.getenv().getOrDefault("GREYTEST_SANDBOX_NETWORK_POLICY", NETWORK_ISOLATED));
        String networkPolicy = ALLOWED_NETWORKS.contains(defaultPolicy) ? defaultPolicy : NETWORK_ISOLATED;
        Sandbox sandbox = new Sandbox(projectId, servicePath, work, sourceRoot, "/work" + (module.toString().isEmpty() ? "" : "/" + module.toString().replace('\\', '/')),
                "greytest-run-" + UUID.randomUUID(), new java.util.LinkedHashSet<>(), networkPolicy);
        activeSandboxes.put(projectId, sandbox);
        return sandbox;
    }

    private Outcome attempt(Sandbox sandbox, boolean fast) {
        return attempt(sandbox, fast, fast && Files.isRegularFile(exportedModule(sandbox).resolve("pom.xml")));
    }

    private Outcome attempt(Sandbox sandbox, boolean fast, boolean offline) {
        return attempt(sandbox, fast, offline, false);
    }

    private Outcome attempt(Sandbox sandbox, boolean fast, boolean offline, boolean allowEmpty) {
        Long projectId = sandbox.projectId();
        String servicePath = sandbox.servicePath(), container = sandbox.container(), workdir = sandbox.workdir();
        Path work = sandbox.work();
        try {
            var exportedArchive = allowEmpty ? units.exportSandboxArchive(projectId, servicePath) : units.exportArchive(projectId, servicePath);
            byte[] archive = exportedArchive.content();
            Path exported = work.resolve("export");
            deleteTree(exported);
            unzip(archive, exported);
            Path log = work.resolve("run.log");
            Path dockerLog = work.resolve("docker.log");
            liveLogs.put(projectId, log);
            syncTests(sandbox, exported, archive, fast, dockerLog);
            announce(projectId, "Đang biên dịch và chạy Unit Test trong sandbox...");
            execute(sandbox, fast, offline, log);

            // Không đọc báo cáo cũ trên host khi lượt mới chưa biên dịch được.
            Path report = work.resolve("jacoco.xml");
            Path surefire = work.resolve("surefire-reports");
            Files.deleteIfExists(report);
            deleteTree(surefire);
            docker.run(List.of("cp", container + ":" + workdir + "/target/surefire-reports", surefire.toString()), dockerLog);
            boolean hasReport = docker.run(List.of("cp", container + ":" + workdir + "/target/site/jacoco/jacoco.xml",
                    report.toString()), dockerLog).exitCode() == 0 && Files.isRegularFile(report);
            if (!hasReport) return withoutCoverage(log, surefire, workdir, exportedArchive);
            int[] counts = surefireCounts(surefire);
            byte[] xml = Files.readAllBytes(report);
            String coverageNote = "Coverage đã được cập nhật.";
            try {
                jobs.executeMutation(projectId, () -> storeCoverage(projectId, servicePath, xml, counts));
            } catch (RuntimeException rejected) {
                // Coverage chỉ là kết quả đi kèm: module chưa đủ điều kiện nhận coverage thì kết quả chạy test vẫn phải được dùng.
                coverageNote = "Chưa cập nhật coverage: " + rejected.getMessage();
            }
            int passed = counts[0] - counts[1];
            var failures = surefireFailures(surefire, exportedArchive);
            return new Outcome(new RunState(counts[1] > 0 ? Status.TESTS_FAILED : Status.SUCCEEDED,
                    "Đã chạy " + counts[0] + " test: " + passed + " đạt, " + counts[1] + " không đạt. " + coverageNote,
                    counts[0], counts[1], tail(log), false, failures.reported()), failures.details());
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    private Outcome withoutCoverage(Path log, Path surefire, String workdir, UnitTestService.ExportArchive archive) throws IOException {
        var compilation = SandboxCompilationFailures.parse(Files.readString(log), archive.content(), workdir, MAX_FAILURE_DETAIL);
        String message = compilation.projectError() == null
                ? "Không tạo được báo cáo coverage. Xem log bên dưới để biết lỗi biên dịch hoặc cấu hình."
                : "Source của project không biên dịch được: " + compilation.projectError();
        if (compilation.compilationError()) {
            return new Outcome(new RunState(Status.FAILED, message, 0, compilation.failures().size(),
                    tail(log), true, List.of()), compilation.failures());
        }
        // Surefire vẫn có thể ghi kết quả test khi JaCoCo không tạo được báo cáo.
        int[] counts = surefireCounts(surefire);
        var failures = surefireFailures(surefire, archive);
        return new Outcome(new RunState(Status.FAILED, message, counts[0], counts[1], tail(log),
                false, failures.reported()), List.of());
    }

    private static String reportKey(Long projectId, String servicePath) {
        return projectId + ":" + java.util.Objects.toString(servicePath, "");
    }

    private void startCoverageRound(Long projectId, String servicePath) {
        runReports.remove(reportKey(projectId, servicePath));
    }

    /** Mỗi lượt xác minh chỉ giữ báo cáo mới nhất, để số vòng và chênh lệch không tăng theo số lần AI sửa rồi chạy lại. */
    private com.greytest.dto.CoverageReportDto storeCoverage(Long projectId, String servicePath, byte[] xml, int[] counts) {
        var saved = coverage.upload(projectId, servicePath, new XmlUpload(xml));
        if (saved != null && saved.id() != null) {
            coverage.recordTestRun(saved.id(), counts[0], counts[1]);
            Long replaced = runReports.put(reportKey(projectId, servicePath), saved.id());
            if (replaced != null) coverage.discard(replaced);
        }
        return saved;
    }

    private static Outcome failed(Exception failure) {
        return new Outcome(new RunState(Status.FAILED,
                failure.getMessage() == null ? "Chạy test trong sandbox thất bại." : failure.getMessage(), 0, 0, ""), List.of());
    }

    private void syncTests(Sandbox sandbox, Path exported, byte[] archive, boolean fast, Path log) throws IOException {
        String container = sandbox.container();
        Set<String> files = new java.util.LinkedHashSet<>();
        Files.createDirectories(exported.resolve("src/test/java"));
        try (var paths = Files.walk(exported.resolve("src/test/java"))) {
            paths.filter(Files::isRegularFile).forEach(path -> files.add(exported.relativize(path).toString().replace('\\', '/')));
        }
        if (!fast) {
            Path root = sandbox.work().resolve("project");
            copyTree(sandbox.root(), root);
            unzip(archive, root.resolve(sandbox.workdir().substring("/work".length()).replaceFirst("^/", "")));
            createContainer(sandbox, root, log);
        } else {
            List<String> remove = new ArrayList<>(List.of("exec", container, "rm", "-f", "--"));
            sandbox.generatedFiles().stream().filter(file -> !files.contains(file)).forEach(file -> remove.add(sandbox.workdir() + "/" + file));
            if (remove.size() > 5) requireDocker(remove, log);
            copyIn(sandbox, exported, sandbox.workdir(), log);
        }
        sandbox.generatedFiles().clear();
        sandbox.generatedFiles().addAll(files);
    }

    /** Image sandbox không có trên registry: tự build một lần từ Dockerfile đi kèm ứng dụng. */
    private void ensureImage(String image, Path work, Path log) throws IOException {
        if (!image.startsWith("greytest-sandbox:")) return;
        synchronized (IMAGE_LOCK) {
            try {
                if (docker.run(List.of("image", "inspect", image), work.resolve("image-inspect.log")).exitCode() == 0) return;
            } catch (IOException unavailable) {
                throw new IOException("Docker không khả dụng.", unavailable);
            }
            Path context = Files.createDirectories(work.resolve("sandbox-image"));
            try (InputStream dockerfile = SandboxTestRunService.class.getResourceAsStream("/sandbox/Dockerfile")) {
                if (dockerfile == null) throw new IOException("Thiếu Dockerfile của sandbox trong ứng dụng.");
                Files.copy(dockerfile, context.resolve("Dockerfile"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            requireDocker(List.of("build", "-t", image, "--build-arg",
                    "JAVA_VERSION=" + image.substring(image.indexOf(':') + 1), context.toString()), log);
        }
    }

    /**
     * Đưa file vào /work. `docker cp` luôn tạo file thuộc root trong khi sandbox chạy UID 1000 và bỏ hết capability
     * (không chown được), nên chép qua volume /stage rồi để chính người dùng sandbox copy sang.
     */
    private void copyIn(Sandbox sandbox, Path source, String target, Path log) throws IOException {
        String stage = "/stage/" + UUID.randomUUID();
        requireDocker(List.of("cp", source + File.separator + ".", sandbox.container() + ":" + stage), log);
        requireDocker(List.of("exec", sandbox.container(), "cp", "-R", stage + "/.", target + "/"), log);
    }

    private void ensureNetwork(String network) {
        if (NETWORK_ISOLATED.equals(network)) {
            try {
                new ProcessBuilder("docker", "network", "create", "--driver", "bridge", NETWORK_ISOLATED)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Network có thể đã tồn tại hoặc môi trường test mock
            }
        }
    }

    private void createContainer(Sandbox sandbox, Path source, Path log) throws IOException {
        Files.createFile(sandbox.work().resolve("container-created"));
        String selectedImage = image(sandbox.projectId());
        validateImage(selectedImage);
        ensureImage(selectedImage, sandbox.work(), log);
        validateContainerName(sandbox.container());
        validateNetwork(sandbox.networkPolicy());
        ensureNetwork(sandbox.networkPolicy());
        String cacheVolume = "greytest_sandbox_m2_p" + sandbox.projectId();
        String m2Mount = selectedImage.startsWith("greytest-sandbox") ? "/home/greytest/.m2" : "/home/ubuntu/.m2";

        List<String> createArgs = new ArrayList<>(List.of(
                "create", "--name", sandbox.container(),
                "--network", sandbox.networkPolicy(),
                "--memory", "2g",
                "--cpus", "2",
                "--pids-limit", "512",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true",
                "--read-only",
                "--user", "1000:1000",
                "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                "-v", cacheVolume + ":" + m2Mount,
                "-v", "/work",
                "-v", "/stage",
                // Không đặt -w vào thư mục module: Docker sẽ tạo sẵn thư mục đó thuộc root và UID 1000 không chép source vào được.
                // Lệnh exec chạy Maven tự chỉ định -w module.
                "-w", "/work",
                selectedImage,
                "sh", "-c", "while :; do sleep 3600; done"
        ));
        requireDocker(createArgs, log);
        recordSecurityAudit("SANDBOX_CREATED", sandbox.projectId(), sandbox.container(), sandbox.networkPolicy(), "SUCCESS", 0,
                "Khởi tạo container bảo mật với network policy: " + sandbox.networkPolicy());
        Path root = source;
        if (source.equals(sandbox.root())) {
            root = sandbox.work().resolve("project");
            copyTree(source, root);
        }
        requireDocker(List.of("start", sandbox.container()), log);
        copyIn(sandbox, root, "/work", log);
        Files.createFile(sandbox.work().resolve("container-ready"));
    }

    private void requireDocker(List<String> command, Path log) throws IOException {
        Result result;
        try { result = docker.run(command, log); }
        catch (IOException unavailable) { throw new IOException("Docker không khả dụng.", unavailable); }
        if (result.exitCode() != 0) throw new IOException("Không khởi tạo được sandbox Docker. " + tail(log));
    }

    private static Path exportedModule(Sandbox sandbox) {
        return sandbox.root().resolve(sandbox.workdir().substring("/work".length()).replaceFirst("^/", ""));
    }

    private void execute(Sandbox sandbox, boolean fast, boolean offline, Path log) throws IOException {
        Files.writeString(log, "");
        long startTime = System.currentTimeMillis();

        // Phase 3.2: Ngắt kết nối mạng khi chạy offline (Air-Gap); kết nối lại nếu cần online
        if (offline) {
            disconnectNetwork(sandbox.projectId(), sandbox.container(), sandbox.networkPolicy(), log);
        } else {
            connectNetwork(sandbox.container(), sandbox.networkPolicy(), log);
        }

        recordSecurityAudit("TEST_EXECUTION_STARTED", sandbox.projectId(), sandbox.container(),
                offline ? NETWORK_NONE : sandbox.networkPolicy(), "RUNNING", 0,
                offline ? "Bắt đầu chạy test trong môi trường Air-Gap" : "Bắt đầu tải dependency");

        // Image maven:* đặt MAVEN_CONFIG=/root/.m2; mvnw của project chèn biến này vào làm tham số → "Unknown lifecycle phase /root/.m2".
        // Truyền cùng cờ build cho compile sớm và chạy test; không nội suy vào lệnh shell.
        List<String> command = new ArrayList<>(List.of("exec", "-w", sandbox.workdir(), "-e", "MAVEN_CONFIG=", "-e", "MAVEN_ARGS=" + mavenArgs));
        if (fast) command.addAll(List.of("-e", "GREYTEST_FAST_RERUN=1"));
        command.addAll(List.of(sandbox.container(), "sh", "./run-greytest-coverage.sh", "-B", "-ntp"));
        if (offline) command.add("-o");
        int exit = docker.run(command, log).exitCode();
        long duration = System.currentTimeMillis() - startTime;
        recordSecurityAudit("TEST_EXECUTION_FINISHED", sandbox.projectId(), sandbox.container(),
                offline ? NETWORK_NONE : sandbox.networkPolicy(),
                exit == 0 ? "SUCCESS" : "EXIT_" + exit, duration, "Hoàn tất chạy test");
        if (exit == -1) throw new IOException("Chạy test trong sandbox quá thời gian cho phép. " + tail(log));
        String output = Files.readString(log);
        if (offline && exit != 0 && output.contains("offline")
                && (output.contains("has not been downloaded") || output.contains("has not been previously downloaded"))) {
            execute(sandbox, fast, false, log);
        }
    }

    private void connectNetwork(String container, String network, Path log) {
        if (NETWORK_NONE.equals(network)) {
            return;
        }
        if (!airGapped.remove(container)) return;
        try {
            docker.run(List.of("network", "connect", network, container), log);
        } catch (Exception ignored) {}
    }

    private void disconnectNetwork(Long projectId, String container, String network, Path log) {
        if (NETWORK_NONE.equals(network)) {
            recordSecurityAudit("AIR_GAP_ENFORCED", projectId, container, NETWORK_NONE, "ALREADY_NONE", 0,
                    "Container đã khởi tạo ở chế độ air-gap (network=none), không cần ngắt kết nối");
            return;
        }
        if (airGapped.contains(container)) return;
        try {
            var res = docker.run(List.of("network", "disconnect", network, container), log);
            if (res.exitCode() != 0) {
                auditLogger.warn("[SANDBOX_AUDIT] Failed to disconnect network {} from container {}: exit={}", network, container, res.exitCode());
                throw new SecurityException("Không thể ngắt kết nối mạng an toàn (" + network + ") cho sandbox container: exit=" + res.exitCode());
            } else {
                airGapped.add(container);
                recordSecurityAudit("AIR_GAP_ENFORCED", projectId, container, NETWORK_NONE, "DISCONNECTED", 0,
                        "Ngắt kết nối mạng trước khi chạy Unit Test");
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            auditLogger.warn("[SANDBOX_AUDIT] Exception disconnecting network {} from container {}: {}", network, container, e.getMessage());
            throw new SecurityException("Lỗi hệ thống khi ngắt kết nối mạng sandbox container: " + e.getMessage(), e);
        }
    }

    private String image(Long projectId) {
        String version = javaVersions.detect(projectId).map(ProjectJavaVersionDetector.JavaVersionInfo::version)
                .map(value -> value.startsWith("1.") ? value.substring(2) : value).orElse("17");
        String ver = JAVA_IMAGES.contains(version) ? version : "17";
        String prefix = System.getProperty("greytest.sandbox.image-prefix",
                System.getenv().getOrDefault("GREYTEST_SANDBOX_IMAGE_PREFIX", "greytest-sandbox:"));
        return prefix + ver;
    }

    public static void validateImage(String image) {
        if (image == null || !ALLOWED_IMAGES.contains(image)) {
            throw new IllegalArgumentException("Docker image không nằm trong allowlist an toàn: " + image);
        }
    }

    public static void validateContainerName(String name) {
        if (name == null || !name.matches("^greytest-run-[a-zA-Z0-9\\-]{1,64}$")) {
            throw new IllegalArgumentException("Tên container không hợp lệ: " + name);
        }
    }

    public static void validateMount(String mount) {
        if (mount == null || mount.isBlank()) {
            throw new IllegalArgumentException("Mount volume không hợp lệ.");
        }
        if (mount.contains("/var/run/docker.sock") || mount.contains("/etc")
                || mount.contains("/proc") || mount.contains("/sys") || mount.contains("/app/storage")
                || mount.startsWith("/root") || mount.matches("^[a-zA-Z]:.*")) {
            throw new SecurityException("Cấm mount các thư mục hệ thống hoặc dữ liệu nhạy cảm: " + mount);
        }
        if (mount.contains(":")) {
            String hostPart = mount.substring(0, mount.indexOf(':'));
            if (hostPart.startsWith("/") || hostPart.startsWith("\\") || hostPart.matches("^[a-zA-Z]:.*") || hostPart.contains("..")) {
                throw new SecurityException("Cấm bind-mount trực tiếp đường dẫn host vào sandbox: " + mount);
            }
        }
    }

    public static void validateDockerArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("Danh sách tham số Docker không được rỗng.");
        }
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) {
                throw new IllegalArgumentException("Tham số Docker không được null.");
            }
            if (arg.contains("\n") || arg.contains("\r")) {
                throw new IllegalArgumentException("Tham số Docker chứa ký tự xuống dòng nguy hiểm: " + arg);
            }
            if (arg.startsWith("--privileged")) {
                throw new SecurityException("Không được phép sử dụng cờ --privileged.");
            }
            if (arg.startsWith("--mount")) {
                throw new SecurityException("Không được phép sử dụng cờ --mount tùy ý.");
            }
            if (arg.equals("--net=host") || arg.equals("--network=host")
                    || ((arg.equals("--net") || arg.equals("--network")) && i + 1 < args.size() && "host".equalsIgnoreCase(args.get(i + 1)))) {
                recordSecurityAudit("SECURITY_VIOLATION_BLOCKED", null, null, "HOST_NETWORK", "BLOCKED", 0, "Cấm host network");
                throw new SecurityException("Cấm sử dụng host network trong sandbox: " + arg);
            }
            if (arg.equals("-v") || arg.equals("--volume")) {
                if (i + 1 < args.size()) {
                    validateMount(args.get(i + 1));
                }
            } else if (arg.startsWith("--volume=")) {
                validateMount(arg.substring("--volume=".length()));
            }
        }
    }

    public static void validateNetwork(String network) {
        if (network == null || !ALLOWED_NETWORKS.contains(network)) {
            recordSecurityAudit("SECURITY_VIOLATION_BLOCKED", null, null, network, "BLOCKED", 0, "Network không hợp lệ");
            throw new SecurityException("Network không nằm trong allowlist an toàn: " + network);
        }
    }

    public static void recordSecurityAudit(String event, Long projectId, String container, String networkPolicy,
            String status, long durationMs, String detail) {
        auditLogger.info("[SANDBOX_AUDIT] event={} projectId={} containerId={} networkPolicy={} status={} durationMs={} detail={}",
                event, projectId != null ? projectId : "N/A", container != null ? container : "N/A",
                networkPolicy != null ? networkPolicy : "NONE", status, durationMs, detail);
    }

    /** Tổng số test và số test không đạt (failures + errors) từ báo cáo Surefire. */
    private static int[] surefireCounts(Path dir) throws IOException {
        int[] counts = new int[2];
        if (!Files.isDirectory(dir)) return counts;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                Matcher suite = SUITE.matcher(Files.readString(file));
                if (!suite.find()) continue;
                counts[0] += attribute(suite.group(), "tests");
                counts[1] += attribute(suite.group(), "failures") + attribute(suite.group(), "errors");
            }
        }
        return counts;
    }

    private record SurefireFailures(List<UnitTestService.TestFailure> details, List<FailedTestDto> reported) { }

    /** Giữ stack trace cho AI sửa lỗi kỹ thuật; giao diện chỉ cần thông báo ngắn từ Surefire. */
    private static SurefireFailures surefireFailures(Path dir, UnitTestService.ExportArchive exportedArchive) throws IOException {
        List<UnitTestService.TestFailure> failures = new ArrayList<>();
        List<FailedTestDto> reported = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new SurefireFailures(failures, List.of());
        byte[] archive = exportedArchive.content();
        var exportedFixtures = SandboxCompilationFailures.exportedFixtureMethods(archive);
        try (var files = Files.list(dir)) {
            // Báo cáo đến từ sandbox nên là dữ liệu không tin cậy: cấm DOCTYPE để chặn XXE.
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            for (Path file : files.filter(path -> path.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                var cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    var testCase = (org.w3c.dom.Element) cases.item(index);
                    // Verify của Mockito cũng dùng <failure> nhưng vẫn là lỗi kỹ thuật có thể sửa.
                    // Có cả hai thì coi là lỗi kỹ thuật để AI vẫn được sửa phần lỗi đó.
                    var error = testCase.getElementsByTagName("error").item(0);
                    var problem = error != null ? error : testCase.getElementsByTagName("failure").item(0);
                    if (problem == null) continue;
                    var element = (org.w3c.dom.Element) problem;
                    String type = element.getAttribute("type");
                    String message = element.getAttribute("message");
                    String stack = element.getTextContent();
                    // Mockito có thể ghi tiêu đề làm type thay vì tên class trong báo cáo Surefire.
                    boolean mockitoVerification = type.startsWith("org.mockito.exceptions.")
                            || type.equals("Wanted but not invoked") && message.stripLeading().startsWith("Wanted but not invoked:")
                                    && message.contains("interaction") && message.contains("with this mock");
                    // Chỉ phân loại lại wrapper JUnit khi stack xác nhận lỗi Mockito, không đoán lỗi nghiệp vụ.
                    boolean unexpectedException = type.equals("org.opentest4j.AssertionFailedError")
                            && message.startsWith("Unexpected exception thrown:")
                            && stack.contains("org.junit.jupiter.api.AssertDoesNotThrow.")
                            && stack.contains("Caused by: org.mockito.exceptions.");
                    String className = testCase.getAttribute("classname");
                    // Test tham số hoá có hậu tố "(args)[1]"; chỉ lấy tên method.
                    String methodName = testCase.getAttribute("name").replaceAll("[(\\[].*", "");
                    String detail = (exportedFixtures.contains(className + "#" + methodName) ? "EXPORTED TEST: " : "")
                            + type + ": " + message + "\n" + unusedStubLines(stack, archive) + stack;
                    failures.add(new UnitTestService.TestFailure(className, methodName,
                            detail.substring(0, Math.min(detail.length(), MAX_FAILURE_DETAIL)),
                            error == null && !unexpectedException && !mockitoVerification));
                    if (reported.size() < MAX_REPORTED_FAILURES) {
                        String shortMessage = (message.isBlank() ? type : message.strip()).lines().findFirst().orElse("").strip();
                        if (shortMessage.length() > MAX_FAILURE_MESSAGE_LENGTH)
                            shortMessage = shortMessage.substring(0, MAX_FAILURE_MESSAGE_LENGTH - 1) + "…";
                        String caseCode = exportedArchive.caseCodesByTest().get(className + "#" + methodName);
                        reported.add(new FailedTestDto(className, methodName, caseCode, shortMessage));
                    }
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException unreadable) {
            // Báo cáo hỏng thì coi như không có chi tiết lỗi; số đếm vẫn lấy được từ surefireCounts.
        }
        return new SurefireFailures(failures, List.copyOf(reported));
    }

    public static final String UNUSED_STUB_LINE = "Unused stub source line: ";
    private static final Pattern UNUSED_STUB_AT = Pattern.compile("->\\s*at ([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+\\.java):(\\d+)\\)");

    /** Dòng code của từng stub Mockito báo thừa, đọc từ chính file đã export, để sửa ở local mà không cần đoán số dòng. */
    static String unusedStubLines(String stack, byte[] archive) {
        if (archive == null || !stack.contains("UnnecessaryStubbingException")) return "";
        Set<String> wanted = new LinkedHashSet<>();
        Matcher at = UNUSED_STUB_AT.matcher(stack);
        while (at.find()) {
            String owner = at.group(1);
            String pkg = owner.contains(".") ? owner.substring(0, owner.lastIndexOf('.')) : "";
            wanted.add((pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + at.group(2) + ":" + at.group(3));
        }
        if (wanted.isEmpty()) return "";
        StringBuilder lines = new StringBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                var hits = wanted.stream().filter(w -> name.endsWith("/" + w.substring(0, w.lastIndexOf(':')))).toList();
                if (hits.isEmpty()) continue;
                List<String> content = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                for (String hit : hits) {
                    int line = Integer.parseInt(hit.substring(hit.lastIndexOf(':') + 1));
                    if (line >= 1 && line <= content.size()) lines.append(UNUSED_STUB_LINE).append(content.get(line - 1).strip()).append('\n');
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
        return lines.toString();
    }

    private static int attribute(String tag, String name) {
        Matcher value = Pattern.compile("\\b" + name + "=\"(\\d+)\"").matcher(tag);
        return value.find() ? Integer.parseInt(value.group(1)) : 0;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path relative = from.relativize(path);
                // Bỏ output build cũ và lịch sử git: không cần cho lần chạy và làm chậm việc copy.
                if (relative.startsWith("target") || relative.startsWith(".git") || relative.startsWith("build")) continue;
                // Repo clone về có thể chứa symlink trỏ ra file của server; không mang chúng vào sandbox.
                if (Files.isSymbolicLink(path)) continue;
                Path target = to.resolve(relative.toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void unzip(byte[] archive, Path into) throws IOException {
        Path base = into.toAbsolutePath().normalize();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = base.resolve(entry.getName()).normalize();
                if (!target.startsWith(base)) throw new IOException("Đường dẫn trong gói test không hợp lệ: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String tail(Path log) {
        try {
            if (log == null || !Files.isRegularFile(log)) return "";
            List<String> lines = new ArrayList<>(Files.readAllLines(log, StandardCharsets.UTF_8));
            return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    public static Path createSecureTempDirectory(String prefix) throws IOException {
        Path tempDir;
        try {
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                var attrs = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                tempDir = Files.createTempDirectory(prefix, attrs);
            } else {
                tempDir = Files.createTempDirectory(prefix);
                File file = tempDir.toFile();
                file.setReadable(false, false);
                file.setReadable(true, true);
                file.setWritable(false, false);
                file.setWritable(true, true);
                file.setExecutable(false, false);
                file.setExecutable(true, true);
            }
        } catch (Exception e) {
            tempDir = Files.createTempDirectory(prefix);
        }
        return tempDir;
    }

    public static void deleteTree(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
            // Thư mục tạm của hệ điều hành; sót lại không ảnh hưởng dữ liệu.
        }
    }

    static Result dockerCli(List<String> args, Path log) throws IOException {
        validateDockerArgs(args);
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                Files.writeString(log, "\n[GreyTest] Quá " + TIMEOUT_MINUTES + " phút, đã dừng sandbox.\n",
                        java.nio.file.StandardOpenOption.APPEND);
                return new Result(-1);
            }
            return new Result(process.exitValue());
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Bị ngắt khi chờ Docker.", interrupted);
        }
    }

    /** Bọc jacoco.xml lấy từ sandbox để đi qua đúng CoverageService.upload như file người dùng tải lên. */
    private record XmlUpload(byte[] content) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "jacoco.xml"; }
        @Override public String getContentType() { return "application/xml"; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), content); }
    }
}
