package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.dto.ProjectDto;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.SourceAnalysisException;
import com.greytest.mapper.ProjectMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.GithubService;
import com.greytest.service.analysis.AnalysisService;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private GithubService githubService;
    @Mock
    private AnalysisService analysisService;

    private ProjectService service() {
        return new ProjectService(
                projectRepository, fileStorageService, githubService, new ProjectMapper(), analysisService);
    }

    @Test
    void createsProjectFromValidZip(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        when(fileStorageService.storeZip(any())).thenReturn(dir);
        when(projectRepository.save(any())).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        ProjectDto dto = service().createFromZip(
                new MockMultipartFile("file", "demo.zip", "application/zip", new byte[] {1}));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.name()).isEqualTo("demo");
        assertThat(dto.sourceType()).isEqualTo(SourceType.ZIP);
        assertThat(dto.status()).isEqualTo(ProjectStatus.ANALYZED);
        verify(analysisService).analyze(1L);
    }

    @Test
    void rejectsZipWithoutBuildFile(@TempDir Path dir) {
        when(fileStorageService.storeZip(any())).thenReturn(dir);

        assertThatThrownBy(() -> service().createFromZip(
                new MockMultipartFile("file", "x.zip", "application/zip", new byte[] {1})))
                .isInstanceOf(InvalidProjectSourceException.class);

        verify(fileStorageService).delete(dir);
    }

    @Test
    void removesStoredSourceWhenAutomaticAnalysisFails(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        when(fileStorageService.storeZip(any())).thenReturn(dir);
        when(projectRepository.save(any())).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(1L);
            return project;
        });
        doThrow(new SourceAnalysisException("Source lỗi"))
                .when(analysisService).analyze(1L);

        assertThatThrownBy(() -> service().createFromZip(
                new MockMultipartFile("file", "demo.zip", "application/zip", new byte[] {1})))
                .isInstanceOf(SourceAnalysisException.class);

        verify(fileStorageService).delete(dir);
    }

    @Test
    void deleteThrowsWhenProjectMissing() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOf(ProjectNotFoundException.class);
    }
}
