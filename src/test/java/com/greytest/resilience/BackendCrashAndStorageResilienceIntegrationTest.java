package com.greytest.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.exception.StorageException;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.encryption.StorageEncryptionService;

/**
 * Phase 6.3 Workstreams D, E, F, G: Backend Crash, Storage Failures & Concurrency Resilience.
 * Xác minh:
 * D6. Worker phát sinh ngoại lệ không mong muốn: trạng thái chuyển FAILED, giải phóng lock activeProjects.
 * D7 & F4. Request retry lặp lại: chặn race condition, trả về GenerationInProgressException rõ ràng.
 * E1 & E2. Disk full / IO Error khi giải nén ZIP: tự động rollback dọn sạch thư mục dở dang.
 * E5 & E6. Ciphertext bị truncate hoặc thay đổi bit: AES-256-GCM BadTagException, không bao giờ đọc dữ liệu rác.
 * E7. Missing / Wrong DEK: ném StorageException bảo vệ an toàn.
 * G1 & G7. Concurrency & Tenant Isolation: Hai project khác nhau chạy mutation song song độc lập, không block nhau.
 */
@SpringBootTest
public class BackendCrashAndStorageResilienceIntegrationTest {

    @Autowired
    private GenerationJobService generationJobService;

    @Autowired
    private GenerationProgressService progressService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private StorageEncryptionService encryptionService;

    @Value("${greytest.storage.path:./storage}")
    private String storagePath;

