package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Tự kiểm chứng PID/span/batch và wait event trên PostgreSQL thật, không mô phỏng server telemetry. */
class TailLatencyObserverTest {
    @Test void capturesRealBlockerAndGrantedLockWithoutChangingSchema() throws Throwable {
        assumeTrue("jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024".equals(System.getenv("SPRING_DATASOURCE_URL")));
        String oldTail=System.getProperty("benchmark.tail-observer"),oldDetail=System.getProperty("benchmark.transaction-detail");
        System.setProperty("benchmark.tail-observer","true");System.setProperty("benchmark.transaction-detail","true");
        var configuration=new HikariConfig();configuration.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));
        configuration.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));configuration.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(1);var executor=Executors.newSingleThreadExecutor();
        try(var pool=new HikariDataSource(configuration);var locker=pool.getConnection()) {
            locker.setAutoCommit(false);long lockId=System.nanoTime();int lockerPid=locker.unwrap(org.postgresql.PGConnection.class).getBackendPID();
            try(var statement=locker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {statement.setLong(1,lockId);statement.execute();}
            var source=(DataSource)CriticalPathProfiler.Configuration.criticalPathObserver().postProcessAfterInitialization(pool,"dataSource");
            var jdbc=new JdbcTemplate(source);var run=new CriticalPathProfiler.Run("real-blocker",0,0);
            try(var observer=TailLatencyObserver.startIfEnabled()) {
                assertThat(observer.statistics()).contains("wal_records","xact_commit","checkpoints_timed");
                CriticalPathProfiler.bind(run,()->{
                    var work=CriticalPathProfiler.batchWorker(List.of(Set.of(1L)),ignored->jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {statement.setLong(1,lockId);statement.execute();}return null;}));
                    var waiting=executor.submit(()->work.apply(Set.of(1L)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    try {
                        while(run.databaseSamples.stream().noneMatch(s->s.containsKey("locks")) && System.nanoTime()<deadline) Thread.sleep(10);
                        assertThat(run.databaseSamples).anySatisfy(sample->{
                            assertThat(sample.get("waitType")).isEqualTo("Lock");assertThat(sample.get("blockers")).asList().contains(lockerPid);
                            @SuppressWarnings("unchecked") var locks=(List<java.util.Map<String,Object>>)sample.get("locks");assertThat(locks).isNotNull();
                            assertThat(locks).anySatisfy(lock->{assertThat(lock.get("pid")).isEqualTo(lockerPid);assertThat(lock.get("granted")).isEqualTo(true);});
                            assertThat(locks).anySatisfy(lock->assertThat(lock.get("granted")).isEqualTo(false));
                        });
                    } finally {locker.rollback();}
                    waiting.get(5,TimeUnit.SECONDS);return null;
                });
            }
            assertThat(run.observerErrors).isEmpty();
        } finally {
            executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);
            if(oldTail==null)System.clearProperty("benchmark.tail-observer");else System.setProperty("benchmark.tail-observer",oldTail);
            if(oldDetail==null)System.clearProperty("benchmark.transaction-detail");else System.setProperty("benchmark.transaction-detail",oldDetail);
        }
    }
    @Test void observesNativeWaitAndCorrelatesWorkerWithoutBorrowingPoolSlot() throws Throwable {
        assumeTrue("jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024".equals(System.getenv("SPRING_DATASOURCE_URL")));
        String previous=System.getProperty("benchmark.tail-observer");
        System.setProperty("benchmark.tail-observer","true");
        var configuration=new HikariConfig(); configuration.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));
        configuration.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME")); configuration.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        configuration.setMaximumPoolSize(2); configuration.setMinimumIdle(1);
        var executor=Executors.newSingleThreadExecutor();
        try(var pool=new HikariDataSource(configuration)) {
            var source=(DataSource)CriticalPathProfiler.Configuration.criticalPathObserver().postProcessAfterInitialization(pool,"dataSource");
            var jdbc=new JdbcTemplate(source); var run=new CriticalPathProfiler.Run("native-wait",0,0);
            try(var observer=TailLatencyObserver.startIfEnabled()) {
                CriticalPathProfiler.bind(run,()->{
                    var work=CriticalPathProfiler.batchWorker(List.of(Set.of(31L)),ignored->jdbc.queryForObject("SELECT 1 FROM pg_sleep(0.35)",Integer.class));
                    assertThat(executor.submit(()->work.apply(Set.of(31L))).get(5,TimeUnit.SECONDS)).isEqualTo(1);
                    return null;
                });
            }
            assertThat(run.observerErrors).isEmpty(); assertThat(run.activeJdbc).isEmpty();
            var span=run.spans().stream().filter(s->s.name().equals("jdbc.executeQuery")).findFirst().orElseThrow();
            assertThat(span.backendPid()).isPositive();
            assertThat(run.databaseSamples).anySatisfy(sample->{
                assertThat(sample.get("waitEvent")).isEqualTo("PgSleep");
                assertThat(sample.get("spanId")).isEqualTo(span.id()); assertThat(sample.get("backendPid")).isEqualTo(span.backendPid());
                assertThat(sample.get("thread")).isEqualTo(span.thread()); assertThat(sample.get("batch")).isEqualTo(1);
                assertThat((long)sample.get("startNanos")).isGreaterThanOrEqualTo(span.startNanos());
                assertThat((long)sample.get("endNanos")).isLessThanOrEqualTo(span.endNanos());
            });
            assertThat(run.poolSamples).isNotEmpty().allSatisfy(sample->assertThat((int)sample.get("total")).isLessThanOrEqualTo(2));
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
            if(previous==null) System.clearProperty("benchmark.tail-observer"); else System.setProperty("benchmark.tail-observer",previous);
        }
    }
}
