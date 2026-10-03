package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.repository.AuthUserRepository;

/** Kiểm chứng khóa admin trên PostgreSQL thật; chỉ thay đổi hai user fixture của test. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminSafetyConcurrencyIntegrationTest {

    @Autowired private AuthUserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    private AuthUser firstAdmin;
    private AuthUser secondAdmin;

    @BeforeEach
    void createAdmins() {
        firstAdmin = createAdmin("first");
        secondAdmin = createAdmin("second");
    }

    @AfterEach
    void cleanUp() {
        if (secondAdmin != null) users.deleteById(secondAdmin.getId());
        if (firstAdmin != null) users.deleteById(firstAdmin.getId());
    }

    @Test
    void blocksAnotherAdminRemovalAndReadsCommittedEnabledAdmins() throws Exception {
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                users.findEnabledAdminsForUpdate();
                AuthUser target = users.findById(secondAdmin.getId()).orElseThrow();
                target.setEnabled(false);
                users.saveAndFlush(target);
                firstHasLock.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transaction.execute(status -> {
                secondStarted.countDown();
                return users.findEnabledAdminsForUpdate().stream().map(AuthUser::getId).toList();
            }));
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);

            List<Long> remaining = second.get(5, TimeUnit.SECONDS);
            assertThat(remaining).contains(firstAdmin.getId()).doesNotContain(secondAdmin.getId());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AuthUser createAdmin(String suffix) {
        AuthUser user = new AuthUser();
        user.setEmail("admin-safety-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setPasswordHash("not-used");
        user.setRole(UserRole.ADMIN);
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
