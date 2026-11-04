package com.greytest.service.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.greytest.service.GenerationJobContext;

/** Chứng minh SQL ứng viên trên PostgreSQL thật trước khi chuyển guard production sang một statement. */
class LeaseAssertionPostgresIntegrationTest {
    static final String FLAG = "greytest.source-update.single-statement-lease-check-enabled";
    static final String CANDIDATE = """
            WITH locked AS MATERIALIZED (
                SELECT owner,fence,state,lease_until FROM generation_checkpoint
                WHERE project_id=? AND source_update_id=? AND stage='PIPELINE' AND target_key='__run__'
                FOR UPDATE
            )
            SELECT COUNT(*) FROM locked WHERE owner=? AND fence=?
                AND state='RUNNING' AND lease_until > clock_timestamp()
            """;
    enum Variant { LEGACY, CANDIDATE }
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private GenerationCheckpointStore store;
    private Long projectId, updateId;
    private GenerationCheckpointStore.Lease lease;

    @BeforeEach void prepare() {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        assumeTrue("jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024".equals(url));
        var source=new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals("read committed",jdbc.queryForObject("SHOW default_transaction_isolation",String.class));
        store=new GenerationCheckpointStore(jdbc,manager);
        projectId=jdbc.queryForObject("INSERT INTO project(name,source_type,status) VALUES (?,'ZIP','UPLOADED') RETURNING id",Long.class,"lease-proof-"+UUID.randomUUID());
        Long revision=jdbc.queryForObject("INSERT INTO source_revision(project_id,source_type,storage_path) VALUES (?,'ZIP','/lease-proof') RETURNING id",Long.class,projectId);
        updateId=jdbc.queryForObject("INSERT INTO source_update(project_id,candidate_revision_id,status) VALUES (?,?,'DRAFT') RETURNING id",Long.class,projectId,revision);
        lease=store.claim(projectId,updateId,Duration.ofMinutes(2));
    }

    @AfterEach void cleanup() { if(projectId!=null) jdbc.update("DELETE FROM project WHERE id=?",projectId); }

