package com.greytest.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisManifestInput;
import com.greytest.dto.AnalysisManifestValidationDto;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.ServiceRelationDto;

class AnalysisManifestServiceTest {

    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final AnalysisManifestService service = new AnalysisManifestService(analysisService);

    @Test
    void buildsDeterministicManifestWithQualifiedSignatures() {
        AnalysisManifestDto manifest = service.buildManifest(analysisResult());

        assertThat(manifest.classes()).containsExactly("demo.UserController", "demo.UserRepository");
        assertThat(manifest.methods()).containsExactly(
                "demo.UserController#find(Long):User");
        assertThat(manifest.endpoints()).containsExactly(
                "GET /users/{id} -> demo.UserController#find(Long):User");
        assertThat(manifest.serviceRepositoryRelations()).containsExactly(
                "demo.UserController -> demo.UserRepository");
    }

    @Test
    void reportsMissingAndUnexpectedInsteadOfOnlyCounts() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisResult());
        AnalysisManifestDto actual = service.buildManifest(analysisResult());
        AnalysisManifestInput expected = new AnalysisManifestInput(
                List.of("demo.Expected", "demo.UserRepository"),
                actual.methods(),
                List.of(),
                actual.serviceRepositoryRelations());

        AnalysisManifestValidationDto validation = service.validateManifest(1L, expected);

        assertThat(validation.exactMatch()).isFalse();
        assertThat(validation.classes().missing()).containsExactly("demo.Expected");
        assertThat(validation.classes().unexpected()).containsExactly("demo.UserController");
        assertThat(validation.endpoints().missing()).isEmpty();
        assertThat(validation.endpoints().unexpected()).containsExactly(
                "GET /users/{id} -> demo.UserController#find(Long):User");
    }

    private AnalysisResultDto analysisResult() {
        EndpointDto endpoint = new EndpointDto(3L, "GET", "/users/{id}", null, null, "find");
        JavaMethodDto method = new JavaMethodDto(
                2L,
                "find",
                "User",
                List.of(new MethodParamDto("id", "Long")),
                List.of(),
                "PUBLIC",
                "User find(Long id) { return null; }",
                10,
                12,
                List.of(endpoint));
        JavaClassDto controller = new JavaClassDto(
                1L,
                "demo",
                "UserController",
                "demo.UserController",
                "CONTROLLER",
                "src/main/java/demo/UserController.java",
                List.of(method));
        JavaClassDto repository = new JavaClassDto(
                4L,
                "demo",
                "UserRepository",
                "demo.UserRepository",
                "REPOSITORY",
                "src/main/java/demo/UserRepository.java",
                List.of());
        ServiceRelationDto relation = new ServiceRelationDto(
                5L,
                "UserController",
                "demo.UserController",
                "UserRepository",
                "demo.UserRepository");
        return new AnalysisResultDto(
                1L, "demo", "ANALYZED", 2, 1, 1, 1, 0,
                List.of(repository, controller), List.of(relation));
    }
}
