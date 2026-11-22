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
    void concurrentFirstCallsOfANewUserShareOneQuotaRow() throws Exception {
        // Các batch đầu của một lượt sinh cùng trừ quota khi user chưa có dòng usage_quota: không được đụng unique user_id.
        int callers = 6;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> { start.await(); return service.consumeLlmCall(user.getId(), null, 1, Map.of()); }));
            }
            start.countDown();
            for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(quotas.findByUserId(user.getId())).get().extracting("quotaUsed").isEqualTo(callers);
    }

    @Test
    void aCallWhileAnotherTransactionCreatesTheRowDoesNotHitTheUniqueUserId() throws Exception {
        // Transaction ngoài (đọc quota ở màn hình) tạo dòng nhưng chưa commit trong lúc batch AI trừ quota bằng REQUIRES_NEW.
        var created = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                service.current(user.getId());
                created.countDown();
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }));
            created.await(10, java.util.concurrent.TimeUnit.SECONDS);
            var consumer = pool.submit(() -> service.consumeLlmCall(user.getId(), null, 1, Map.of()));
            Thread.sleep(500);
            release.countDown();
            holder.get(30, java.util.concurrent.TimeUnit.SECONDS);
            consumer.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(quotas.findByUserId(user.getId())).get().extracting("quotaUsed").isEqualTo(1);
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
}
