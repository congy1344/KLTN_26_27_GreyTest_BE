package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.greytest.dto.UserQuotaResponse;
import com.greytest.entity.AuthUser;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;

class UserServiceTest {

    private AuthUserRepository authUserRepository;
    private UsageQuotaService usageQuotaService;
    private UserActivityService userActivityService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        authUserRepository = mock(AuthUserRepository.class);
        usageQuotaService = mock(UsageQuotaService.class);
        userActivityService = mock(UserActivityService.class);
        userService = new UserService(authUserRepository, usageQuotaService, userActivityService);
    }

    @Test
    void upgradesUserToProAndSetsQuotaTo300() {
        AuthUser user = new AuthUser();
        user.setId(42L);
        user.setEmail("user@example.com");
        user.setRole(UserRole.USER);
        user.setTier(UserTier.FREE);

        when(authUserRepository.findById(42L)).thenReturn(Optional.of(user));

        UsageQuota proQuota = new UsageQuota();
        proQuota.setUserId(42L);
        proQuota.setQuotaLimit(300);
        proQuota.setQuotaUsed(100);
        proQuota.setPeriodStart(LocalDate.of(2026, 10, 1));

        when(usageQuotaService.upgradeToTier(42L, UserTier.PRO)).thenReturn(proQuota);
        when(usageQuotaService.getProLimit()).thenReturn(300);

        UserQuotaResponse response = userService.upgradeToPro(user);

        assertThat(user.getTier()).isEqualTo(UserTier.PRO);
        assertThat(response.tier()).isEqualTo(UserTier.PRO);
        assertThat(response.limit()).isEqualTo(300);
        assertThat(response.used()).isEqualTo(100);
        assertThat(response.remaining()).isEqualTo(200);
        assertThat(response.resetDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(response.exceeded()).isFalse();

        verify(authUserRepository).save(user);
        verify(usageQuotaService).upgradeToTier(42L, UserTier.PRO);
        verify(userActivityService).record(eq(42L), eq(ActivityAction.USER_UPGRADE_TIER), any(), any());
    }

    @Test
    void upgradesUserToPlusAndSetsQuota() {
        AuthUser user = new AuthUser();
        user.setId(42L);
        user.setEmail("user@example.com");
        user.setRole(UserRole.USER);
        user.setTier(UserTier.FREE);

        when(authUserRepository.findById(42L)).thenReturn(Optional.of(user));

        UsageQuota plusQuota = new UsageQuota();
        plusQuota.setUserId(42L);
        plusQuota.setQuotaLimit(200);
        plusQuota.setQuotaUsed(50);
        plusQuota.setPeriodStart(LocalDate.of(2026, 10, 1));

        when(usageQuotaService.upgradeToTier(42L, UserTier.PLUS)).thenReturn(plusQuota);

        UserQuotaResponse response = userService.upgradeToTier(user, UserTier.PLUS);

        assertThat(user.getTier()).isEqualTo(UserTier.PLUS);
        assertThat(response.tier()).isEqualTo(UserTier.PLUS);
        assertThat(response.limit()).isEqualTo(200);
        assertThat(response.used()).isEqualTo(50);
        assertThat(response.remaining()).isEqualTo(150);

        verify(authUserRepository).save(user);
        verify(usageQuotaService).upgradeToTier(42L, UserTier.PLUS);
    }

    @Test
    void returnsCurrentQuotaForUser() {
        AuthUser user = new AuthUser();
        user.setId(42L);
        user.setTier(UserTier.FREE);

        when(authUserRepository.findById(42L)).thenReturn(Optional.of(user));

        UsageQuota freeQuota = new UsageQuota();
        freeQuota.setUserId(42L);
        freeQuota.setQuotaLimit(100);
        freeQuota.setQuotaUsed(100);
        freeQuota.setPeriodStart(LocalDate.of(2026, 10, 1));

        when(usageQuotaService.current(42L)).thenReturn(freeQuota);

        UserQuotaResponse response = userService.currentQuota(user);

        assertThat(response.tier()).isEqualTo(UserTier.FREE);
        assertThat(response.limit()).isEqualTo(100);
        assertThat(response.used()).isEqualTo(100);
        assertThat(response.remaining()).isEqualTo(0);
        assertThat(response.exceeded()).isTrue();
    }
}
