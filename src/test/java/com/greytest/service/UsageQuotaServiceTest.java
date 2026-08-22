package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.greytest.entity.UsageQuota;
import com.greytest.exception.UsageQuotaExceededException;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.entity.enums.ActivityAction;

class UsageQuotaServiceTest {

    private static final Clock AUGUST = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsDefaultMonthlyQuotaAndCountsEachLlmCall() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>();
        UsageQuotaRepository repository = repository(stored);
        UsageQuotaService service = new UsageQuotaService(repository, 2, AUGUST);

        UsageQuota first = service.consumeLlmCall(7L);
        UsageQuota second = service.consumeLlmCall(7L);

        assertThat(first.getQuotaLimit()).isEqualTo(2);
        assertThat(second.getQuotaUsed()).isEqualTo(2);
        assertThat(second.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThatThrownBy(() -> service.consumeLlmCall(7L))
                .isInstanceOf(UsageQuotaExceededException.class)
                .hasMessageContaining("quota");
    }

    @Test
    void resetsUsageWhenAnewMonthStarts() {
        UsageQuota old = new UsageQuota();
        old.setUserId(9L);
        old.setQuotaLimit(5);
        old.setQuotaUsed(5);
        old.setPeriodStart(LocalDate.of(2026, 7, 1));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(old);
        UsageQuotaService service = new UsageQuotaService(repository(stored), 100, AUGUST);

        UsageQuota quota = service.consumeLlmCall(9L);

        assertThat(quota.getQuotaUsed()).isEqualTo(1);
        assertThat(quota.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void reservesQuotaAndPersistsLlmActivityInOneServiceCall() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>();
        UserActivityLogRepository activities = mock(UserActivityLogRepository.class);
        UsageQuotaService service = new UsageQuotaService(repository(stored), activities, 3, AUGUST);

        service.consumeLlmCall(7L, 11L, java.util.Map.of("prompt", "test-plan"));

        verify(activities).save(org.mockito.ArgumentMatchers.argThat(activity ->
                activity.getUserId().equals(7L)
                        && activity.getRelatedProjectId().equals(11L)
                        && activity.getActionType() == ActivityAction.LLM_CALL));
        assertThat(stored.get().getQuotaUsed()).isEqualTo(1);
    }

    private UsageQuotaRepository repository(AtomicReference<UsageQuota> stored) {
        UsageQuotaRepository repository = mock(UsageQuotaRepository.class);
        when(repository.findByUserIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(invocation -> {
            UsageQuota quota = invocation.getArgument(0);
            stored.set(quota);
            return quota;
        });
        return repository;
    }
}
