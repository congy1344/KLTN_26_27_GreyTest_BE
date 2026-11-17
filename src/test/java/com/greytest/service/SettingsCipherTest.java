package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SettingsCipherTest {

    @Test
    void encryptsAndDecryptsWithoutStoringThePlainValue() {
        SettingsCipher cipher = new SettingsCipher("local-dev-secret");

        String stored = cipher.encrypt("sk-live-1234567890");

        assertThat(stored).startsWith("v1:").doesNotContain("sk-live");
        assertThat(cipher.decrypt(stored)).isEqualTo("sk-live-1234567890");
    }

    @Test
    void usesAFreshNonceForEveryEncryption() {
        SettingsCipher cipher = new SettingsCipher("local-dev-secret");

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void rejectsValuesEncryptedWithAnotherSecret() {
        String stored = new SettingsCipher("secret-a").encrypt("value");

        assertThatThrownBy(() -> new SettingsCipher("secret-b").decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToEncryptWhenNoSecretIsConfigured() {
        SettingsCipher cipher = new SettingsCipher("  ");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GREYTEST_SETTINGS_SECRET");
    }
}
