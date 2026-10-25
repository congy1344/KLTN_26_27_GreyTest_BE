package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.SourceRevision;
import com.greytest.repository.SourceRevisionRepository;
import com.greytest.service.GenerationJobContext;

class PersistentSourceAnalysisServiceTest {
    @Test void reusesDiffAstWithoutASecondScannerPassAndRejectsConcurrentSourceChanges(@TempDir Path root) throws Exception {
        var source=root.resolve("Service.java"); Files.writeString(source,"class Service { int value(){ return 42; } }");
        var repository=mock(SourceRevisionRepository.class);
        var revision=new SourceRevision(); revision.setId(5L); revision.setProjectId(1L); revision.setStoragePath(root.toString());
        when(repository.findByStoragePath(root.toString())).thenReturn(List.of(revision));
        when(repository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(revision));
        var service=new PersistentSourceAnalysisService(repository,new ObjectMapper());
        var digest=PersistentSourceAnalysisService.contentDigest(root);
        var scan=new com.greytest.service.analysis.JavaParserHelper().scanProject(root);
        try(var scope=GenerationJobContext.openScope(null)) {
            service.rememberScan(root,scan,digest);
            assertThat(service.load(1L,root,true,digest).classes().get(0).methods().get(0).sourceCode()).contains("return 42");
            assertThat(GenerationJobContext.metrics()).containsEntry("analysisDiffReused",1L).containsEntry("analysisPersistentHits",1L)
                .doesNotContainKey("analysisParseEvents");
        }
        Files.writeString(source,"class Service { int value(){ return 43; } }");
        assertThatThrownBy(()->service.rememberScan(root,scan,digest)).isInstanceOf(LlmResponseException.class).hasMessageContaining("changed while analyzing");
    }
    @Test
    void remapsPersistedSourceToCurrentClassAndMethodIds(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Service.java"), "class Service { int value() { return 2; } }");
        var parsed = SnapshotAnalysisLoader.parse(root, true);
        for (long currentId : List.of(10L, 20L)) {
            var method = new com.greytest.dto.JavaMethodDto(currentId + 1, "value", "int", List.of(), List.of(),
                    "PACKAGE_PRIVATE", "int value() { return 1; }", 1, 1, List.of(), List.of());
            var owner = new com.greytest.dto.JavaClassDto(currentId, "", "Service", "Service", "OTHER", "Service.java", "old", List.of(), List.of(method));
            var stored = new com.greytest.dto.AnalysisResultDto(1L, "fixture", "ANALYZED", 1, 1, 0, 0, 0, 0,
                    1, 1, 0, List.of(), List.of(owner), List.of(), List.of());
            var result = SnapshotAnalysisLoader.remap(stored, parsed);
            assertThat(result.classes().get(0).id()).isEqualTo(currentId);
            assertThat(result.classes().get(0).methods().get(0).id()).isEqualTo(currentId + 1);
            assertThat(result.classes().get(0).methods().get(0).sourceCode()).contains("return 2");
        }
    }
    @Test
    void persistsSourceOnlyAndReusesAcrossInstancesButInvalidatesChangedOrCorruptSource(@TempDir Path root) throws Exception {
        Path source = root.resolve("Service.java");
        Files.writeString(source, "class Service { int value() { return 1; } }");
        var revisions = mock(SourceRevisionRepository.class);
        var revision = new SourceRevision(); revision.setId(5L); revision.setProjectId(1L); revision.setStoragePath(root.toString());
        when(revisions.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(revision));
        var mapper = new ObjectMapper();
        var service = new PersistentSourceAnalysisService(revisions, mapper);
        String digest = PersistentSourceAnalysisService.contentDigest(root);
        try (var scope = GenerationJobContext.openScope(null)) {
            var parsed = service.load(1L, root, true, digest);
            assertThat(parsed.classes()).hasSize(1);
            assertThat(parsed.classes().get(0).id()).isNull();
            assertThat(parsed.classes().get(0).methods().get(0).id()).isNull();
            assertThat(GenerationJobContext.metrics()).containsEntry("analysisParseEvents", 1L);
            assertThat(revision.getAnalysisSnapshot()).isNotBlank();
        }
        try (var scope = GenerationJobContext.openScope(null)) {
            var restored = new PersistentSourceAnalysisService(revisions, mapper).load(1L, root, true, digest);
            assertThat(restored.classes().get(0).methods().get(0).sourceCode()).contains("return 1");
            assertThat(GenerationJobContext.metrics()).doesNotContainKey("analysisParseEvents").containsEntry("analysisPersistentHits", 1L);
        }
        Files.writeString(source, "class Service { int value() { return 2; } }");
        String changed = PersistentSourceAnalysisService.contentDigest(root);
        assertThat(changed).isNotEqualTo(digest);
        assertThat(service.load(1L, root, true, changed).classes().get(0).sourceCode()).contains("return 2");
        revision.setAnalysisSnapshot("{\"formatVersion\":1,\"sourceDigest\":\"wrong\"}");
        assertThat(service.load(1L, root, true, changed).classes()).hasSize(1);
        Files.writeString(source, "class Broken { invalid java");
        assertThatThrownBy(() -> service.load(1L, root, true, PersistentSourceAnalysisService.contentDigest(root)))
                .isInstanceOf(LlmResponseException.class);
    }
}
