package com.greytest.service.storage.encryption;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.exception.StorageException;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Dịch vụ mã hóa và giải mã source code tại rest (Phase 2).
 * Sử dụng kiến trúc Envelope Encryption:
 * - KEK (Key Encryption Key): Lấy từ biến môi trường APP_STORAGE_KEK, 256-bit AES key.
 * - DEK (Data Encryption Key): 256-bit AES key ngẫu nhiên cho từng project, wrap bằng KEK qua AESWrap (RFC 3394).
 * - File Encryption: AES-256-GCM với 12-byte IV ngẫu nhiên cho mỗi file và AAD gắn chặt với Project ID + relative path.
 */
@Slf4j
@Service
public class StorageEncryptionService {

    public static final byte[] MAGIC_HEADER = new byte[] { 'G', 'T', '_', 'E' };
    public static final byte FORMAT_VERSION = 1;
    public static final int GCM_IV_LENGTH = 12;
    public static final int GCM_TAG_LENGTH_BITS = 128;
    public static final int HEADER_LENGTH = MAGIC_HEADER.length + 1 + 1 + GCM_IV_LENGTH; // 4 + 1 + 1 + 12 = 18 bytes
    private static final String WRAP_ALGORITHM = "AESWrap";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    private final StorageEncryptionProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey kek;

    public StorageEncryptionService(StorageEncryptionProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (properties.isEncryptionEnabled()) {
            if (properties.getKek() == null || properties.getKek().isBlank()) {
                throw new IllegalStateException(
                        "Mã hóa lưu trữ (GREYTEST_ENCRYPTION_ENABLED) đang BẬT nhưng APP_STORAGE_KEK chưa được cấu hình. "
                                + "Hệ thống từ chối khởi động để tránh rò rỉ dữ liệu plaintext.");
            }
            try {
                byte[] keyBytes = Base64.getDecoder().decode(properties.getKek().trim());
                if (keyBytes.length != 32) {
                    throw new IllegalStateException(
                            "APP_STORAGE_KEK không hợp lệ: độ dài phải là 32 bytes (256 bits), nhưng nhận được "
                                    + keyBytes.length + " bytes.");
                }
                this.kek = new SecretKeySpec(keyBytes, "AES");
                log.info("Storage Encryption Core đã kích hoạt (Envelope Encryption AES-256-GCM, KEK version: {})",
                        properties.getKekVersion());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APP_STORAGE_KEK không phải chuỗi Base64 hợp lệ: " + e.getMessage(), e);
            }
        } else if (properties.getKek() != null && !properties.getKek().isBlank()) {
            // Khi flag tắt nhưng có KEK cấu hình sẵn, parse để hỗ trợ giải mã các project đã mã hóa trước đó
            try {
                byte[] keyBytes = Base64.getDecoder().decode(properties.getKek().trim());
                if (keyBytes.length == 32) {
                    this.kek = new SecretKeySpec(keyBytes, "AES");
                }
            } catch (Exception ignored) {
                // Ignore if disabled and invalid
            }
        }
    }

    public boolean isEncryptionEnabled() {
        return properties.isEncryptionEnabled();
    }

    public String getKekVersion() {
        return properties.getKekVersion();
    }

    /**
     * Cho phép nạp KEK động trong môi trường kiểm thử (Testing).
     */
    public void setKekForTesting(SecretKey testKek, String kekVersion) {
        this.kek = testKek;
        this.properties.setKekVersion(kekVersion);
    }

