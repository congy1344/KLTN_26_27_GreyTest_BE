package com.greytest.service.generation;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.greytest.exception.GenerationInProgressException;

/** Lưu checkpoint và điều phối lease có fencing để worker cũ không ghi đè kết quả mới. */
@Service
public class GenerationCheckpointStore {

    public record Lease(Long projectId, Long updateId, String owner, long fence) { }
    public record Checkpoint(String stage, String targetKey, String fingerprint, String provenance,
            String outputJson, String outputDigest, String state) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final TransactionTemplate required;

    public GenerationCheckpointStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
    }

    public Lease claim(Long projectId, Long updateId, Duration ttl) {
        long millis = leaseMillis(ttl);
        String owner = UUID.randomUUID().toString();
        return independent.execute(status -> {
            // Cùng thứ tự khóa với analyze/review/apply/cancel để chặn request ở backend instance khác.
            jdbc.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", projectId);
            Integer running = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM generation_checkpoint WHERE project_id=? AND stage='PIPELINE'
                        AND target_key='__run__' AND state='RUNNING' AND lease_until > clock_timestamp()
                    """, Integer.class, projectId);
            if (running != null && running > 0) {
                throw new GenerationInProgressException("Project đang sinh artifact trong một bản cập nhật khác");
            }
            List<Long> fences = jdbc.query("""
                    INSERT INTO generation_checkpoint(project_id,source_update_id,stage,target_key,state,owner,fence,lease_until)
                    SELECT project_id,id,'PIPELINE','__run__','RUNNING',?,1,clock_timestamp() + (? * interval '1 millisecond')
                    FROM source_update WHERE id = ? AND project_id = ?
                    ON CONFLICT (source_update_id,stage,target_key) DO UPDATE
                    SET state='RUNNING',owner=EXCLUDED.owner,fence=generation_checkpoint.fence+1,
                        lease_until=EXCLUDED.lease_until,error=NULL,metrics=NULL,updated_at=clock_timestamp()
                    WHERE generation_checkpoint.state <> 'RUNNING' OR generation_checkpoint.lease_until <= clock_timestamp()
                    RETURNING fence
                    """, (rs, row) -> rs.getLong(1), owner, millis, updateId, projectId);
            if (fences.isEmpty()) {
                throw new GenerationInProgressException("Source update đang được xử lý hoặc không thuộc project");
            }
            return new Lease(projectId, updateId, owner, fences.get(0));
        });
    }

    public void heartbeat(Lease lease, Duration ttl) {
        long millis = leaseMillis(ttl);
        independent.executeWithoutResult(status -> requireChanged(jdbc.update("""
                UPDATE generation_checkpoint SET lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at=clock_timestamp()
                WHERE project_id=? AND source_update_id=? AND stage='PIPELINE' AND target_key='__run__'
                    AND owner=? AND fence=? AND state='RUNNING' AND lease_until > clock_timestamp()
                """, millis, lease.projectId(), lease.updateId(), lease.owner(), lease.fence())));
    }

    /** Khóa sentinel trong transaction đang lưu artifact; tuyệt đối không giữ khóa lúc gọi LLM. */
    public void assertLease(Lease lease) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Lease must be asserted inside the artifact transaction");
        }
        jdbc.queryForList("""
                SELECT id FROM generation_checkpoint WHERE project_id=? AND source_update_id=?
                    AND stage='PIPELINE' AND target_key='__run__' FOR UPDATE
                """, lease.projectId(), lease.updateId());
        // Kiểm tra giờ DB sau khi lấy khóa, vì thời gian chờ khóa có thể làm lease hết hạn.
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM generation_checkpoint WHERE project_id=? AND source_update_id=?
                    AND stage='PIPELINE' AND target_key='__run__' AND owner=? AND fence=?
                    AND state='RUNNING' AND lease_until > clock_timestamp()
                """, Integer.class, lease.projectId(), lease.updateId(), lease.owner(), lease.fence());
        requireChanged(count == null ? 0 : count);
    }

    public void complete(Lease lease, String stage, String key, String fingerprint, String provenance,
            String outputJson, String outputDigest) {
        if (outputJson == null || outputJson.isBlank() || outputDigest == null || outputDigest.isBlank()) {
            throw new IllegalArgumentException("Successful checkpoint requires persisted output and digest");
        }
        save(lease, stage, key, "SUCCEEDED", fingerprint, provenance, outputJson, outputDigest, null);
    }

    public void mark(Lease lease, String stage, String key, String state, String fingerprint,
            String provenance, String outputJson, String error) {
        if (!Set.of("FAILED", "BLOCKED", "CONFLICT").contains(state)) {
            throw new IllegalArgumentException("mark only accepts FAILED, BLOCKED or CONFLICT");
        }
        save(lease, stage, key, state, fingerprint, provenance, outputJson, null, error);
    }

    public Optional<Checkpoint> find(Long updateId, String stage, String key) {
        return jdbc.query("""
                SELECT stage,target_key,fingerprint,provenance::text,output_json,output_digest,state
                FROM generation_checkpoint WHERE source_update_id=? AND stage=? AND target_key=?
                """, (rs, row) -> new Checkpoint(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)), updateId, stage, key).stream().findFirst();
    }

    public List<Checkpoint> list(Long updateId) {
        return jdbc.query("""
                SELECT stage,target_key,fingerprint,provenance::text,output_json,output_digest,state
                FROM generation_checkpoint WHERE source_update_id=? AND stage <> 'PIPELINE' ORDER BY stage,target_key
                """, (rs, row) -> new Checkpoint(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)), updateId);
    }

    public Optional<String> metricsJson(Long updateId) {
        return jdbc.query("""
                SELECT metrics::text FROM generation_checkpoint WHERE source_update_id=?
                    AND stage='PIPELINE' AND target_key='__run__' AND metrics IS NOT NULL
                """, (rs, row) -> rs.getString(1), updateId).stream().findFirst();
    }

    public boolean isRunning(Long updateId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM generation_checkpoint WHERE source_update_id=? AND stage='PIPELINE'
                    AND target_key='__run__' AND state='RUNNING' AND lease_until > clock_timestamp()
                """, Integer.class, updateId);
        return count != null && count > 0;
    }

    public void release(Lease lease, String state, String metricsJson) {
        if (!Set.of("SUCCEEDED", "FAILED", "BLOCKED", "CONFLICT", "CANCELLED").contains(state)) {
            throw new IllegalArgumentException("Invalid terminal run state");
        }
        independent.executeWithoutResult(status -> requireChanged(jdbc.update("""
                UPDATE generation_checkpoint SET state=?,metrics=CAST(? AS jsonb),lease_until=NULL,updated_at=clock_timestamp()
                WHERE project_id=? AND source_update_id=? AND stage='PIPELINE' AND target_key='__run__'
                    AND owner=? AND fence=? AND state='RUNNING' AND lease_until > clock_timestamp()
                """, state, metricsJson, lease.projectId(), lease.updateId(), lease.owner(), lease.fence())));
    }

    private void save(Lease lease, String stage, String key, String state, String fingerprint,
            String provenance, String outputJson, String outputDigest, String error) {
        if (stage == null || stage.isBlank() || "PIPELINE".equals(stage) || key == null || key.isBlank()) {
            throw new IllegalArgumentException("A non-internal stage and stable target key are required");
        }
        required.executeWithoutResult(status -> {
            assertLease(lease);
            jdbc.update("""
                    INSERT INTO generation_checkpoint(project_id,source_update_id,stage,target_key,state,fingerprint,
                        provenance,output_json,output_digest,error,owner,fence)
                    VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?)
                    ON CONFLICT (source_update_id,stage,target_key) DO UPDATE SET state=EXCLUDED.state,
                        fingerprint=EXCLUDED.fingerprint,provenance=EXCLUDED.provenance,output_json=EXCLUDED.output_json,
                        output_digest=EXCLUDED.output_digest,error=EXCLUDED.error,owner=EXCLUDED.owner,
                        fence=EXCLUDED.fence,updated_at=clock_timestamp()
                    """, lease.projectId(), lease.updateId(), stage, key, state, fingerprint, provenance,
                    outputJson, outputDigest, error, lease.owner(), lease.fence());
        });
    }

    private long leaseMillis(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("A positive lease duration is required");
        }
        return ttl.toMillis();
    }

    private void requireChanged(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Generation lease expired or was replaced");
        }
    }
}
