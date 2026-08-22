package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
class UsageQuotaTransactionIntegrationTest {

    @Autowired private UsageQuotaService service;
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
    void keepsProviderReservationWhenOuterGenerationTransactionRollsBack() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            service.consumeLlmCall(user.getId(), null, Map.of("prompt", "test-plan"));
            throw new IllegalStateException("generation failed after provider call");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(quotas.findByUserId(user.getId())).get().extracting("quotaUsed").isEqualTo(1);
        assertThat(activities.countByUserId(user.getId())).isEqualTo(1);
    }
}
