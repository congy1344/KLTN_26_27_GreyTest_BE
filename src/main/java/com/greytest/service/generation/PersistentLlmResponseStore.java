package com.greytest.service.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Lưu response đã được caller validate; cache độc lập với transaction lưu artifact. */
@Service
public class PersistentLlmResponseStore {

    public record CachedEntry(String response, String provenance) { }

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PROJECT_ENTRIES = 500;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final ObjectMapper mapper;

    public PersistentLlmResponseStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<String> get(Long projectId, String stage, String fingerprint) {
        return getEntry(projectId, stage, fingerprint).map(CachedEntry::response);
    }

    public Optional<CachedEntry> getEntry(Long projectId, String stage, String fingerprint) {
        return independent.execute(status -> {
            var responses = jdbc.query("""
                    SELECT response,response_digest,expires_at > clock_timestamp() AS current,provenance::text
                    FROM llm_response_cache WHERE project_id=? AND stage=? AND fingerprint=?
                    """, (rs, row) -> new CachedResponse(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)),
                    projectId, stage, fingerprint);
            if (responses.isEmpty()) {
                return Optional.empty();
            }
            CachedResponse cached = responses.get(0);
            try {
                validateStructure(cached.response());
                if (cached.current() && digest(cached.response()).equals(cached.digest())) {
                    return Optional.of(new CachedEntry(cached.response(), cached.provenance()));
                }
            } catch (IllegalArgumentException invalid) {
                // Cache hỏng phải trở thành miss; không dùng làm bằng chứng stage đã hoàn tất.
            }
            delete(projectId, stage, fingerprint);
            return Optional.empty();
        });
    }

    public void putValidated(Long projectId, String stage, String fingerprint, String response) {
        putValidated(projectId, stage, fingerprint, response, "{\"formatVersion\":1}");
    }

    public void putValidated(Long projectId, String stage, String fingerprint, String response, String provenance) {
        validateStructure(response);
        independent.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO llm_response_cache(project_id,stage,fingerprint,response,response_digest,provenance)
                    VALUES (?,?,?,?,?,CAST(? AS jsonb))
                    ON CONFLICT (project_id,stage,fingerprint) DO UPDATE SET response=EXCLUDED.response,
                        response_digest=EXCLUDED.response_digest,provenance=EXCLUDED.provenance,
                        created_at=clock_timestamp(),expires_at=clock_timestamp()+interval '7 days'
                    """, projectId, stage, fingerprint, response, digest(response), provenance);
            jdbc.update("""
                    DELETE FROM llm_response_cache WHERE id IN (
                        SELECT id FROM llm_response_cache WHERE project_id=?
                        ORDER BY created_at DESC,id DESC OFFSET ?)
                    """, projectId, MAX_PROJECT_ENTRIES);
        });
    }

    public void evict(Long projectId, String stage, String fingerprint) {
        independent.executeWithoutResult(status -> delete(projectId, stage, fingerprint));
    }

    private void delete(Long projectId, String stage, String fingerprint) {
        jdbc.update("DELETE FROM llm_response_cache WHERE project_id=? AND stage=? AND fingerprint=?", projectId, stage, fingerprint);
    }

    private void validateStructure(String response) {
        if (response == null || response.isBlank() || response.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Cache response is empty or exceeds the size limit");
        }
        try {
            JsonNode json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response);
            if (json == null || !json.isContainerNode() || !hasContent(json)) {
                throw new IllegalArgumentException("Cache response must contain a non-empty JSON object or array");
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cache response must be valid JSON", invalid);
        }
    }

    private boolean hasContent(JsonNode node) {
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (hasContent(child)) {
                    return true;
                }
            }
            return false;
        }
        return !node.isNull() && (!node.isTextual() || !node.textValue().isBlank());
    }

    private String digest(String response) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private record CachedResponse(String response, String digest, boolean current, String provenance) { }
}
