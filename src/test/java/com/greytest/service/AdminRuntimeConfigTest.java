package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.greytest.dto.admin.AdminDtos.RateLimitConfigRequest;
import com.greytest.dto.admin.AdminDtos.UpdateAiKeysRequest;
import com.greytest.dto.admin.AdminDtos.UpdateAiModelRequest;
import com.greytest.dto.admin.AdminDtos.UpdateApiKeySlotRequest;
import com.greytest.dto.admin.AdminDtos.UpdateStageLimitsRequest;
import com.greytest.dto.admin.AdminDtos.UpdateSystemSettingsRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.Project;
import com.greytest.entity.SystemSetting;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SystemSettingRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.service.agent.LlmStageTokenLimits;
import com.greytest.service.agent.OpenAiLlmClient;

/** Cấu hình admin sửa phải còn nguyên khi backend khởi động lại (sau deploy). */
class AdminRuntimeConfigTest {

    private final Map<String, SystemSetting> rows = new ConcurrentHashMap<>();
    private SystemSettingRepository settings;
    private ProjectRepository projects;
    private final AuthUser admin = new AuthUser();

    @BeforeEach
    void setUp() {
        admin.setId(1L);
        settings = mock(SystemSettingRepository.class);
        when(settings.findById(any())).thenAnswer(call -> Optional.ofNullable(rows.get(call.<String>getArgument(0))));
        when(settings.findAll()).thenAnswer(call -> List.copyOf(rows.values()));
        when(settings.save(any())).thenAnswer(call -> {
            SystemSetting row = call.getArgument(0);
            rows.put(row.getSettingKey(), row);
            return row;
        });
        projects = mock(ProjectRepository.class);
    }

    @Test
    void everyRuntimeChangeSurvivesARestart() {
        Runtime before = new Runtime();
        before.admin.updateAiKeys(admin, new UpdateAiKeysRequest(List.of(
                new UpdateApiKeySlotRequest(0, "sk-primary-0000000001"),
                new UpdateApiKeySlotRequest(2, "sk-rotation-000000002"))));
        before.admin.updateStageLimits(admin, new UpdateStageLimitsRequest(Map.of("unit-test", 20000)));
        before.admin.updateAiModel(admin, new UpdateAiModelRequest("gpt-5-mini", null));
        before.admin.updateSystemSettings(admin, new UpdateSystemSettingsRequest(Map.of("ai.batch_concurrency", "3")));
        before.admin.updateRateLimitConfig(admin, new RateLimitConfigRequest(true, true, 80, 20, 50));

        Runtime after = new Runtime();
        after.admin.restoreRuntimeConfig();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.captor();
        verify(after.openAi).setApiKeys(keys.capture());
        assertThat(keys.getValue()).containsExactly("sk-primary-0000000001", "sk-rotation-000000002");
        verify(after.openAi).setModel("gpt-5-mini");
        assertThat(after.admin.getAiRuntimeConfig().model()).isEqualTo("gpt-5-mini");
        assertThat(after.limits.getLimits()).containsEntry("unit-test", 20000);
        assertThat(after.executor.getConcurrency()).isEqualTo(3);
        var waf = after.ddos.getWafStatus();
        assertThat(waf.underAttackMode()).isTrue();
        assertThat(waf.standardRateLimitPerMinute()).isEqualTo(80);
        assertThat(waf.underAttackRateLimitPerMinute()).isEqualTo(20);
        assertThat(waf.burstLimitPerSecond()).isEqualTo(50);
    }

    @Test
    void seededDefaultsDoNotOverrideTheModelFromTheEnvironment() {
        // Migration V30 chèn sẵn ai.model = gpt-4o-mini mà không có updated_by; đó không phải lựa chọn của admin.
        rows.put("ai.model", new SystemSetting("ai.model", "gpt-4o-mini", "seed"));
        rows.put("ai.batch_concurrency", new SystemSetting("ai.batch_concurrency", "2", "seed"));
        Runtime runtime = new Runtime();

        runtime.admin.restoreRuntimeConfig();

        verify(runtime.openAi, never()).setModel(any());
        assertThat(runtime.executor.getConcurrency()).isEqualTo(4);
    }

    @Test
    void settingsPageShowsTheModelAndConcurrencyActuallyInUse() {
        rows.put("ai.model", new SystemSetting("ai.model", "gpt-4o-mini", "seed"));
        rows.put("ai.batch_concurrency", new SystemSetting("ai.batch_concurrency", "2", "seed"));
        Runtime runtime = new Runtime();
        ReflectionTestUtils.setField(runtime.admin, "llmModel", "gpt-6-luna");
        runtime.admin.restoreRuntimeConfig();

        var listed = runtime.admin.getSystemSettings();

        assertThat(listed.settings()).containsEntry("ai.model", "gpt-6-luna").containsEntry("ai.batch_concurrency", "4");
        assertThat(listed.details()).filteredOn(row -> row.key().equals("ai.model"))
                .allMatch(row -> row.value().equals("gpt-6-luna"));
    }

