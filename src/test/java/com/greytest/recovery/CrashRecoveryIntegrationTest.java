package com.greytest.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.StorageException;
import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.security.waf.RedisRateLimitStore;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.encryption.StorageEncryptionService;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

/**
 * Phase 6.2 Workstream E: Crash Recovery & Failure Scenarios Integration Tests.
 * Xác minh khả năng phục hồi của hệ thống khi gặp các sự cố crash:
 * 1. Residual workspace cleanup khi reboot sau crash (kèm zero-byte shredding).
 * 2. Background AI job crash giải phóng lock và ghi nhận status FAILED.
 * 3. Race condition / duplicate job submission rejection.
 * 4. Partial upload / zip corruption rollback cleanup.
 * 5. Zip-Slip attack rejection and directory rollback cleanup.
 * 6. Redis outage fallback sang In-Memory store mà không làm gián đoạn request.
 */
@SpringBootTest
public class CrashRecoveryIntegrationTest {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private GenerationJobService generationJobService;

    @Autowired
    private GenerationProgressService progressService;

    @Autowired
    private StorageEncryptionService encryptionService;

    @Test
    @DisplayName("WS-E1: Crash during decrypt / execution leaves residual workspace -> reboot cleanup shreds and deletes all")
    public void testCrashDuringDecryptResidualWorkspaceCleanupOnReboot(@TempDir Path tempStorageDir) throws Exception {
        // Tạo StorageWorkspaceService độc lập trỏ vào thư mục tạm được cô lập hoàn toàn
        StorageWorkspaceService isolatedWorkspaceService = new StorageWorkspaceService(
                tempStorageDir.toString(), encryptionService, null);

        Path wsRoot = tempStorageDir.resolve("workspaces");
        Files.createDirectories(wsRoot);

        // Giả lập crash: để lại 2 thư mục workspace tạm thời từ các job trước đó
        Path residualWs1 = wsRoot.resolve("ws-crash-" + UUID.randomUUID());
        Path residualWs2 = wsRoot.resolve("ws-crash-" + UUID.randomUUID());
        Files.createDirectories(residualWs1);
        Files.createDirectories(residualWs2);

        Path secretFile1 = residualWs1.resolve("ConfidentialCode.java");
        Path secretFile2 = residualWs2.resolve("DatabaseConfig.java");
        Files.writeString(secretFile1, "public class ConfidentialCode { // Secret plaintext left behind }");
        Files.writeString(secretFile2, "public class DatabaseConfig { // Plaintext credentials }");

        assertTrue(Files.exists(secretFile1), "Residual file 1 must exist before recovery");
        assertTrue(Files.exists(secretFile2), "Residual file 2 must exist before recovery");

        // Giả lập hệ thống backend reboot và trigger @PostConstruct cleanupLeftoverWorkspaces
        isolatedWorkspaceService.cleanupLeftoverWorkspaces();

        // Kiểm tra toàn bộ thư mục và file tạm đã bị tiêu hủy sạch sẽ
        assertFalse(Files.exists(residualWs1), "Residual workspace 1 must be purged after reboot cleanup");
        assertFalse(Files.exists(residualWs2), "Residual workspace 2 must be purged after reboot cleanup");
        assertFalse(Files.exists(secretFile1), "Secret file 1 must be shredded and unlinked");
        assertFalse(Files.exists(secretFile2), "Secret file 2 must be shredded and unlinked");
    }

    @Test
    @DisplayName("WS-E2: Background AI job worker crash releases project lock and marks status FAILED")
    public void testJobCrashReleasesProjectLock() throws Exception {
        Long testProjectId = 88888L;
        CountDownLatch workerLatch = new CountDownLatch(1);

        // Submit một background worker ném RuntimeException giả lập crash giữa chừng
        generationJobService.submit(testProjectId, GenerationProgressStage.BUSINESS_RULE, () -> {
            try {
                throw new RuntimeException("Simulated crash in background AI pipeline worker");
            } finally {
                workerLatch.countDown();
            }
        });

        // Chờ worker background thực thi xong
        assertTrue(workerLatch.await(5, TimeUnit.SECONDS), "Worker must complete execution");
        Thread.sleep(150); // Chờ khối finally và progress update hoàn tất

        // Xác minh trạng thái tiến độ được ghi nhận là FAILED
        var progress = progressService.get(testProjectId, GenerationProgressStage.BUSINESS_RULE);
        assertNotNull(progress, "Progress must be recorded");
        assertEquals(GenerationProgressStatus.FAILED, progress.status(),
                "Progress status must transition to FAILED after worker crash");

        // Xác minh sau khi crash, project lock được giải phóng hoàn toàn và mutation tiếp theo thực thi bình thường
        AtomicBoolean secondTaskExecuted = new AtomicBoolean(false);
        assertDoesNotThrow(() -> {
            generationJobService.executeMutation(testProjectId, () -> {
                secondTaskExecuted.set(true);
            });
        });

        assertTrue(secondTaskExecuted.get(), "Next mutation must execute cleanly after prior job crash");
    }