    private TransactionTemplate transaction(int isolation) {
        var tx=new TransactionTemplate(manager); tx.setIsolationLevel(isolation); return tx;
    }
    private void invoke(Variant variant,GenerationCheckpointStore.Lease current) {
        GenerationJobContext.withValue(FLAG,variant==Variant.CANDIDATE,()->{store.assertLease(current);return null;});
    }
    private void assertAllowed(Variant variant,GenerationCheckpointStore.Lease current) {
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status->invoke(variant,current));
    }
    private void expire() { jdbc.update("UPDATE generation_checkpoint SET lease_until=clock_timestamp()-interval '1 second' WHERE source_update_id=? AND stage='PIPELINE'",updateId); }
    private int artifacts() { return jdbc.queryForObject("SELECT COUNT(*) FROM source_update_item WHERE source_update_id=?",Integer.class,updateId); }
    private void insertArtifact() { jdbc.update("INSERT INTO source_update_item(source_update_id,target_type,target_key,action,after_data) VALUES (?,'BUSINESS_RULE','proof','CREATE','{}')",updateId); }

    @ParameterizedTest @EnumSource(Variant.class)
    void validExpiredMissingAndIdentityChecks(Variant variant) {
        assertAllowed(variant,lease);
        assertThrows(IllegalStateException.class,()->invoke(variant,lease));
        for(var wrong:List.of(new GenerationCheckpointStore.Lease(projectId,updateId,"other",lease.fence()),
                new GenerationCheckpointStore.Lease(projectId,updateId,lease.owner(),lease.fence()+1),
                new GenerationCheckpointStore.Lease(-1L,updateId,lease.owner(),lease.fence()),
                new GenerationCheckpointStore.Lease(projectId,-1L,lease.owner(),lease.fence())))
            assertThrows(IllegalStateException.class,()->assertAllowed(variant,wrong));
        jdbc.update("UPDATE generation_checkpoint SET state='FAILED' WHERE source_update_id=? AND stage='PIPELINE'",updateId);
        assertThrows(IllegalStateException.class,()->assertAllowed(variant,lease));
        jdbc.update("UPDATE generation_checkpoint SET state='RUNNING' WHERE source_update_id=? AND stage='PIPELINE'",updateId);
        expire(); assertThrows(IllegalStateException.class,()->assertAllowed(variant,lease));
        jdbc.update("DELETE FROM generation_checkpoint WHERE source_update_id=?",updateId);
        assertThrows(IllegalStateException.class,()->assertAllowed(variant,lease));
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void replacementRetryAndStaleWorkerCannotCommit(Variant variant) {
        expire(); assertThrows(IllegalStateException.class,()->assertAllowed(variant,lease));
        var next=store.claim(projectId,updateId,Duration.ofMinutes(2));
        assertTrue(next.fence()>lease.fence()); assertNotEquals(next.owner(),lease.owner());
        assertThrows(IllegalStateException.class,()->transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status->{
            insertArtifact(); invoke(variant,lease);
        }));
        assertEquals(0,artifacts()); assertAllowed(variant,next);
        store.release(next,"SUCCEEDED","{}");
        assertThrows(IllegalStateException.class,()->assertAllowed(variant,next));
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void artifactAndCheckpointRollbackTogether(Variant variant) {
        assertThrows(IllegalArgumentException.class,()->GenerationJobContext.withValue(FLAG,variant==Variant.CANDIDATE,
                ()->transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status->{
            invoke(variant,lease); insertArtifact();
            store.complete(lease,"BR","proof","fp","{}","[]","digest");
            throw new IllegalArgumentException("rollback proof");
        })));
        assertEquals(0,artifacts()); assertTrue(store.find(updateId,"BR","proof").isEmpty());
        assertAllowed(variant,lease);
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void clockIsEvaluatedAfterRealLockWait(Variant variant) throws Exception {
        jdbc.update("UPDATE generation_checkpoint SET lease_until=clock_timestamp()+interval '2 seconds' WHERE source_update_id=? AND stage='PIPELINE'",updateId);
        var result=whileLocked(variant,TransactionDefinition.ISOLATION_READ_COMMITTED,()->{
            assertTrue(jdbc.queryForObject("SELECT lease_until > clock_timestamp() FROM generation_checkpoint WHERE source_update_id=? AND stage='PIPELINE'",Boolean.class,updateId),
                    "Worker must already be blocked while the lease is still valid");
            await(()->jdbc.queryForObject("SELECT lease_until < clock_timestamp() FROM generation_checkpoint WHERE source_update_id=? AND stage='PIPELINE'",Boolean.class,updateId));
        },true);
        assertInstanceOf(IllegalStateException.class,result);
        assertEquals(0,artifacts());
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void waitingWorkerSeesReplacementOwnership(Variant variant) throws Exception {
        var result=whileLocked(variant,TransactionDefinition.ISOLATION_READ_COMMITTED,()->jdbc.update(
                "UPDATE generation_checkpoint SET owner='replacement',fence=fence+1 WHERE source_update_id=? AND stage='PIPELINE'",updateId),true);
        assertInstanceOf(IllegalStateException.class,result); assertEquals(0,artifacts());
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void waitingWorkerSeesRenewedDeadline(Variant variant) throws Exception {
        expire();
        var result=whileLocked(variant,TransactionDefinition.ISOLATION_READ_COMMITTED,()->jdbc.update(
                "UPDATE generation_checkpoint SET lease_until=clock_timestamp()+interval '1 minute' WHERE source_update_id=? AND stage='PIPELINE'",updateId),false);
        assertNull(result);
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void waitingWorkerSeesDeletedSentinel(Variant variant) throws Exception {
        var result=whileLocked(variant,TransactionDefinition.ISOLATION_READ_COMMITTED,()->jdbc.update(
                "DELETE FROM generation_checkpoint WHERE source_update_id=? AND stage='PIPELINE'",updateId),true);
        assertInstanceOf(IllegalStateException.class,result); assertEquals(0,artifacts());
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void twoWorkersSerializeButValidSharedLeaseRemainsUsable(Variant variant) throws Exception {
        assertNull(whileLocked(variant,TransactionDefinition.ISOLATION_READ_COMMITTED,()->invoke(variant,lease),false));
        assertAllowed(variant,lease);
    }

    @Test void runtimeUsesOneStatementAndRollbackUsesTwo() {
        var counted=new JdbcTemplate(jdbc.getDataSource()) {
            int queries;
            String last;
            @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object...args) {
                queries++; last=sql; return super.queryForList(sql,args);
            }
            @Override public <T> T queryForObject(String sql,Class<T> type,Object...args) {
                queries++; last=sql; return super.queryForObject(sql,type,args);
            }
        };
        var runtime=new GenerationCheckpointStore(counted,manager);
        for(boolean optimized:List.of(true,false)) {
            counted.queries=0;
            GenerationJobContext.withValue(FLAG,optimized,()->transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status->{
                runtime.assertLease(lease); return null;
            }));
            assertEquals(optimized?1:2,counted.queries);
            if(optimized) assertEquals(CANDIDATE.replaceAll("\\s+"," ").trim(),counted.last.replaceAll("\\s+"," ").trim());
        }
    }

    @ParameterizedTest @EnumSource(Variant.class)
    void repeatableReadAndSerializableRejectConcurrentReplacement(Variant variant) throws Exception {
        for(int isolation:List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var result=whileLocked(variant,isolation,()->jdbc.update(
                    "UPDATE generation_checkpoint SET fence=fence+1 WHERE source_update_id=? AND stage='PIPELINE'",updateId),true);
            assertNotNull(result); Throwable cause=result;
            while(cause.getCause()!=null) cause=cause.getCause();
            assertInstanceOf(java.sql.SQLException.class,cause);
            assertEquals("40001",((java.sql.SQLException)cause).getSQLState()); assertEquals(0,artifacts());
            jdbc.update("UPDATE generation_checkpoint SET fence=? WHERE source_update_id=? AND stage='PIPELINE'",lease.fence(),updateId);
        }
    }

    @Test void explainPlacesVolatileClockOutsideLockRows() throws Exception {
        String plan=jdbc.queryForObject("EXPLAIN (VERBOSE,FORMAT JSON) "+CANDIDATE,String.class,projectId,updateId,lease.owner(),lease.fence());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan);
        assertTrue(plan.contains("LockRows"));
        var scan=findPlan(json,"CTE Scan"); assertNotNull(scan);
        assertTrue(scan.path("Filter").asText().contains("clock_timestamp()"));
        assertTrue(scan.path("Filter").asText().contains("owner"));
        Path output=Path.of("target/lease-assertion-proof-plan.json"); Files.writeString(output,plan);
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="lease.benchmark",matches="true")
    void benchmarkRealAssertionsAndObservedLockWait() throws Exception {
        var samples=new java.util.ArrayList<java.util.Map<String,Object>>();
        var waits=new java.util.ArrayList<java.util.Map<String,Object>>();
        var orders=new java.util.ArrayList<Boolean>();
        for(int i=0;i<30;i++) orders.add(i%2==0);
        java.util.Collections.shuffle(orders,new java.util.Random(3400));
        for(int pair=-4;pair<30;pair++) for(int order=0;order<2;order++) {
            boolean optimized=(order==0)==(pair>=0 && orders.get(pair));
            Variant variant=optimized?Variant.CANDIDATE:Variant.LEGACY;
            double elapsed=transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status->{
                long started=System.nanoTime(); invoke(variant,lease); return (System.nanoTime()-started)/1e6;
            });
            if(pair>=0) samples.add(java.util.Map.of("pair",pair,"variant",variant.name(),"assertMillis",elapsed));
            if(pair>=0 && pair<10) {
                assertNull(whileLocked(variant,TransactionDefinition.ISOLATION_READ_COMMITTED,()->{
                    try { Thread.sleep(25); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                },false));
                var row=new java.util.LinkedHashMap<String,Object>(lastWait); row.put("pair",pair); waits.add(row);
            }
        }
        Files.writeString(Path.of("target/assertlease-micro.json"),new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of("assertions",samples,"observedLockWaits",waits)));
    }
    private com.fasterxml.jackson.databind.JsonNode findPlan(com.fasterxml.jackson.databind.JsonNode node,String type) {
        if(type.equals(node.path("Node Type").asText())) return node;
        for(var child:node) { var found=findPlan(child,type); if(found!=null) return found; }
        return null;
    }

    // Xác nhận worker đã bị PostgreSQL chặn trước khi đổi row hoặc chờ deadline; không suy ra lock từ sleep.
    private Throwable whileLocked(Variant variant,int isolation,Runnable beforeUnlock,boolean insertBeforeGuard) throws Exception {
        var executor=Executors.newSingleThreadExecutor(); var pid=new AtomicInteger();
        var pending=new java.util.concurrent.atomic.AtomicReference<Future<Throwable>>();
        long[] timing=new long[4];
        try {
            transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status->{
                jdbc.queryForList("SELECT id FROM generation_checkpoint WHERE source_update_id=? AND stage='PIPELINE' FOR UPDATE",updateId);
                Future<Throwable> worker=executor.submit(()->{
                    try {
                        transaction(isolation).executeWithoutResult(workerStatus->{
                            jdbc.execute("SET LOCAL lock_timeout='8s'");
                            pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                            if(insertBeforeGuard) insertArtifact();
                            timing[0]=System.nanoTime();
                            try { invoke(variant,lease); } finally { timing[1]=System.nanoTime(); }
                        }); return null;
                    } catch(RuntimeException failure) { return failure; }
                });
                pending.set(worker);
                await(()->pid.get()!=0 && jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()));
                assertEquals("Lock",jdbc.queryForObject("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?",String.class,pid.get()));
                timing[2]=System.nanoTime();
                assertFalse(worker.isDone());
                beforeUnlock.run();
                timing[3]=System.nanoTime();
                // Kết thúc transaction giữ lock trước khi join worker ở scope bên ngoài.
            });
            Throwable result=pending.get().get(10,TimeUnit.SECONDS);
            lastWait=java.util.Map.of("variant",variant.name(),"isolation",isolation,"assertMillis",(timing[1]-timing[0])/1e6,
                    "observedBlockedMillis",(timing[3]-timing[2])/1e6,"error",result==null?"none":result.getClass().getSimpleName());
            return result;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));
        }
    }
    private java.util.Map<String,Object> lastWait;

    private static void await(java.util.function.BooleanSupplier ready) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!ready.getAsBoolean()) {
            if(System.nanoTime()>until) fail("PostgreSQL concurrency condition was not reached");
            try { Thread.sleep(10); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
}