    @Test
    void wafChangesWithoutAnAdminContextAreStillRestored() {
        new Runtime().admin.setUnderAttackMode(true);

        Runtime after = new Runtime();
        after.admin.restoreRuntimeConfig();

        assertThat(after.ddos.getWafStatus().underAttackMode()).isTrue();
    }

    @Test
    void storesApiKeysEncryptedAndNeverListsInternalRows() {
        Runtime runtime = new Runtime();
        runtime.admin.updateAiKeys(admin, new UpdateAiKeysRequest(List.of(
                new UpdateApiKeySlotRequest(0, "sk-primary-0000000001"))));

        assertThat(rows.get("runtime.ai.api_keys").getSettingValue())
                .startsWith("v1:").doesNotContain("sk-primary");
        var listed = runtime.admin.getSystemSettings();
        assertThat(listed.settings()).doesNotContainKeys("runtime.ai.api_keys");
        assertThat(listed.details()).noneMatch(row -> row.key().startsWith("runtime."));
    }

    @Test
    void refusesToOverwriteInternalRowsThroughTheGenericSettingsForm() {
        Runtime runtime = new Runtime();

        assertThatThrownBy(() -> runtime.admin.updateSystemSettings(admin,
                new UpdateSystemSettingsRequest(Map.of("runtime.ai.api_keys", "plain"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows).doesNotContainKey("runtime.ai.api_keys");
    }

    @Test
    void rejectsBatchConcurrencyOutsideTheSupportedRange() {
        Runtime runtime = new Runtime();

        assertThatThrownBy(() -> runtime.admin.updateSystemSettings(admin,
                new UpdateSystemSettingsRequest(Map.of("ai.batch_concurrency", "0"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runtime.executor.getConcurrency()).isEqualTo(4);
    }

    @Test
    void doesNotApplyKeysThatCannotBeSavedDurably() {
        Runtime runtime = new Runtime(new SettingsCipher(""));

        assertThatThrownBy(() -> runtime.admin.updateAiKeys(admin, new UpdateAiKeysRequest(List.of(
                new UpdateApiKeySlotRequest(0, "sk-primary-0000000001")))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("GREYTEST_SETTINGS_SECRET");
        verify(runtime.openAi, never()).setApiKeys(any());
    }

    @Test
    void dropsTheRetiredSettingsFromTheSettingsPage() {
        rows.put("log.output_dir", new SystemSetting("log.output_dir", "../log", ""));
        Runtime runtime = new Runtime();

        assertThat(runtime.admin.getSystemSettings().settings())
                .doesNotContainKeys("log.output_dir", "alert.quota_threshold_percent")
                .containsKeys("vnpay.tmn_code", "vnpay.url");
    }

    @Test
    void retryRunsTheStageAgainForTheProjectOwner() {
        Runtime runtime = new Runtime();
        Project project = new Project();
        project.setName("course-registration");
        when(projects.findById(7L)).thenReturn(Optional.of(project));

        runtime.admin.retryJob(admin, 7L, GenerationProgressStage.TEST_PLAN);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(runtime.jobs).submit(eq(7L), eq(GenerationProgressStage.TEST_PLAN), task.capture());
        task.getValue().run();
        verify(runtime.testPlans).generate(7L, null, true);
    }

    /** Một "lần chạy" backend: AdminService cùng các thành phần runtime mới tinh, dùng chung DB. */
    private final class Runtime {
        final OpenAiLlmClient openAi = mock(OpenAiLlmClient.class);
        final LlmStageTokenLimits limits = LlmStageTokenLimits.defaults();
        final DdosProtectionService ddos = new DdosProtectionService();
        final LlmBatchExecutor executor = new LlmBatchExecutor(4);
        final GenerationJobService jobs = mock(GenerationJobService.class);
        final TestPlanService testPlans = mock(TestPlanService.class);
        final AdminService admin;

        Runtime() {
            this(new SettingsCipher("test-settings-secret"));
        }

        Runtime(SettingsCipher cipher) {
            admin = new AdminService(mock(AuthUserRepository.class), projects, mock(UnitTestRepository.class),
                    mock(UserActivityLogRepository.class), mock(UsageQuotaRepository.class),
                    mock(UsageQuotaService.class), mock(UserActivityService.class), null, null, null, null);
            ReflectionTestUtils.setField(admin, "systemSettingRepository", settings);
            ReflectionTestUtils.setField(admin, "settingsCipher", cipher);
            ReflectionTestUtils.setField(admin, "openAiLlmClient", openAi);
            ReflectionTestUtils.setField(admin, "stageTokenLimits", limits);
            ReflectionTestUtils.setField(admin, "ddosProtectionService", ddos);
            ReflectionTestUtils.setField(admin, "llmBatchExecutor", executor);
            ReflectionTestUtils.setField(admin, "generationJobService", jobs);
            ReflectionTestUtils.setField(admin, "testPlanService", testPlans);
        }
    }
}
