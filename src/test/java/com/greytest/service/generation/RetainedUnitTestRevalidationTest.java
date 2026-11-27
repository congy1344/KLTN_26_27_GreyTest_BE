package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.entity.*;
import com.greytest.entity.enums.*;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.*;
import com.greytest.service.SourceUpdateService;
import com.greytest.service.agent.*;
import com.greytest.service.diff.*;
import com.greytest.service.storage.SourceRevisionStorageService;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

class RetainedUnitTestRevalidationTest {
    private static final String FILE = "src/main/java/demo/Service.java";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void applyExportsUnchangedMethodAndLeavesChangedDependenciesForRegeneration(boolean sourceOnly, @TempDir Path root) throws Exception {
        var base = revision(root.resolve("base"), 10L, 1);
        Path newRoot = root.resolve("active");
        Path logical = sourceOnly ? newRoot.resolve("uploaded-project") : newRoot;
        var active = revision(logical, 11L, 2);
        active.setLogicalRoot(logical.toString());
        active.setStoragePath(newRoot.toString());
        Path oldRoot = Path.of(base.getStoragePath());
        var agent = mock(AIAgentService.class);
        var jdbc = mock(JdbcTemplate.class);
        var json = new ObjectMapper();
        var prompts = mock(PromptManager.class);
        when(prompts.load(anyString())).thenReturn("prompt");
        var fingerprints = new GenerationFingerprintService(json, prompts, LlmStageTokenLimits.defaults(), new MockEnvironment());
        var provenance = new UnitTestExportProvenanceService(jdbc, agent, fingerprints, json);
        var tests = mock(UnitTestRepository.class);
        var revisions = mock(SourceRevisionRepository.class);
        var projects = mock(ProjectRepository.class);
        Project project = new Project();
        project.setId(1L);
        project.setOwnerUserId(1L);
        project.setActiveSourceRevisionId(base.getId());
        var listener = new RetainedUnitTestRevalidation(tests, revisions, projects,
                new StorageWorkspaceService(root.toString(), null, projects), provenance, mock(PlatformTransactionManager.class));
        when(revisions.findById(10L)).thenReturn(Optional.of(base));
        when(revisions.findById(11L)).thenReturn(Optional.of(active));
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        var changed = test(1L, "a", "a", oldRoot, agent, tests);
        var dependency = test(2L, "b", "a", oldRoot, agent, tests);
        var invalid = test(3L, "b", "b", oldRoot, agent, tests);
        var kept = test(4L, "b", "b", oldRoot, agent, tests);
        when(tests.findStampedIdsByProjectId(1L)).thenReturn(List.of(1L, 2L, 3L, 4L));
        doThrow(new IllegalArgumentException("validator rejected")).when(agent).validateExistingUnitTest(1L, invalid, newRoot);
        var updates = mock(SourceUpdateRepository.class);
        var update = new SourceUpdate();
        update.setId(9L);
        update.setProjectId(1L);
        update.setBaseRevisionId(10L);
        update.setCandidateRevisionId(11L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        when(updates.findByIdAndProjectId(9L, 1L)).thenReturn(Optional.of(update));
        when(updates.save(update)).thenReturn(update);
        var apply = new SourceUpdateService(projects, revisions, updates, mock(SourceUpdateItemRepository.class),
                mock(SourceRevisionStorageService.class), new SourceUpdateMapper(), mock(SourceDiffService.class),
                mock(ImpactAnalysisService.class), mock(BusinessRuleRepository.class), mock(TestPlanRepository.class),
                mock(TestCaseRepository.class), tests, mock(JavaClassRepository.class), mock(JavaMethodRepository.class),
                json, mock(TestPlanCoveredRuleRepository.class));
        ApplicationEventPublisher publisher = event -> listener.afterApply((RetainedUnitTestRevalidation.SourceApplied) event);
        ReflectionTestUtils.setField(apply, "events", publisher);
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(UserRole.USER);

        var result = sourceOnly ? apply.applySourceChanges(1L, 9L, user) : apply.applyUpdate(1L, 9L, user);
        assertThat(result.status()).isEqualTo(SourceUpdateStatus.APPLIED);
        verify(agent).validateExistingUnitTest(1L, kept, newRoot);
        verify(agent).validateExistingUnitTest(1L, invalid, newRoot);
        assertThat(provenance.prepare(1L, List.of(kept), newRoot)).containsExactly(kept);
        verifyNoInteractions(jdbc);
        String oldHash = GenerationSourceFingerprint.hash(Files.readString(oldRoot.resolve(FILE)));
        String newHash = GenerationSourceFingerprint.hash(Files.readString(logical.resolve(FILE)));
        assertThat(kept.getSourceCode()).startsWith(GenerationSourceFingerprint.PREFIX + newHash)
                .contains("// GREYTEST_ORIGINAL_SOURCE_V1 " + oldHash, "// GREYTEST_REVALIDATED_V1 applied_update=9");
        for (var stale : List.of(changed, dependency, invalid)) {
            assertThat(stale.getSourceCode()).startsWith(GenerationSourceFingerprint.PREFIX + oldHash)
                    .doesNotContain("GREYTEST_REVALIDATED");
            assertThatThrownBy(() -> provenance.prepareValidated(1L, List.of(stale), newRoot))
                    .hasMessageContaining("được sinh từ phiên bản source cũ");
        }
    }

    @Test
    void revalidatesEachCaseInMergedTestClass(@TempDir Path root) throws Exception {
        Path base = root.resolve("base");
        Path active = root.resolve("active");
        String service = """
                package demo;
                public final class Service {
                    public int create() { return 7; }
                    public Integer findById(Integer id) { return id + 1; }
                }
                """;
        Files.createDirectories(base.resolve(FILE).getParent());
        Files.createDirectories(active.resolve(FILE).getParent());
        Files.writeString(base.resolve(FILE), service);
        Files.writeString(active.resolve(FILE), service.replace("return id + 1;", "if (id == null) return null; return id + 1;"));
        var agent = mock(AIAgentService.class);
        var prompts = mock(PromptManager.class);
        when(prompts.load(anyString())).thenReturn("prompt");
        var json = new ObjectMapper();
        var fingerprints = new GenerationFingerprintService(json, prompts, LlmStageTokenLimits.defaults(), new MockEnvironment());
        var provenance = new UnitTestExportProvenanceService(mock(JdbcTemplate.class), agent, fingerprints, json);
        var tests = mock(UnitTestRepository.class);
        // Cùng input ổn định để chỉ dependency của output quyết định dấu của từng case.
        var create = test(33L, "create", "create", base, agent, tests);
        var find = test(34L, "create", "create", base, agent, tests);
        String merged = GenerationSourceFingerprint.aggregate(List.of(create.getSourceCode())) + """
                package demo;
                class ServiceTest {
                    Service service = new Service();
                    @org.junit.jupiter.api.Test void createsUser() { service.create(); }
                    @org.junit.jupiter.api.Test void findsUser() { lookup(); }
                    void lookup() { lookupById(); }
                    void lookupById() { service.findById(1); }
                }
                """;
        create.setTestMethodName("createsUser");
        find.setTestMethodName("findsUser");
        create.setSourceCode(merged);
        find.setSourceCode(merged);

        provenance.revalidateRetained(1L, 9L, create, base, active, active);
        provenance.revalidateRetained(1L, 9L, find, base, active, active);

        assertThat(create.getSourceCode()).startsWith(GenerationSourceFingerprint.PREFIX
                + GenerationSourceFingerprint.hash(Files.readString(active.resolve(FILE))))
                .contains("GREYTEST_REVALIDATED_V1 applied_update=9", "void findsUser()", "service.findById(1)");
        assertThat(find.getSourceCode()).isEqualTo(merged);
    }

    /** Hai revision chỉ khác nhau ở thân method a. */
    private SourceRevision revision(Path root, long id, int value) throws Exception {
        Files.createDirectories(root.resolve(FILE).getParent());
        Files.writeString(root.resolve(FILE), "package demo; public final class Service { public int a() { return "
                + value + "; } public int b() { return 7; } }");
        SourceRevision revision = new SourceRevision();
        revision.setStoragePath(root.toString());
        revision.setId(id);
        return revision;
    }

    /** Dùng fingerprint thật cho method của case và API mà test gọi. */
    private UnitTest test(long id, String target, String call, Path root, AIAgentService agent, UnitTestRepository tests) {
        var method = new MethodContextDto(id, "demo.Service", target, "int", List.of(), List.of(), "public", "",
                1, 1, List.of(), List.of(), List.of());
        var owner = new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE", FILE, "", List.of(), List.of(method));
        var context = new UnitTestContextDto(new ProjectContextDto(1L, "demo", "TEST_GENERATED"), null, List.of(owner),
                List.of(new BusinessRuleContextDto(id, id, "BR-" + id, target, null, "AI", "APPROVED", false, null)),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(agent.snapshotContext(eq(GenerationProgressStage.UNIT_TEST), eq(1L), eq(Set.of(id)), any())).thenReturn(context);
        var test = new UnitTest();
        test.setId(id);
        test.setTestCaseId(id);
        test.setTestClassName("ServiceTest");
        test.setTestMethodName("exercise");
        test.setSourceCode(GenerationSourceFingerprint.capture(root, List.of(owner))
                + "package demo; class ServiceTest { @org.junit.jupiter.api.Test void exercise() { "
                + "org.junit.jupiter.api.Assertions.assertEquals(" + (call.equals("a") ? 1 : 7)
                + ", new Service()." + call + "()); } }");
        when(tests.findByIdForUpdate(id)).thenReturn(Optional.of(test));
        return test;
    }
}
