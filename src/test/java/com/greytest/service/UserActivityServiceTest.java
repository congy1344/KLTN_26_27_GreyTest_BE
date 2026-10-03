package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.repository.UserActivityLogRepository;

class UserActivityServiceTest {

    @Test
    void recordsADefensiveImmutableCopyWithExplicitNullMetadata() throws Exception {
        UserActivityLogRepository repository = mock(UserActivityLogRepository.class);
        UserActivityService service = new UserActivityService(repository);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetUserId", 2L);
        metadata.put("quotaLimit", null);

        service.record(1L, ActivityAction.ADMIN_QUOTA_CHANGE, null, metadata);
        metadata.put("quotaLimit", 10);

        ArgumentCaptor<UserActivityLog> captured = ArgumentCaptor.forClass(UserActivityLog.class);
        verify(repository).save(captured.capture());
        Map<String, Object> stored = captured.getValue().getMetadata();
        assertThat(stored).containsEntry("targetUserId", 2L).containsEntry("quotaLimit", null);
        assertThatThrownBy(() -> stored.put("quotaLimit", 20)).isInstanceOf(UnsupportedOperationException.class);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(mapper.writeValueAsString(stored)).has("quotaLimit")).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(stored)).get("quotaLimit").isNull()).isTrue();
    }
}