    /**
     * Sinh Data Encryption Key (DEK) ngẫu nhiên 256-bit cho một project.
     */
    public SecretKey generateDek() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, secureRandom);
            return keyGen.generateKey();
        } catch (GeneralSecurityException e) {
            throw new StorageException("Không khởi tạo được AES KeyGenerator", e);
        }
    }

    /**
     * Wrap (mã hóa) DEK bằng KEK sử dụng chuẩn AES Key Wrap (RFC 3394).
     */
    public String wrapDek(SecretKey dek) {
        SecretKey activeKek = requireKek();
        try {
            Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.WRAP_MODE, activeKek);
            byte[] wrapped = cipher.wrap(dek);
            return Base64.getEncoder().encodeToString(wrapped);
        } catch (GeneralSecurityException e) {
            throw new StorageException("Lỗi khi wrap DEK bằng KEK", e);
        }
    }

    /**
     * Unwrap (giải mã) DEK từ chuỗi Base64 bằng KEK.
     */
    public SecretKey unwrapDek(String encryptedDekBase64) {
        if (encryptedDekBase64 == null || encryptedDekBase64.isBlank()) {
            throw new StorageException("Không có DEK đã mã hóa để giải mã");
        }
        SecretKey activeKek = requireKek();
        try {
            byte[] wrapped = Base64.getDecoder().decode(encryptedDekBase64.trim());
            Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.UNWRAP_MODE, activeKek);
            return (SecretKey) cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new StorageException("Không thể unwrap DEK (sai KEK hoặc DEK bị hỏng)", e);
        }
    }

    /**
     * Re-wrap DEK từ KEK cũ sang KEK mới phục vụ quy trình KEK Rotation an toàn.
     */
    public String reWrapDek(String encryptedDekBase64, SecretKey oldKek, SecretKey newKek) {
        if (encryptedDekBase64 == null || encryptedDekBase64.isBlank()) {
            throw new StorageException("Không có DEK để re-wrap");
        }
        if (oldKek == null || newKek == null) {
            throw new StorageException("Cần cung cấp cả oldKek và newKek để re-wrap DEK");
        }
        try {
            byte[] wrapped = Base64.getDecoder().decode(encryptedDekBase64.trim());
            Cipher unwrapCipher = Cipher.getInstance(WRAP_ALGORITHM);
            unwrapCipher.init(Cipher.UNWRAP_MODE, oldKek);
            SecretKey dek = (SecretKey) unwrapCipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY);

            Cipher wrapCipher = Cipher.getInstance(WRAP_ALGORITHM);
            wrapCipher.init(Cipher.WRAP_MODE, newKek);
            byte[] reWrapped = wrapCipher.wrap(dek);
            return Base64.getEncoder().encodeToString(reWrapped);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new StorageException("Không thể re-wrap DEK (sai KEK cũ hoặc DEK bị hỏng)", e);
        }
    }

    /**
     * Chuyển đổi chuỗi Base64 sang SecretKey AES 256-bit.
     */
    public SecretKey parseKekBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new StorageException("KEK Base64 không được để trống");
        }
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length != 32) {
            throw new StorageException("KEK phải có độ dài đúng 256-bit (32 bytes)");
        }
        return new javax.crypto.spec.SecretKeySpec(bytes, "AES");
    }

    /**
     * Mã hóa một file plaintext thành ciphertext AES-256-GCM với AAD gắn chặt với Project ID và relative path.
     * Sử dụng atomic write: ghi file .tmp rồi đổi tên để tránh file hỏng giữa chừng.
     */
    public void encryptFile(Path sourcePlaintext, Path targetCiphertext, SecretKey dek, Long projectId, String relativePath) {
        if (!Files.isRegularFile(sourcePlaintext, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Path targetDir = targetCiphertext.getParent();
        if (targetDir != null && !Files.exists(targetDir)) {
            try {
                Files.createDirectories(targetDir);
            } catch (IOException e) {
                throw new StorageException("Không tạo được thư mục đích để lưu file mã hóa: " + targetDir, e);
            }
        }

        Path tempTarget = targetCiphertext.resolveSibling(targetCiphertext.getFileName().toString() + ".tmp." + secureRandom.nextInt(100000));
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(computeAad(projectId, relativePath));

            byte[] plaintext = Files.readAllBytes(sourcePlaintext);
            byte[] ciphertextWithTag = cipher.doFinal(plaintext);

            try (OutputStream fileOut = Files.newOutputStream(tempTarget)) {
                // Ghi header 18 bytes: MAGIC (4) + VERSION (1) + IV_LEN (1) + IV (12)
                fileOut.write(MAGIC_HEADER);
                fileOut.write(FORMAT_VERSION);
                fileOut.write((byte) GCM_IV_LENGTH);
                fileOut.write(iv);
                fileOut.write(ciphertextWithTag);
            }

            atomicMoveOrReplace(tempTarget, targetCiphertext);
        } catch (GeneralSecurityException | IOException e) {
            deleteQuietly(tempTarget);
            throw new StorageException("Không mã hóa được file: " + relativePath, e);
        }
    }

    /**
     * Giải mã một file ciphertext AES-256-GCM.
     * Xác thực header magic, version, IV và GCM auth tag kèm AAD.
     */
    public void decryptFile(Path sourceCiphertext, Path targetPlaintext, SecretKey dek, Long projectId, String relativePath) {
        if (!Files.isRegularFile(sourceCiphertext, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Path targetDir = targetPlaintext.getParent();
        if (targetDir != null && !Files.exists(targetDir)) {
            try {
                Files.createDirectories(targetDir);
            } catch (IOException e) {
                throw new StorageException("Không tạo được thư mục đích để lưu file giải mã: " + targetDir, e);
            }
        }

        Path tempTarget = targetPlaintext.resolveSibling(targetPlaintext.getFileName().toString() + ".tmp." + secureRandom.nextInt(100000));
        try (InputStream fis = Files.newInputStream(sourceCiphertext, LinkOption.NOFOLLOW_LINKS)) {
            byte[] header = fis.readNBytes(HEADER_LENGTH);
            if (header.length < HEADER_LENGTH) {
                throw new StorageException("File ciphertext quá ngắn hoặc bị hỏng header: " + relativePath);
            }

            ByteBuffer bb = ByteBuffer.wrap(header);
            byte[] magic = new byte[4];
            bb.get(magic);
            if (!Arrays.equals(magic, MAGIC_HEADER)) {
                throw new StorageException("Magic header không hợp lệ cho file mã hóa: " + relativePath);
            }
            byte version = bb.get();
            if (version != FORMAT_VERSION) {
                throw new StorageException("Phiên bản mã hóa không được hỗ trợ (" + version + ") cho file: " + relativePath);
            }
            int ivLen = bb.get() & 0xFF;
            if (ivLen != GCM_IV_LENGTH) {
                throw new StorageException("Độ dài IV không hợp lệ: " + ivLen);
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            bb.get(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(computeAad(projectId, relativePath));

            byte[] ciphertextWithTag = fis.readAllBytes();
            byte[] plaintext = cipher.doFinal(ciphertextWithTag);

            Files.write(tempTarget, plaintext);
            atomicMoveOrReplace(tempTarget, targetPlaintext);
        } catch (GeneralSecurityException | IOException e) {
            deleteQuietly(tempTarget);
            if (e instanceof AEADBadTagException || (e.getCause() instanceof AEADBadTagException)) {
                throw new StorageException("Dữ liệu bị sửa đổi hoặc sai khoá giải mã (GCM authentication tag mismatch): " + relativePath, e);
            }
            throw new StorageException("Không giải mã được file: " + relativePath, e);
        }
    }

    /**
     * Đọc và giải mã trực tiếp một file trong bộ nhớ (In-Memory) mà không ghi ra đĩa.
     */
    public byte[] readDecryptedBytes(Path sourceCiphertext, SecretKey dek, Long projectId, String relativePath) {
        if (!Files.isRegularFile(sourceCiphertext, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("File không tồn tại: " + sourceCiphertext);
        }
        try (InputStream fis = Files.newInputStream(sourceCiphertext, LinkOption.NOFOLLOW_LINKS)) {
            byte[] header = fis.readNBytes(HEADER_LENGTH);
            if (header.length < HEADER_LENGTH) {
                throw new StorageException("File ciphertext quá ngắn hoặc bị hỏng header: " + relativePath);
            }

            ByteBuffer bb = ByteBuffer.wrap(header);
            byte[] magic = new byte[4];
            bb.get(magic);
            if (!Arrays.equals(magic, MAGIC_HEADER)) {
                throw new StorageException("Magic header không hợp lệ: " + relativePath);
            }
            byte version = bb.get();
            if (version != FORMAT_VERSION) {
                throw new StorageException("Phiên bản mã hóa không khớp: " + version);
            }
            int ivLen = bb.get() & 0xFF;
            byte[] iv = new byte[ivLen];
            bb.get(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(computeAad(projectId, relativePath));

            byte[] ciphertextWithTag = fis.readAllBytes();
            return cipher.doFinal(ciphertextWithTag);
        } catch (AEADBadTagException e) {
            throw new StorageException("Xác thực GCM thất bại: ciphertext bị sửa đổi hoặc sai DEK", e);
        } catch (GeneralSecurityException | IOException e) {
            throw new StorageException("Lỗi khi đọc file mã hóa: " + relativePath, e);
        }
    }

    public String readDecryptedString(Path sourceCiphertext, SecretKey dek, Long projectId, String relativePath) {
        return new String(readDecryptedBytes(sourceCiphertext, dek, projectId, relativePath), StandardCharsets.UTF_8);
    }

    /**
     * Mã hóa toàn bộ cây thư mục source của project vào thư mục đích.
     */
    public void encryptDirectory(Path sourceDir, Path targetDir, SecretKey dek, Long projectId) {
        if (!Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Thư mục nguồn không tồn tại: " + sourceDir);
        }
        try {
            Files.createDirectories(targetDir);
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                walk.forEach(sourcePath -> {
                    Path relative = sourceDir.relativize(sourcePath);
                    if (relative.toString().isBlank()) {
                        return;
                    }
                    Path targetPath = targetDir.resolve(relative).normalize();
                    if (!targetPath.startsWith(targetDir.normalize())) {
                        throw new StorageException("Phát hiện nguy cơ Path Traversal trong đường dẫn relative: " + relative);
                    }
                    if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.createDirectories(targetPath);
                        } catch (IOException e) {
                            throw new StorageException("Không tạo được thư mục con: " + targetPath, e);
                        }
                    } else if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                        encryptFile(sourcePath, targetPath, dek, projectId, relative.toString());
                    }
                });
            }
        } catch (IOException e) {
            throw new StorageException("Lỗi khi duyệt cây thư mục để mã hóa", e);
        }
    }

    /**
     * Giải mã toàn bộ cây thư mục ciphertext vào thư mục đích.
     */
    public void decryptDirectory(Path sourceDir, Path targetDir, SecretKey dek, Long projectId) {
        if (!Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Thư mục nguồn mã hóa không tồn tại: " + sourceDir);
        }
        try {
            Files.createDirectories(targetDir);
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                walk.forEach(sourcePath -> {
                    Path relative = sourceDir.relativize(sourcePath);
                    if (relative.toString().isBlank()) {
                        return;
                    }
                    Path targetPath = targetDir.resolve(relative).normalize();
                    if (!targetPath.startsWith(targetDir.normalize())) {
                        throw new StorageException("Phát hiện nguy cơ Path Traversal trong đường dẫn relative: " + relative);
                    }
                    if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.createDirectories(targetPath);
                        } catch (IOException e) {
                            throw new StorageException("Không tạo được thư mục con: " + targetPath, e);
                        }
                    } else if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                        decryptFile(sourcePath, targetPath, dek, projectId, relative.toString());
                    }
                });
            }
        } catch (IOException e) {
            throw new StorageException("Lỗi khi duyệt cây thư mục để giải mã", e);
        }
    }

    /**
     * Kiểm tra xem một file có phải là file đã được mã hóa theo định dạng GreyTest không.
     */
    public boolean isEncryptedFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (InputStream is = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] magic = is.readNBytes(MAGIC_HEADER.length);
            return Arrays.equals(magic, MAGIC_HEADER);
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] computeAad(Long projectId, String relativePath) {
        String cleanPath = relativePath == null ? "" : relativePath.replace('\\', '/').replaceAll("^/+", "");
        String aad = (projectId != null ? projectId : 0L) + ":" + cleanPath;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey requireKek() {
        if (kek == null) {
            throw new IllegalStateException(
                    "KEK chưa được khởi tạo. Vui lòng cấu hình biến môi trường APP_STORAGE_KEK hợp lệ.");
        }
        return kek;
    }

    private void atomicMoveOrReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
