package com.greytest.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.service.storage.encryption.StorageEncryptionService;

class StorageEncryptionBenchmarkTest {

    @TempDir
    Path tempDir;

    private StorageEncryptionService encryptionService;
    private SecretKey dek;
    private final SecureRandom random = new SecureRandom();

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, random);
        SecretKey kek = keyGen.generateKey();

        StorageEncryptionProperties properties = new StorageEncryptionProperties();
        properties.setEncryptionEnabled(true);
        properties.setKek(Base64.getEncoder().encodeToString(kek.getEncoded()));
        properties.setKekVersion("v1");

        encryptionService = new StorageEncryptionService(properties);
        encryptionService.validateConfiguration();

        dek = encryptionService.generateDek();
    }

    @Test
    @DisplayName("Đo hiệu năng và benchmark độ trễ mã hóa / giải mã AES-256-GCM")
    void benchmarkEncryptionAndDecryptionLatencies() throws IOException {
        System.out.println("=== BẮT ĐẦU BENCHMARK HIỆU NĂNG MÃ HÓA AES-256-GCM (PHASE 2) ===");

        // Test các kích thước: Small (10 KB), Medium (200 KB), Large (5 MB)
        int[] sizes = { 10 * 1024, 200 * 1024, 5 * 1024 * 1024 };
        String[] labels = { "Nhỏ (10 KB)", "Trung bình (200 KB)", "Lớn (5 MB)" };

        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            String label = labels[i];

            byte[] payload = new byte[size];
            random.nextBytes(payload);

            Path plainFile = tempDir.resolve("bench-" + i + ".bin");
            Path encFile = tempDir.resolve("bench-" + i + ".enc");
            Path decFile = tempDir.resolve("bench-" + i + ".dec");
            Files.write(plainFile, payload);

            // Đo mã hóa 20 lần
            List<Long> encTimes = new ArrayList<>();
            for (int r = 0; r < 20; r++) {
                long start = System.nanoTime();
                encryptionService.encryptFile(plainFile, encFile, dek, 101L, "bench.bin");
                encTimes.add(System.nanoTime() - start);
            }

            // Đo giải mã 20 lần
            List<Long> decTimes = new ArrayList<>();
            for (int r = 0; r < 20; r++) {
                long start = System.nanoTime();
                encryptionService.decryptFile(encFile, decFile, dek, 101L, "bench.bin");
                decTimes.add(System.nanoTime() - start);
            }

            Collections.sort(encTimes);
            Collections.sort(decTimes);

            double encAvgMs = encTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            double encP95Ms = encTimes.get((int) (encTimes.size() * 0.95)) / 1_000_000.0;
            double decAvgMs = decTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            double decP95Ms = decTimes.get((int) (decTimes.size() * 0.95)) / 1_000_000.0;

            long encFileSize = Files.size(encFile);
            long overhead = encFileSize - size;

            System.out.printf("[%s] Payload: %d bytes | Encrypt Avg: %.2f ms (p95: %.2f ms) | Decrypt Avg: %.2f ms (p95: %.2f ms) | Overhead: +%d bytes%n",
                    label, size, encAvgMs, encP95Ms, decAvgMs, decP95Ms, overhead);
        }
        System.out.println("=== KẾT THÚC BENCHMARK HIỆU NĂNG MÃ HÓA AES-256-GCM ===");
    }
}
