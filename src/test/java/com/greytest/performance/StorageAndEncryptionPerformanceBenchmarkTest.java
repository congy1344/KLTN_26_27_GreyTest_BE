package com.greytest.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.encryption.StorageEncryptionService;

/**
 * Phase 6.4 Workstream A5 & H: Storage & Envelope Encryption Performance Benchmark.
 * Đo lường chính xác các chỉ số:
 * - Throughput và Latency của mã hóa / giải mã AES-256-GCM theo kích thước tệp (Small 10KB, Medium 200KB, Large 5MB).
 * - Tốc độ bọc/mở khoá DEK (Key Wrapping / Unwrapping).
 * - Hiệu năng giải nén ZIP và dọn dẹp workspace tạm.
 * - Độ trễ và tính toàn vẹn khi xử lý đa tệp đồng thời.
 */
public class StorageAndEncryptionPerformanceBenchmarkTest {

    @TempDir
    Path tempDir;

    private StorageEncryptionService encryptionService;
    private FileStorageService fileStorageService;
    private SecretKey kek;
    private SecretKey dek;
    private final SecureRandom random = new SecureRandom();

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, random);
        kek = keyGen.generateKey();

        StorageEncryptionProperties properties = new StorageEncryptionProperties();
        properties.setEncryptionEnabled(true);
        properties.setKek(Base64.getEncoder().encodeToString(kek.getEncoded()));
        properties.setKekVersion("v1");

        encryptionService = new StorageEncryptionService(properties);
        encryptionService.validateConfiguration();

        dek = encryptionService.generateDek();
        fileStorageService = new FileStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("Workstream H1: Benchmark tốc độ mã hóa & giải mã AES-256-GCM theo kích thước tệp")
    void benchmarkAesGcmEncryptionThroughput() throws IOException {
        int[] sizes = { 10 * 1024, 200 * 1024, 5 * 1024 * 1024 };
        String[] labels = { "Small (10 KB)", "Medium (200 KB)", "Large (5 MB)" };
        int iterations = 20;

        for (int idx = 0; idx < sizes.length; idx++) {
            int size = sizes[idx];
            String label = labels[idx];

            byte[] plaintext = new byte[size];
            random.nextBytes(plaintext);

            Path srcFile = tempDir.resolve("src_" + idx + ".dat");
            Path encFile = tempDir.resolve("enc_" + idx + ".enc");
            Path decFile = tempDir.resolve("dec_" + idx + ".dat");
            Files.write(srcFile, plaintext);

            // Warmup
            for (int w = 0; w < 3; w++) {
                encryptionService.encryptFile(srcFile, encFile, dek, 100L, "file_" + idx);
                encryptionService.decryptFile(encFile, decFile, dek, 100L, "file_" + idx);
            }

            List<Long> encTimes = new ArrayList<>();
            List<Long> decTimes = new ArrayList<>();

            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                encryptionService.encryptFile(srcFile, encFile, dek, 100L, "file_" + idx);
                long t1 = System.nanoTime();
                encTimes.add((t1 - t0) / 1_000); // microseconds

                long t2 = System.nanoTime();
                encryptionService.decryptFile(encFile, decFile, dek, 100L, "file_" + idx);
                long t3 = System.nanoTime();
                decTimes.add((t3 - t2) / 1_000); // microseconds
            }

            Collections.sort(encTimes);
            Collections.sort(decTimes);

            long encP50 = encTimes.get(iterations / 2);
            long encP95 = encTimes.get((int) (iterations * 0.95));
            long decP50 = decTimes.get(iterations / 2);
            long decP95 = decTimes.get((int) (iterations * 0.95));

            double sizeMb = size / (1024.0 * 1024.0);
            double encThroughputMbSec = (sizeMb / (encP50 / 1_000_000.0));
            double decThroughputMbSec = (sizeMb / (decP50 / 1_000_000.0));

            System.out.printf("[Storage Benchmark] %s - Encrypt: p50=%.2f ms, p95=%.2f ms (Throughput: %.2f MB/s) | Decrypt: p50=%.2f ms, p95=%.2f ms (Throughput: %.2f MB/s)%n",
                    label, encP50 / 1000.0, encP95 / 1000.0, encThroughputMbSec, decP50 / 1000.0, decP95 / 1000.0, decThroughputMbSec);

            // Xác minh tính toàn vẹn
            byte[] decrypted = Files.readAllBytes(decFile);
            assertThat(decrypted).isEqualTo(plaintext);
            assertThat(encThroughputMbSec).isGreaterThan(1.0); // Tối thiểu 1 MB/s cho file nhỏ và tăng mạnh cho file lớn
        }
    }

    @Test
    @DisplayName("Workstream H2: Benchmark tốc độ Key Wrapping & Unwrapping DEK (Envelope Encryption)")
    void benchmarkKeyWrapLatency() throws Exception {
        int iterations = 1000;
        List<Long> wrapTimes = new ArrayList<>();
        List<Long> unwrapTimes = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            SecretKey currentDek = encryptionService.generateDek();

            long t0 = System.nanoTime();
            String encryptedDek = encryptionService.wrapDek(currentDek);
            long t1 = System.nanoTime();
            wrapTimes.add((t1 - t0) / 1_000); // us

            long t2 = System.nanoTime();
            SecretKey unwrappedDek = encryptionService.unwrapDek(encryptedDek);
            long t3 = System.nanoTime();
            unwrapTimes.add((t3 - t2) / 1_000); // us

            assertThat(unwrappedDek.getEncoded()).isEqualTo(currentDek.getEncoded());
        }

        Collections.sort(wrapTimes);
        Collections.sort(unwrapTimes);

        long wrapP50 = wrapTimes.get(iterations / 2);
        long wrapP95 = wrapTimes.get((int) (iterations * 0.95));
        long unwrapP50 = unwrapTimes.get(iterations / 2);
        long unwrapP95 = unwrapTimes.get((int) (iterations * 0.95));

        System.out.printf("[KeyWrap Benchmark] 1000 ops - Wrap DEK: p50=%.3f ms, p95=%.3f ms | Unwrap DEK: p50=%.3f ms, p95=%.3f ms%n",
                wrapP50 / 1000.0, wrapP95 / 1000.0, unwrapP50 / 1000.0, unwrapP95 / 1000.0);

        assertThat(wrapP50).isLessThan(5000); // p50 < 5ms
        assertThat(unwrapP50).isLessThan(5000); // p50 < 5ms
    }

    @Test
    @DisplayName("Workstream H3: Benchmark tốc độ giải nén ZIP đa tệp và dọn dẹp workspace")
    void benchmarkZipExtractionAndCleanup() throws Exception {
        int fileCount = 50;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < fileCount; i++) {
                zos.putNextEntry(new ZipEntry("src/main/java/com/demo/Service" + i + ".java"));
                zos.write(("package com.demo;\npublic class Service" + i + " { void run() {} }").getBytes());
                zos.closeEntry();
            }
        }
        byte[] zipBytes = baos.toByteArray();
        MockMultipartFile zipFile = new MockMultipartFile("file", "project.zip", "application/zip", zipBytes);

        int iterations = 10;
        List<Long> extractTimes = new ArrayList<>();
        List<Long> cleanupTimes = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            Path projectDir = fileStorageService.storeZip(zipFile);
            long t1 = System.nanoTime();
            extractTimes.add((t1 - t0) / 1_000_000); // ms

            assertTrue(Files.isDirectory(projectDir));

            long t2 = System.nanoTime();
            fileStorageService.delete(projectDir);
            long t3 = System.nanoTime();
            cleanupTimes.add((t3 - t2) / 1_000_000); // ms

            assertThat(Files.exists(projectDir)).isFalse();
        }

        Collections.sort(extractTimes);
        Collections.sort(cleanupTimes);

        long extractP50 = extractTimes.get(iterations / 2);
        long cleanupP50 = cleanupTimes.get(iterations / 2);

        System.out.printf("[ZIP Storage Benchmark] 50 files extraction: p50=%d ms | cleanup: p50=%d ms%n",
                extractP50, cleanupP50);

        assertThat(extractP50).isLessThan(200); // < 200ms
        assertThat(cleanupP50).isLessThan(50);  // < 50ms
    }
}
