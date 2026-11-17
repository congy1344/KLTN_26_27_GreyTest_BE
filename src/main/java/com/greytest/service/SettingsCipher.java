package com.greytest.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mã hoá các giá trị cấu hình nhạy cảm (khoá API AI) trước khi lưu vào bảng system_setting.
 * Dùng AES-256-GCM, khoá suy ra từ biến môi trường GREYTEST_SETTINGS_SECRET; mỗi lần mã hoá
 * dùng một nonce ngẫu nhiên nên cùng một giá trị cho ra chuỗi khác nhau.
 */
@Component
public class SettingsCipher {

    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SettingsCipher(@Value("${greytest.settings-secret:}") String secret) {
        this.key = secret == null || secret.isBlank() ? null : new SecretKeySpec(sha256(secret.trim()), "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Không mã hoá được cấu hình", ex);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Giá trị cấu hình đã mã hoá không đúng định dạng");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // Sai secret hoặc dữ liệu bị sửa: GCM từ chối thay vì trả về rác.
            throw new IllegalStateException("Không giải mã được cấu hình (GREYTEST_SETTINGS_SECRET đã đổi?)", ex);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "Chưa cấu hình GREYTEST_SETTINGS_SECRET nên không thể lưu khoá API bền vững.");
        }
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
