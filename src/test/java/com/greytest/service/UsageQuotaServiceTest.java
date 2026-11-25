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

import com.greytest.entity.AuthUser;
import com.greytest.entity.TierPackage;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.TierPackageRepository;
import com.greytest.exception.UsageQuotaExceededException;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.entity.enums.ActivityAction;

class UsageQuotaServiceTest {

    @Test
    void quotaPreflightDoesNotAcquireAWriteLockWhenTheQuotaIsMissing() {
        UsageQuotaRepository repository = mock(UsageQuotaRepository.class);
        when(repository.findByUserId(7L)).thenReturn(Optional.empty());
        UsageQuotaService service = new UsageQuotaService(repository, null, 2, 300, AUGUST, "real");

        service.checkQuotaAvailable(7L);

        org.mockito.Mockito.verify(repository).findByUserId(7L);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findByUserIdForUpdate(any());
    }

    private static final Clock AUGUST = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void downgradeKeepsUsageAfterReloadAndUsesCalendarMonth() {
        UsageQuota paid = new UsageQuota();
        paid.setUserId(7L);
        paid.setQuotaLimit(300);
        paid.setQuotaUsed(120);
        paid.setPeriodStart(LocalDate.of(2026, 8, 20));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(paid);
        UsageQuotaService service = new UsageQuotaService(repository(stored), 100, AUGUST);

        UsageQuota result = service.downgradeToFree(7L);

        assertThat(result.getQuotaLimit()).isEqualTo(100);
        assertThat(result.getQuotaUsed()).isEqualTo(120);
        assertThat(result.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(service.current(7L).getQuotaUsed()).isEqualTo(120);
        assertThatThrownBy(() -> service.consumeLlmCall(7L))
                .isInstanceOf(UsageQuotaExceededException.class);
    }

    @Test
    void downgradeOfPlusKeepsRemainingUsageAndStillResetsNextMonth() {
        UsageQuota paid = new UsageQuota();
        paid.setUserId(7L);
        paid.setQuotaLimit(200);
        paid.setQuotaUsed(25);
        paid.setPeriodStart(LocalDate.of(2026, 8, 20));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(paid);
        UsageQuotaRepository repository = repository(stored);
        UsageQuotaService service = new UsageQuotaService(repository, 100, AUGUST);

        service.downgradeToFree(7L);
        assertThat(service.consumeLlmCall(7L).getQuotaUsed()).isEqualTo(26);

        Clock september = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        assertThat(new UsageQuotaService(repository, 100, september).current(7L).getQuotaUsed()).isZero();
    }

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

    @Test
    void consumesSpecifiedNumberOfUnitsPerCall() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>();
        UsageQuotaRepository repository = repository(stored);
        UsageQuotaService service = new UsageQuotaService(repository, 20, AUGUST);

        UsageQuota quota = service.consumeLlmCall(7L, 11L, 8, java.util.Map.of("prompt", "unit-test"));

        assertThat(quota.getQuotaUsed()).isEqualTo(8);
        assertThat(stored.get().getQuotaUsed()).isEqualTo(8);
    }

    @Test
    void unlimitedQuotaAllowsCallsForRealProvider() {
        UsageQuota unlimited = new UsageQuota();
        unlimited.setUserId(7L);
        unlimited.setQuotaLimit(null);
        unlimited.setQuotaUsed(100);
        unlimited.setPeriodStart(LocalDate.of(2026, 8, 1));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(unlimited);
        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 2, AUGUST, "real");

        UsageQuota result = service.consumeLlmCall(7L);

