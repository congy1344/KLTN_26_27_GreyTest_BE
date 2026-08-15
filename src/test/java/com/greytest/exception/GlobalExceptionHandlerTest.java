package com.greytest.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingAuthorizationHeaderReturnsUnauthorized() {
        var response = handler.handleMissingHeader(new MissingRequestHeaderException("Authorization", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("AUTH_ERROR");
    }

    @Test
    void missingEndpointReturnsNotFound() {
        var response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/api/health"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void concurrentGenerationReturnsConflict() {
        var response = handler.handleGenerationInProgress(
                new GenerationInProgressException("Tác vụ đang chạy."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("GENERATION_IN_PROGRESS");
    }
}
