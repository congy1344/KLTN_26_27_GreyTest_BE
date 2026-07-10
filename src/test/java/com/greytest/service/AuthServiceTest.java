package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.greytest.repository.AuthUserRepository;

class AuthServiceTest {

    @Test
    void rejectsWeakTokenSecretAtStartup() {
        assertThatThrownBy(() -> new AuthService(mock(AuthUserRepository.class), "too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
