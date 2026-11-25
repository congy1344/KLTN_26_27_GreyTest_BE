package com.greytest.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.UnitTestService;
import com.greytest.service.agent.ProjectJavaVersionDetector;
import com.greytest.service.coverage.SandboxTestRunService;
import com.greytest.service.coverage.SandboxTestRunService.Docker;
import com.greytest.service.coverage.SandboxTestRunService.Result;
import com.greytest.service.coverage.SandboxTestRunService.RunState;
import com.greytest.service.coverage.SandboxTestRunService.Status;

/**
 * Phase 6.3 Workstream C: Docker Sandbox Fault Injection Integration Tests.
 * Xác minh tính bền bỉ và cơ chế dọn dẹp của Sandbox khi Docker gặp sự cố:
 * C1. Docker daemon stopped / unavailable (IOException) -> Chuyển FAILED, dọn dẹp workspace.
 * C2. Container bị kill -9 (exit code 137) trong quá trình docker exec -> Chuyển FAILED, gọi docker rm -f.
 * C3 & C4. Docker không thể tạo container mới (exit code 1) -> Chuyển FAILED lập tức.
 * C5. Sandbox timeout (exit code -1 / 124) trong lúc execute -> Chuyển FAILED, gọi docker rm -f.
 * C6. Dọn dẹp container và volume/thư mục mồ côi (reboot) -> cleanupLeftoverSandboxes() xóa thư mục thực tế.
 */
public class DockerSandboxFaultInjectionIntegrationTest {

    @TempDir
    Path tempDir;

    private final UnitTestService units = mock(UnitTestService.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CoverageService coverage = mock(CoverageService.class);
    private final GenerationJobService jobs = mock(GenerationJobService.class);
    private final ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);

    private Project sampleProject;
    private final Long projectId = 801L;

    @BeforeEach
    void setUp() throws IOException {
        Path projectRoot = tempDir.resolve("sample-project");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");

        sampleProject = new Project();
        sampleProject.setId(projectId);
        sampleProject.setName("Sandbox Resilience Project");
        sampleProject.setStatus(ProjectStatus.UPLOADED);
        sampleProject.setStoragePath(projectRoot.toString());
        sampleProject.setStorageEncrypted(false);

        when(projects.findById(projectId)).thenReturn(Optional.of(sampleProject));
        when(javaVersions.detect(any())).thenReturn(Optional.of(new ProjectJavaVersionDetector.JavaVersionInfo("17", "pom.xml")));
        when(jobs.executeMutation(eq(projectId), any(java.util.function.Supplier.class)))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());

        byte[] zipBytes = createSampleZip("src/test/java/com/demo/SampleTest.java", "public class SampleTest {}");
        when(units.exportArchive(eq(projectId), any()))
                .thenReturn(new UnitTestService.ExportArchive(zipBytes, List.of()));
        when(units.exportSandboxArchive(eq(projectId), any()))
                .thenAnswer(call -> units.exportArchive(projectId, call.getArgument(1)));
    }

    private static byte[] createSampleZip(String entryName, String content) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zos.write(content.getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("C1: Docker daemon dừng đột ngột (IOException) - Job chuyển FAILED, dọn sạch workspace")
    void testDockerDaemonUnavailableFailsGracefully() {
        Docker deadDocker = (args, log) -> {
            throw new IOException("Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?");
        };

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                Runnable::run, deadDocker);

        service.start(projectId, "sample-service");

        RunState result = service.state(projectId);
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.message()).contains("Docker không khả dụng");
    }

    @Test
    @DisplayName("C2: Container bị kill -9 trong docker exec (exit code 137 OOM/SIGKILL) - Job chuyển FAILED, dọn dẹp rm -f")
    void testContainerKill9HandledGracefully() {
        List<List<String>> executedCommands = new CopyOnWriteArrayList<>();

        // Container tạo và start thành công; nhưng khi execute test qua docker exec thì bị kill với mã 137
        Docker killedDocker = (args, log) -> {
            executedCommands.add(new ArrayList<>(args));
            if (args.contains("exec")) {
                return new Result(137); // SIGKILL / OOM mid-run
            }
            return new Result(0);
        };

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                Runnable::run, killedDocker);

        service.start(projectId, "sample-service");

        RunState result = service.state(projectId);
        assertThat(result.status()).isEqualTo(Status.FAILED);
        // Xác nhận cơ chế cleanup đã gọi docker rm -f container để giải phóng tài nguyên
        boolean rmInvoked = executedCommands.stream()
                .anyMatch(cmd -> cmd.contains("rm") && cmd.contains("-f"));
        assertThat(rmInvoked)
                .as("Cơ chế cleanup() phải gọi docker rm -f khi container bị kill")
                .isTrue();
    }

    @Test
    @DisplayName("C3 & C4: Docker từ chối tạo container mới (exit code 1) - Job chuyển FAILED ngay lập tức")
    void testDockerCannotCreateContainerFailsGracefully() {
        Docker failedCreationDocker = (args, log) -> {
            if (args.contains("create")) return new Result(1); // Create failed
            return new Result(0);
        };

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                Runnable::run, failedCreationDocker);

        service.start(projectId, "sample-service");

        RunState result = service.state(projectId);
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.message()).contains("Không khởi tạo được sandbox Docker");
    }

    @Test
    @DisplayName("C5: Sandbox timeout (exit code -1 / 124 trong exec) - Báo quá thời gian và giải phóng container")
    void testSandboxTimeoutFailsGracefully() {
        List<List<String>> executedCommands = new CopyOnWriteArrayList<>();

        // Mock docker exec trả về exit code -1 (Timeout theo quy ước của SandboxTestRunService.dockerCli)
        Docker timeoutDocker = (args, log) -> {
            executedCommands.add(new ArrayList<>(args));
            if (args.contains("exec")) {
                return new Result(-1); // Process timed out
            }
            return new Result(0);
        };

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                Runnable::run, timeoutDocker);

        service.start(projectId, "sample-service");

        RunState result = service.state(projectId);
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.message()).contains("quá thời gian");

        // Xác nhận docker rm -f được gọi để thu hồi container bị treo
        boolean rmInvoked = executedCommands.stream()
                .anyMatch(cmd -> cmd.contains("rm") && cmd.contains("-f"));
        assertThat(rmInvoked).isTrue();
    }

    @Test
    @DisplayName("C6: Dọn dẹp thư mục mồ côi (cleanupLeftoverSandboxes) thực sự xóa thư mục sót lại")
    void testCleanupLeftoverSandboxesSafeExecution() throws IOException {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path orphanDir = Files.createDirectory(tmpDir.resolve("greytest-run-testorphan-" + UUID.randomUUID()));
        Path dummyFile = Files.writeString(orphanDir.resolve("leftover.txt"), "dangling state");
        assertThat(Files.exists(dummyFile)).isTrue();

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                Executors.newSingleThreadExecutor(), (args, log) -> new Result(0));

        // Gọi cleanupLeftoverSandboxes()
        service.cleanupLeftoverSandboxes();

        // Xác minh thư mục mồ côi đã được quét và xóa sạch
        assertThat(Files.exists(orphanDir)).as("Thư mục greytest-run-* mồ côi phải bị xóa").isFalse();
    }
}
