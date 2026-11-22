package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;

/**
 * Hồi quy blog-api (project 1703) và jhipster-sample-app (project 1706): exception dựng message qua
 * constructor nhiều lệnh, getMessage() ghi đè, super() không message, hoặc lớp cha của framework.
 */
class ExceptionConstructorMessageEvidenceTest {
    private static final String EXCEPTIONS = ""
            // BlogapiException: super() rồi gán field, getMessage() trả field đó.
            + "static class BlogapiException extends RuntimeException { private final String message; private final Object status; "
            + "BlogapiException(Object status, String message) { super(); this.status = status; this.message = message; } "
            + "public String getMessage() { return message; } } "
            // ResourceNotFoundException: super() không message; chuỗi format nằm trong helper private không được gọi.
            + "static class ResourceNotFoundException extends RuntimeException { private String resourceName; private String fieldName; private Object fieldValue; "
            + "ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) { super(); "
            + "this.resourceName = resourceName; this.fieldName = fieldName; this.fieldValue = fieldValue; } "
            + "private void setApiResponse() { String message = String.format(\"%s not found with %s: '%s'\", resourceName, fieldName, fieldValue); } } "
            // UnauthorizedException: overload ApiResponse không gán message nên getMessage() trả null.
            + "static class UnauthorizedException extends RuntimeException { private Object apiResponse; private String message; "
            + "UnauthorizedException(Object apiResponse) { super(); this.apiResponse = apiResponse; } "
            + "UnauthorizedException(String message) { super(message); this.message = message; } "
            + "public String getMessage() { return message; } public void setMessage(String message) { this.message = message; } } "
            // UserNotActivatedException: lớp cha AuthenticationException của Spring Security giữ nguyên message.
            + "static class UserNotActivatedException extends AuthenticationException { "
            + "UserNotActivatedException(String message) { super(message); } } ";

    @Test
    void readsMessageAssignedInAMultiStatementConstructorAndReturnedByGetMessage() {
        var classes = source("", "throw new BlogapiException(STATUS, \"Post does not belong to category\");");
        assertThat(validate(classes, "service ném BlogapiException với thông điệp \"Post does not belong to category\".")).isEmpty();
        assertThat(validate(classes, "service ném BlogapiException với thông điệp \"Category not found\"."))
                .hasValueSatisfying(error -> assertThat(error).contains("Post does not belong to category"));
    }

    @Test
    void exceptionBuiltWithSuperWithoutMessageIsReportedAsHavingNoMessage() {
        var classes = source("Long id", "throw new ResourceNotFoundException(\"Post\", \"id\", id);");
        assertThat(validate(classes, "service ném ResourceNotFoundException với thông điệp \"Post not found with id: '1'\"."))
                .hasValueSatisfying(error -> assertThat(error)
                        .contains("ResourceNotFoundException", "no message")
                        .doesNotContain("cannot verify"));
        // Mô tả không trích message thì hợp lệ.
        assertThat(validate(classes, "service ném ResourceNotFoundException với resourceName Post, fieldName id.")).isEmpty();
    }

    @Test
    void overriddenGetMessageFollowsTheConstructorThatWasCalled() {
        assertThat(validate(source("Object apiResponse", "throw new UnauthorizedException(apiResponse);"),
                "service ném UnauthorizedException với thông điệp \"You don't have permission\"."))
                .hasValueSatisfying(error -> assertThat(error).contains("no message"));
        assertThat(validate(source("", "throw new UnauthorizedException(\"You don't have permission\");"),
                "service ném UnauthorizedException với thông điệp \"You don't have permission\".")).isEmpty();
    }

    @Test
    void frameworkParentKeepsTheMessagePassedToItsStringConstructor() {
        var classes = source("String login", "throw new UserNotActivatedException(\"User \" + login + \" was not activated\");");
        assertThat(validate(classes, "service ném UserNotActivatedException với thông điệp \"User <login> was not activated\".")).isEmpty();
        assertThat(validate(classes, "service ném UserNotActivatedException với thông điệp \"User <login> is disabled\"."))
                .hasValueSatisfying(error -> assertThat(error).contains("User <login> was not activated"));
    }

    private Optional<String> validate(List<ClassContextDto> classes, String description) {
        return new SourceExceptionMessageEvidence(classes).validate(classes, 1L, description, null, true);
    }

    private List<ClassContextDto> source(String parameters, String body) {
        String method = "void run(" + parameters + ") { " + body + " }";
        var dto = new MethodContextDto(1L, "sample.Service", "run", "void", List.of(), List.of(), "PUBLIC", method,
                1, 1, List.of(), List.of(), List.of());
        String unit = "package sample; import org.springframework.security.core.AuthenticationException; "
                + "class Service { static final Object STATUS = null; " + EXCEPTIONS + method + " }";
        return List.of(new ClassContextDto(1L, "sample", "Service", "sample.Service", "SERVICE", "Service.java",
                unit, List.of(), List.of(dto)));
    }
}
