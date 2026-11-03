package com.greytest.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Kiểm tra profiler không đổi kết quả/lỗi và giữ correlation khi chuyển worker. */
class CriticalPathProfilerTest {
    @Test void recordsNestedIntervalsWithoutAddingTheirWallTimes() throws Throwable {
        var run = new CriticalPathProfiler.Run("test", 1, 2);
        CriticalPathProfiler.bind(run, () -> CriticalPathProfiler.observe("parent", () ->
                CriticalPathProfiler.observe("child", () -> 42)));
        var spans = run.spans();
        assertThat(spans).hasSize(2);
        var child = spans.stream().filter(s -> s.name().equals("child")).findFirst().orElseThrow();
        var parent = spans.stream().filter(s -> s.name().equals("parent")).findFirst().orElseThrow();
        assertThat(child.parent()).isEqualTo(parent.id());
        assertThat(child.startNanos()).isGreaterThanOrEqualTo(parent.startNanos());
        assertThat(child.endNanos()).isLessThanOrEqualTo(parent.endNanos());
        assertThat(parent.cpuNanos()).isGreaterThanOrEqualTo(-1);
        assertThat(parent.allocatedBytes()).isGreaterThanOrEqualTo(-1);
        assertThat(child.purpose()).isEqualTo("parent");
    }

    @Test void controlDisablesObserverAtContextInitialization() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CriticalPathProfiler.Configuration.class)
                .withPropertyValues("benchmark.instrumentation=false")
                .run(context -> assertThat(context).doesNotHaveBean("criticalPathObserver"));
    }

    @Test void canonicalComparisonKeepsBusinessValuesAndSource() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ids = java.util.Map.of("METHOD", java.util.Map.of("17", "Service#value()"), "TEST_CASE", java.util.Map.of("18", "case-ordinal-1"));
        var input = mapper.readTree("{\"methodId\":17,\"expectedResult\":\"Returns 42\",\"sourceCode\":\"assertEquals(42, service.value());\",\"parameters\":[\"second\",\"first\"]}");
        var canonical = PairedSourceUpdateBenchmarkTest.canonical(input, "", ids, java.util.Map.of());
        assertThat(canonical.path("methodId").asText()).isEqualTo("Service#value()");
        assertThat(canonical.path("expectedResult")).isEqualTo(input.path("expectedResult"));
        assertThat(canonical.path("sourceCode")).isEqualTo(input.path("sourceCode"));
        assertThat(canonical.path("parameters")).isEqualTo(input.path("parameters"));
        assertThat(PairedSourceUpdateBenchmarkTest.normalizeTargetKey("case:18", ids)).isEqualTo("case:case-ordinal-1");
    }

    @Test void targetKeyPreservesCaseOrdinalWithinPlan() {
        var ids = java.util.Map.of("TEST_PLAN", java.util.Map.of("17", "plan-identity"),
                "TEST_CASE", java.util.Map.of("18", "case-identity"));
        assertThat(PairedSourceUpdateBenchmarkTest.normalizeTargetKey("plan:17:case:0", ids))
                .isEqualTo("plan:plan-identity:case:0");
        assertThat(PairedSourceUpdateBenchmarkTest.normalizeTargetKey("plan:17:case:18", ids))
                .isEqualTo("plan:plan-identity:case:18");
        assertThat(PairedSourceUpdateBenchmarkTest.normalizeTargetKey("case:18", ids))
                .isEqualTo("case:case-identity");
        assertThat(PairedSourceUpdateBenchmarkTest.normalizeTargetKey("benchmark.Service#value()", ids))
                .isEqualTo("benchmark.Service#value()");
    }

    @Test void preservesFailureAndRestoresThreadContext() throws Throwable {
        var run = new CriticalPathProfiler.Run("failure", 1, 2);
        var failure = new IllegalStateException("expected");
        assertThatThrownBy(() -> CriticalPathProfiler.bind(run, () ->
                CriticalPathProfiler.observe("failed", () -> { throw failure; }))).isSameAs(failure);
        assertThat(run.spans()).singleElement().satisfies(s -> assertThat(s.failed()).isTrue());
        CriticalPathProfiler.observe("outside", () -> null);
        assertThat(run.spans()).hasSize(1);
    }

    @Test void propagatesBatchCorrelationToAnotherThread() throws Throwable {
        var run = new CriticalPathProfiler.Run("workers", 3, 4);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            CriticalPathProfiler.bind(run, () -> {
                var task = CriticalPathProfiler.batchWorker(List.of(java.util.Set.of(17L)),
                        (Object input) -> CriticalPathProfiler.unchecked("work", () -> 42));
                assertThat(executor.submit(() -> task.apply(java.util.Set.of(17L))).get()).isEqualTo(42);
                return null;
            });
        } finally { executor.shutdownNow(); }
        assertThat(run.spans()).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("work");
            assertThat(s.batch()).isEqualTo(1);
            assertThat(s.targets()).isEqualTo("[17]");
        });
    }

    @Test void recordsOnlyNewTransactionHoldUntilCommit() throws Throwable {
        var original = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus(true);
        org.mockito.Mockito.when(original.getTransaction(null)).thenReturn(status);
        var observed = (org.springframework.transaction.PlatformTransactionManager) CriticalPathProfiler.Configuration
                .criticalPathObserver().postProcessAfterInitialization(original, "transactionManager");
        var run = new CriticalPathProfiler.Run("transaction", 1, 2);
        CriticalPathProfiler.bind(run, () -> {
            var tx = observed.getTransaction(null);
            CriticalPathProfiler.observe("localBody", () -> 42);
            observed.commit(tx);
            return null;
        });
        var hold = run.spans().stream().filter(s -> s.name().equals("transaction.hold")).findFirst().orElseThrow();
        var body = run.spans().stream().filter(s -> s.name().equals("localBody")).findFirst().orElseThrow();
        assertThat(hold.startNanos()).isLessThanOrEqualTo(body.startNanos());
        assertThat(hold.endNanos()).isGreaterThanOrEqualTo(body.endNanos());
        org.mockito.Mockito.verify(original).commit(status);
    }
}