        assertThat(result.getQuotaUsed()).isEqualTo(101);
        assertThat(result.getQuotaLimit()).isNull();
    }

    @Test
    void updatesQuotaToUnlimited() {
        UsageQuota finite = new UsageQuota();
        finite.setUserId(7L);
        finite.setQuotaLimit(2);
        finite.setQuotaUsed(1);
        finite.setPeriodStart(LocalDate.of(2026, 8, 1));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(finite);
        UsageQuotaService service = new UsageQuotaService(repository(stored), 2, AUGUST);

        UsageQuota result = service.updateLimit(7L, null);

        assertThat(result.getQuotaLimit()).isNull();
        assertThat(result.getQuotaUsed()).isEqualTo(1);
    }

    @Test
    void rejectsNegativeQuotaLimit() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>();
        UsageQuotaService service = new UsageQuotaService(repository(stored), 2, AUGUST);

        assertThatThrownBy(() -> service.updateLimit(7L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("âm");
    }

    @Test
    void checkQuotaAvailablePassesWhenUnderLimit() {
        UsageQuota finite = new UsageQuota();
        finite.setUserId(7L);
        finite.setQuotaLimit(2);
        finite.setQuotaUsed(1);
        finite.setPeriodStart(LocalDate.of(2026, 8, 1));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(finite);
        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 2, 300, AUGUST, "real");

        service.checkQuotaAvailable(7L);
    }

    @Test
    void checkQuotaAvailableThrowsWhenExceeded() {
        UsageQuota finite = new UsageQuota();
        finite.setUserId(7L);
        finite.setQuotaLimit(2);
        finite.setQuotaUsed(2);
        finite.setPeriodStart(LocalDate.of(2026, 8, 1));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(finite);
        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 2, 300, AUGUST, "real");

        assertThatThrownBy(() -> service.checkQuotaAvailable(7L))
                .isInstanceOf(UsageQuotaExceededException.class);
    }

    @Test
    void resetUsageResetsQuotaUsedToZeroAndNormalizesPeriod() {
        UsageQuota finite = new UsageQuota();
        finite.setUserId(7L);
        finite.setQuotaLimit(100);
        finite.setQuotaUsed(95);
        finite.setPeriodStart(LocalDate.of(2026, 8, 15));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(finite);
        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 100, 300, AUGUST, "real");

        UsageQuota reset = service.resetUsage(7L);

        assertThat(reset.getQuotaUsed()).isZero();
        assertThat(reset.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(stored.get().getQuotaUsed()).isZero();
    }

    @Test
    void resetUsagePreservesWeeklyCycleForPlusTier() {
        UsageQuota plus = new UsageQuota();
        plus.setUserId(7L);
        plus.setQuotaLimit(200);
        plus.setQuotaUsed(150);
        plus.setPeriodStart(LocalDate.of(2026, 8, 19)); // 2 ngày trước, trong chu kỳ 1 tuần
        AtomicReference<UsageQuota> stored = new AtomicReference<>(plus);
        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 100, 300, AUGUST, "real");

        UsageQuota reset = service.resetUsage(7L);

        assertThat(reset.getQuotaUsed()).isZero();
        assertThat(reset.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    void proTierResetsOnWeeklyCycle() {
        UsageQuota pro = new UsageQuota();
        pro.setUserId(8L);
        pro.setQuotaLimit(300);
        pro.setQuotaUsed(100);
        // Bắt đầu 10 ngày trước (ngày 11/08), vượt quá 1 tuần
        pro.setPeriodStart(LocalDate.of(2026, 8, 11));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(pro);
        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 100, 200, 300, AUGUST, "real");

        UsageQuota current = service.current(8L);

        // PRO là chu kỳ tuần (300 lượt/tuần), do đó được reset sau 1 tuần
        assertThat(current.getQuotaUsed()).isZero();
        assertThat(current.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    void dynamicLimitsResolvedFromTierPackageRepository() {
        UsageQuota q = new UsageQuota();
        q.setUserId(9L);
        q.setQuotaLimit(100);
        q.setQuotaUsed(10);
        q.setPeriodStart(LocalDate.of(2026, 8, 1));
        AtomicReference<UsageQuota> stored = new AtomicReference<>(q);

        com.greytest.repository.TierPackageRepository tierRepo = mock(com.greytest.repository.TierPackageRepository.class);
        when(tierRepo.findById("PRO")).thenReturn(Optional.of(new com.greytest.entity.TierPackage(
                "PRO", "Gói Pro VIP", 50_000L, "MONTHLY", 500, 5, "VIP", "Desc", true)));
        when(tierRepo.findById("PLUS")).thenReturn(Optional.of(new com.greytest.entity.TierPackage(
                "PLUS", "Gói Plus VIP", 25_000L, "WEEKLY", 250, 3, "Plus", "Desc", true)));
        when(tierRepo.findById("FREE")).thenReturn(Optional.of(new com.greytest.entity.TierPackage(
                "FREE", "Gói Free", 0L, "MONTHLY", 50, 2, "Free", "Desc", true)));

        UsageQuotaService service = new UsageQuotaService(repository(stored), null, 100, 200, 300, AUGUST, "real");
        service.setTierPackageRepository(tierRepo);

        UsageQuota proResult = service.upgradeToPro(9L);
        assertThat(proResult.getQuotaLimit()).isEqualTo(500);

        UsageQuota plusResult = service.upgradeToPlus(9L);
        assertThat(plusResult.getQuotaLimit()).isEqualTo(250);

        UsageQuota freeResult = service.downgradeToFree(9L);
        assertThat(freeResult.getQuotaLimit()).isEqualTo(50);
    }

    @Test
    void monthlyTierPackageKeepsUsageForTheWholeMonth() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>(quota(300, 50, LocalDate.of(2026, 8, 1)));
        UsageQuotaService service = serviceWithTier(stored, UserTier.PRO, "MONTHLY");

        assertThat(service.current(7L).getQuotaUsed()).isEqualTo(50);
    }

    @Test
    void weeklyTierPackageResetsUsageAfterSevenDays() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>(quota(200, 150, LocalDate.of(2026, 8, 10)));
        UsageQuotaService service = serviceWithTier(stored, UserTier.PLUS, "WEEKLY");

        UsageQuota current = service.current(7L);

        assertThat(current.getQuotaUsed()).isZero();
        assertThat(current.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    void adminCanSwitchAFreeTierToAWeeklyCycle() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>(quota(100, 90, LocalDate.of(2026, 8, 13)));
        UsageQuotaService service = serviceWithTier(stored, UserTier.FREE, "WEEKLY");

        assertThat(service.current(7L).getQuotaUsed()).isZero();
    }

    @Test
    void currentReadOnlyRollsOverWithoutLockingOrSaving() {
        UsageQuota stale = quota(100, 42, LocalDate.of(2026, 7, 1));
        UsageQuotaRepository repo = repository(new AtomicReference<>(stale));
        UsageQuotaService service = new UsageQuotaService(repo, 100, AUGUST);

        UsageQuota snapshot = service.currentReadOnly(7L);

        assertThat(snapshot.getQuotaUsed()).isZero();
        assertThat(snapshot.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        verify(repo).findByUserId(7L);
        verify(repo, org.mockito.Mockito.never()).findByUserIdForUpdate(any());
        verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void dailyTierPackageResetsUsageAfterOneDay() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>(quota(50, 40, LocalDate.of(2026, 8, 20)));
        UsageQuotaService service = serviceWithTierAndResetCycle(stored, UserTier.PLUS, "MONTHLY", "DAILY");

        UsageQuota current = service.current(7L);

        assertThat(current.getQuotaUsed()).isZero();
        assertThat(current.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    void dailyTierPackageKeepsUsageOnSameDay() {
        AtomicReference<UsageQuota> stored = new AtomicReference<>(quota(50, 30, LocalDate.of(2026, 8, 21)));
        UsageQuotaService service = serviceWithTierAndResetCycle(stored, UserTier.PLUS, "MONTHLY", "DAILY");

        UsageQuota current = service.current(7L);

        assertThat(current.getQuotaUsed()).isEqualTo(30);
        assertThat(current.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    private UsageQuotaService serviceWithTierAndResetCycle(AtomicReference<UsageQuota> stored, UserTier tier, String cycle, String resetCycle) {
        UsageQuotaService service = new UsageQuotaService(repository(stored), 100, AUGUST);
        AuthUser user = new AuthUser();
        user.setTier(tier);
        AuthUserRepository users = mock(AuthUserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        TierPackageRepository packages = mock(TierPackageRepository.class);
        when(packages.findById(tier.name())).thenReturn(Optional.of(
                new TierPackage(tier.name(), tier.name(), 0L, cycle, 300, 2, "", "", true, resetCycle)));
        service.setUserRepository(users);
        service.setTierPackageRepository(packages);
        return service;
    }

    private UsageQuotaService serviceWithTier(AtomicReference<UsageQuota> stored, UserTier tier, String cycle) {
        return serviceWithTierAndResetCycle(stored, tier, cycle, cycle);
    }

    private static UsageQuota quota(int limit, int used, LocalDate periodStart) {
        UsageQuota quota = new UsageQuota();
        quota.setUserId(7L);
        quota.setQuotaLimit(limit);
        quota.setQuotaUsed(used);
        quota.setPeriodStart(periodStart);
        return quota;
    }

    private UsageQuotaRepository repository(AtomicReference<UsageQuota> stored) {
        UsageQuotaRepository repository = mock(UsageQuotaRepository.class);
        when(repository.findByUserId(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.findByUserIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(invocation -> {
            UsageQuota quota = invocation.getArgument(0);
            stored.set(quota);
            return quota;
        });
        return repository;
    }
}
