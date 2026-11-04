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