    @Test
    @DisplayName("WS-E3: Concurrent mutation on active project rejected with GenerationInProgressException")
    public void testConcurrentJobsRejectDuplicateRun() throws Exception {
        Long testProjectId = 99999L;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Thread longRunningWorker = new Thread(() -> {
            generationJobService.executeMutation(testProjectId, () -> {
                startLatch.countDown();
                try {
                    finishLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        longRunningWorker.start();
        assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Worker must begin execution");

        // Khi worker đang chạy, request song song phải bị từ chối
        assertThrows(GenerationInProgressException.class, () -> {
            generationJobService.executeMutation(testProjectId, () -> {
                // Should not reach here
            });
        });

        // Kết thúc worker
        finishLatch.countDown();
        longRunningWorker.join(2000);

        // Sau khi worker kết thúc, request tiếp theo được chấp nhận
        assertDoesNotThrow(() -> {
            generationJobService.executeMutation(testProjectId, () -> {
                // Success
            });
        });
    }

    @Test
    @DisplayName("WS-E4: Partial/Corrupted ZIP upload crash triggers atomic directory cleanup rollback")
    public void testPartialZipUploadCrashCleanup() throws Exception {
        Path projectsRoot = Path.of("./storage", "projects").toAbsolutePath().normalize();
        Files.createDirectories(projectsRoot);

        long dirCountBefore;
        try (var stream = Files.list(projectsRoot)) {
            dirCountBefore = stream.filter(Files::isDirectory).count();
        }

        // Giả lập network stream bị đứt giữa chừng trong lúc upload khiến getInputStream ném IOException
        MockMultipartFile corruptedFile = new MockMultipartFile(
                "file", "upload_crash.zip", "application/zip", new byte[100]) {
            @Override
            public InputStream getInputStream() throws IOException {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("Simulated network drop / IO error during file upload");
                    }
                };
            }
        };

        // FileStorageService.storeZip ném StorageException và tự rollback xóa thư mục vừa tạo
        StorageException ex = assertThrows(StorageException.class, () -> {
            fileStorageService.storeZip(corruptedFile);
        });

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("Không giải nén được file ZIP") || ex.getMessage().contains("ZIP"));

        // Xác minh số thư mục con trong projectsRoot không tăng lên (thư mục tạo dở đã bị xóa)
        long dirCountAfter;
        try (var stream = Files.list(projectsRoot)) {
            dirCountAfter = stream.filter(Files::isDirectory).count();
        }
        assertEquals(dirCountBefore, dirCountAfter, "No orphaned directories must remain after upload failure");
    }

    @Test
    @DisplayName("WS-E5: Zip-Slip malicious archive rejected and extracted entries rolled back cleanly")
    public void testZipSlipAttackTriggersRollbackCleanup() throws Exception {
        Path projectsRoot = Path.of("./storage", "projects").toAbsolutePath().normalize();
        Files.createDirectories(projectsRoot);

        long dirCountBefore;
        try (var stream = Files.list(projectsRoot)) {
            dirCountBefore = stream.filter(Files::isDirectory).count();
        }

        // Tạo file ZIP chứa entry Zip-Slip độc hại trỏ ra ngoài
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry validEntry = new ZipEntry("src/App.java");
            zos.putNextEntry(validEntry);
            zos.write("public class App {}".getBytes());
            zos.closeEntry();

            ZipEntry maliciousEntry = new ZipEntry("../../evil.sh");
            zos.putNextEntry(maliciousEntry);
            zos.write("rm -rf /".getBytes());
            zos.closeEntry();
        }

        MockMultipartFile zipSlipFile = new MockMultipartFile(
                "file", "zipslip.zip", "application/zip", baos.toByteArray());

        // FileStorageService.storeZip ném InvalidProjectSourceException và tự rollback xóa thư mục vừa tạo
        assertThrows(InvalidProjectSourceException.class, () -> {
            fileStorageService.storeZip(zipSlipFile);
        });

        // Xác minh số thư mục con trong projectsRoot không tăng lên (thư mục tạo dở đã bị xóa)
        long dirCountAfter;
        try (var stream = Files.list(projectsRoot)) {
            dirCountAfter = stream.filter(Files::isDirectory).count();
        }
        assertEquals(dirCountBefore, dirCountAfter, "No orphaned directories must remain after Zip-Slip attack");
    }

    @Test
    @DisplayName("WS-E6: Redis network outage triggers graceful fallback to In-Memory store without throwing 500")
    public void testRedisDisconnectionFallbackToInMemory() {
        InMemoryRateLimitStore memoryStore = new InMemoryRateLimitStore();
        RedisRateLimitStore fallbackStore = new RedisRateLimitStore(
                memoryStore,
                true, // redisEnabled = true
                "localhost",
                59999, // Dead port
                50);   // fast timeout

        try {
            String testIp = "192.168.100.50";

            // Yêu cầu recordAndCheck không được throw exception mà phải fallback sang In-Memory
            assertDoesNotThrow(() -> {
                boolean allowed = fallbackStore.recordAndCheck(testIp, 60, 10);
                assertTrue(allowed, "First request via fallback store must be allowed");
            });

            // Block và IsBlocked cũng phải hoạt động qua memory fallback
            assertDoesNotThrow(() -> {
                fallbackStore.block(testIp, "Simulated attack during Redis outage", 3600);
                assertTrue(fallbackStore.isBlocked(testIp), "IP must be blocked in fallback store");
            });
        } finally {
            fallbackStore.close();
        }
    }
}
