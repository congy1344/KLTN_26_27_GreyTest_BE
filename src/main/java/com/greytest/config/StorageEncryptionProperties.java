package com.greytest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình mã hóa lưu trữ source code (Phase 2 - Envelope Encryption).
 */
@Configuration
@ConfigurationProperties(prefix = "greytest.storage")
@Getter
@Setter
public class StorageEncryptionProperties {

    /** Bật/tắt mã hóa lưu trữ cho các project mới hoặc tác vụ mã hóa. */
    private boolean encryptionEnabled = false;

    /**
     * Key Encryption Key (KEK) định dạng Base64 của 32 bytes (256-bit).
     * Tuyệt đối không lưu vào database, log hay hardcode trong image.
     */
    private String kek;

    /** Định danh phiên bản của KEK (ví dụ: v1). */
    private String kekVersion = "v1";
}
