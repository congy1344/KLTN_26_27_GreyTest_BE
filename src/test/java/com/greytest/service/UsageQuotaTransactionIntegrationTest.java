package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
class UsageQuotaTransactionIntegrationTest {

    @Autowired private UsageQuotaService service;
    @Autowired private UserService userService;
    @Autowired private UsageQuotaRepository quotas;
    @Autowired private UserActivityLogRepository activities;
    @Autowired private AuthUserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    private AuthUser user;

    @BeforeEach
    void createUser() {
        user = new AuthUser();
        user.setEmail("quota-transaction-" + System.nanoTime() + "@test.local");
        user.setFullName("Quota Transaction Test");
        user.setPasswordHash("not-used");
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        user = users.save(user);
    }

    @AfterEach
    void cleanUp() {
        users.deleteById(user.getId());
    }

    @Test
    void cancellationPersistsFreeTierQuotaAndAuditTogether() {
        user.setTier(UserTier.PLUS);
        users.save(user);
        service.upgradeToPlus(user.getId());
        service.consumeLlmCall(user.getId(), null, 25, Map.of());

        var result = userService.cancelSubscription(user);

        assertThat(result.tier()).isEqualTo(UserTier.FREE);
        assertThat(result.used()).isEqualTo(25);
        assertThat(users.findById(user.getId())).get().extracting("tier").isEqualTo(UserTier.FREE);
        assertThat(userService.currentQuota(user).used()).isEqualTo(25);
        assertThat(quotas.findByUserId(user.getId())).get().extracting("quotaUsed").isEqualTo(25);
        assertThat(activities.countByUserId(user.getId())).isEqualTo(2);
    }

    @Test
    void concurrentConsumeLlmCallOnNewUserCreatesSingleRowAndCountsEveryCall() throws Exception {
        assertThat(quotas.findByUserId(user.getId())).isEmpty();

        int threads = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<com.greytest.entity.UsageQuota>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    return service.consumeLlmCall(user.getId(), null, 1, Map.of());
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (java.util.concurrent.Future<com.greytest.entity.UsageQuota> future : futures) {
                assertThat(future.get(15, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(quotas.findByUserId(user.getId())).get().extracting("quotaUsed").isEqualTo(threads);
        assertThat(activities.countByUserId(user.getId())).isEqualTo((long) threads);
    }
}
