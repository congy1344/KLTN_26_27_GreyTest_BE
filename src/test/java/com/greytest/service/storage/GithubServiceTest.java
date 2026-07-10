package com.greytest.service.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.greytest.exception.InvalidProjectSourceException;

class GithubServiceTest {

    @Test
    void rejectsSpoofedGithubHostBeforeCreatingDirectory() {
        FileStorageService storage = mock(FileStorageService.class);

        assertThatThrownBy(() -> new GithubService(storage).clone("https://github.com.attacker.example/user/repo"))
                .isInstanceOf(InvalidProjectSourceException.class);
        verifyNoInteractions(storage);
    }
}