    @Test
    @DisplayName("D6: Worker AI background ném RuntimeException - Tự động ghi nhận FAILED và nhả lock activeProjects")
    void testWorkerUnhandledExceptionTransitionsToFailedAndReleasesLock() throws Exception {
        Long testProjectId = 777101L;
        CountDownLatch latch = new CountDownLatch(1);

        generationJobService.submit(testProjectId, GenerationProgressStage.BUSINESS_RULE, () -> {
            try {
                throw new RuntimeException("Simulated unexpected AI model timeout / API quota crash");
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker must execute");

        // Polling chủ động trạng thái FAILED thay vì dùng Thread.sleep cứng
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 3000) {
            var progress = progressService.get(testProjectId, GenerationProgressStage.BUSINESS_RULE);
            if (progress != null && progress.status() == GenerationProgressStatus.FAILED) {
                break;
            }
            Thread.sleep(50);
        }

        var finalProgress = progressService.get(testProjectId, GenerationProgressStage.BUSINESS_RULE);
        assertNotNull(finalProgress);
        assertEquals(GenerationProgressStatus.FAILED, finalProgress.status(),
                "Job status must be FAILED after worker crash");

        // Worker ghi FAILED trước khi nhả khóa trong finally.
        await().atMost(3, TimeUnit.SECONDS).ignoreException(GenerationInProgressException.class)
                .until(() -> generationJobService.executeMutation(testProjectId, () -> true));
    }

    @Test
    @DisplayName("D7 & F4: Client retry trùng lặp khi Job đang chạy - Bị từ chối bằng GenerationInProgressException")
    void testClientRetryDuringRunningJobRejectedSafely() throws Exception {
        Long testProjectId = 777102L;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Thread longRunningTask = new Thread(() -> {
            generationJobService.executeMutation(testProjectId, () -> {
                startLatch.countDown();
                try {
                    finishLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        longRunningTask.start();
        assertTrue(startLatch.await(2, TimeUnit.SECONDS));

        // Client retry gửi request thứ 2 khi request đầu còn đang chạy
        assertThrows(GenerationInProgressException.class, () -> {
            generationJobService.executeMutation(testProjectId, () -> {});
        });

        finishLatch.countDown();
        longRunningTask.join(2000);

        // Sau khi hoàn thành, request mới được chấp nhận
        assertDoesNotThrow(() -> {
            generationJobService.executeMutation(testProjectId, () -> {});
        });
    }

    @Test
    @DisplayName("E1 & E2: Disk full / IO Error khi giải nén ZIP dở dang - Rollback xóa sạch thư mục và file đã giải nén một phần")
    void testDiskFullDuringZipExtractionTriggersAtomicRollback() throws Exception {
        Path projectsRoot = Path.of(storagePath, "projects").toAbsolutePath().normalize();
        Files.createDirectories(projectsRoot);

        long countBefore;
        try (var s = Files.list(projectsRoot)) {
            countBefore = s.filter(Files::isDirectory).count();
        }

        // Tạo 1 zip hợp lệ có 2 entry
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("valid_entry_1.txt"));
            zos.write("Valid content before disk full".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("second_entry.txt"));
            zos.write("Some more bytes".getBytes());
            zos.closeEntry();
        }
        byte[] validZipBytes = baos.toByteArray();

        // MockMultipartFile trả về stream ném IOException ("Disk full") sau khi đã giải nén xong entry 1
        MockMultipartFile diskFullFile = new MockMultipartFile("file", "disk_full.zip", "application/zip", new byte[10]) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    private final ByteArrayInputStream delegate = new ByteArrayInputStream(validZipBytes);
                    private int bytesRead = 0;

                    @Override
                    public int read() throws IOException {
                        if (bytesRead > 60) {
                            throw new IOException("No space left on device (Disk Full Simulation mid-extraction)");
                        }
                        int b = delegate.read();
                        if (b != -1) bytesRead++;
                        return b;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        if (bytesRead > 60) {
                            throw new IOException("No space left on device (Disk Full Simulation mid-extraction)");
                        }
                        int n = delegate.read(b, off, len);
                        if (n != -1) bytesRead += n;
                        return n;
                    }
                };
            }
        };

        assertThrows(StorageException.class, () -> fileStorageService.storeZip(diskFullFile));

        long countAfter;
        try (var s = Files.list(projectsRoot)) {
            countAfter = s.filter(Files::isDirectory).count();
        }

        assertEquals(countBefore, countAfter, "Mọi tệp và thư mục giải nén dở dang phải được dọn sạch khi gặp lỗi đĩa đầy");
    }

    @Test
    @DisplayName("E5 & E6: File Ciphertext bị truncate hoặc thay đổi bit - AES-256-GCM phát hiện BadTag, không đọc dữ liệu rác")
    void testTamperedOrTruncatedCiphertextFailsIntegrityCheck(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("source");
        Path encDir = tempDir.resolve("encrypted");
        Path decDir = tempDir.resolve("decrypted");
        Files.createDirectories(srcDir);
        Files.createDirectories(encDir);
        Files.createDirectories(decDir);

        Path sampleFile = srcDir.resolve("CriticalBusinessService.java");
        Files.writeString(sampleFile, "public class CriticalBusinessService { /* sensitive */ }");

        SecretKey dek = encryptionService.generateDek();
        Long projectId = 999301L;

        // Mã hóa thư mục
        encryptionService.encryptDirectory(srcDir, encDir, dek, projectId);

        Path encFile = encDir.resolve("CriticalBusinessService.java");
        assertTrue(Files.exists(encFile));

        // Tình huống 1: File bị cắt cụt (Truncated)
        byte[] originalEncBytes = Files.readAllBytes(encFile);
        byte[] truncatedBytes = new byte[originalEncBytes.length - 10]; // Cắt bớt 10 bytes cuối (phần Authentication Tag)
        System.arraycopy(originalEncBytes, 0, truncatedBytes, 0, truncatedBytes.length);
        Files.write(encFile, truncatedBytes);

        assertThatThrownBy(() -> encryptionService.decryptDirectory(encDir, decDir, dek, projectId))
                .isInstanceOf(StorageException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);

        // Tình huống 2: File bị thay đổi 1 bit trong nội dung ciphertext
        byte[] modifiedBytes = originalEncBytes.clone();
        modifiedBytes[20] ^= 0x01; // Đổi 1 bit
        Files.write(encFile, modifiedBytes);

        assertThatThrownBy(() -> encryptionService.decryptDirectory(encDir, decDir, dek, projectId))
                .isInstanceOf(StorageException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    @DisplayName("E7: Thiếu DEK (null) hoặc sai DEK - StorageException bảo vệ an toàn, không rò rỉ plaintext")
    void testMissingOrWrongDekFailsSafely(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("source");
        Path encDir = tempDir.resolve("encrypted");
        Path decDir = tempDir.resolve("decrypted");
        Files.createDirectories(srcDir);
        Files.createDirectories(encDir);
        Files.createDirectories(decDir);

        Files.writeString(srcDir.resolve("Secret.java"), "class Secret {}");

        SecretKey originalDek = encryptionService.generateDek();
        SecretKey wrongDek = encryptionService.generateDek();
        Long projectId = 999302L;

        encryptionService.encryptDirectory(srcDir, encDir, originalDek, projectId);

        // Sai DEK
        assertThatThrownBy(() -> encryptionService.decryptDirectory(encDir, decDir, wrongDek, projectId))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("sai khoá giải mã");

        // Thiếu DEK (null)
        assertThatThrownBy(() -> encryptionService.decryptDirectory(encDir, decDir, null, projectId))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("G1 & G7: Concurrency & Tenant Isolation - Hai Project khác nhau chạy song song độc lập, không chặn lẫn nhau")
    void testCrossProjectConcurrencyAndTenantIsolation() throws Exception {
        Long project1 = 777201L;
        Long project2 = 777202L;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch p1Started = new CountDownLatch(1);
        CountDownLatch p2Started = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        AtomicBoolean p1Success = new AtomicBoolean(false);
        AtomicBoolean p2Success = new AtomicBoolean(false);

        Thread thread1 = new Thread(() -> {
            try {
                startLatch.await();
                generationJobService.executeMutation(project1, () -> {
                    p1Started.countDown();
                    try {
                        finishLatch.await(5, TimeUnit.SECONDS);
                        p1Success.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception ignored) {}
        });

        Thread thread2 = new Thread(() -> {
            try {
                startLatch.await();
                generationJobService.executeMutation(project2, () -> {
                    p2Started.countDown();
                    try {
                        finishLatch.await(5, TimeUnit.SECONDS);
                        p2Success.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception ignored) {}
        });

        thread1.start();
        thread2.start();

        // Kích hoạt chạy đồng thời
        startLatch.countDown();

        // Cả 2 project đều phải được cấp quyền chạy song song (không lock lẫn nhau)
        assertTrue(p1Started.await(2, TimeUnit.SECONDS), "Project 1 must start mutation");
        assertTrue(p2Started.await(2, TimeUnit.SECONDS), "Project 2 must start mutation in parallel");

        // Thả cho hoàn tất
        finishLatch.countDown();
        thread1.join(2000);
        thread2.join(2000);

        assertTrue(p1Success.get(), "Project 1 must complete successfully");
        assertTrue(p2Success.get(), "Project 2 must complete successfully");
    }
}
