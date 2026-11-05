# Phase 3.7 — Transaction and Commit Bottleneck Investigation

## Executive result

**Finding:** the strongest directly observed database bottleneck in this six-method fixture is transaction-ID lock contention on the run-level PIPELINE lease row. Two generation workers share one source-update lease. Artifact/checkpoint transactions call `GenerationCheckpointStore.assertLease`, which locks that same row with `SELECT ... FOR UPDATE`; a worker keeps the row lock until its transaction completes. PostgreSQL sampled the other worker waiting for the holder's transaction ID while executing `assertLease`.

This identifies a real contention mechanism, not the whole explanation for every tail. The exact duration attributable to lock waiting cannot be reconstructed from periodic samples. WAL wait events appeared in two of the slowest samples and in some holder samples, but WAL sync time collection is disabled and the host disk counters are not mapped to the PostgreSQL volume. **WAL fsync, storage I/O, or checkpoint write amplification are therefore not established as the root cause.**

**Decision: NO-GO for a runtime change.** Keep `assertLease` single-statement optimization OFF and make no runtime or PostgreSQL setting changes from this investigation. The measured evidence is enough to justify a narrowly scoped follow-up experiment, but not to claim a safe end-to-end improvement or change concurrency/lease semantics.

## Scope and reproducibility

- Project branch: `perf/source-update-performance`; measured backend HEAD: `52528aaf92c61717114868ed4183d58f021a2d0c`.
- Workload: six independent methods, cold generation followed by warm resume; deterministic in-process mock provider with configured latency 0 ms; PostgreSQL 15.17; READ COMMITTED; worker concurrency 2.
- Each cohort has 20 valid cold runs and 20 warm runs. All cold runs in both cohorts have the same fixture digest: `5e0e138ef10d018da87025b99ceb29f72362b528b1d6e4297a08d5dd6a738fa7`.
- Each cold run changed 6 methods and made 7 mock LLM calls. There were zero real provider requests. `assertLease` optimization was false for every run.
- Diagnostic cohort enabled the test-only transaction/Hibernate/PostgreSQL observers. Control cohort disabled these observers. Cohorts were run sequentially, not randomized or paired; report their distributions separately and do not interpret their difference as a causal profiler-overhead or runtime A/B result.
- A warm run made 0 LLM calls. The harness verified the warm output/provenance against its persisted checkpoint, and ran compile/JUnit quality checks on the saved generated proposals. Each cohort's six generated UT fixture tests passed; profile quality run: 6,110 ms, control: 5,920 ms (outside the root generation window).
- Raw measurement artifacts are under ignored build output: `target/phase3-7/profile/samples.json`, `target/phase3-7/control/samples.json`, and `target/phase3-7/host-io.csv`. They are not committed; the per-run cold timings and key span/event observations are reproduced below.

## End-to-end results

Durations are wall-clock milliseconds. p95 uses nearest-rank for 20 samples, so p95 is the maximum sample. The separate cohorts are not paired.

| Cohort / workload | n | p50 | p95 | max |
|---|---:|---:|---:|---:|
| Diagnostic, six-method cold | 20 | 2,454.7 | 4,655.7 | 5,116.2 |
| Diagnostic, warm resume | 20 | 937.9 | 1,371.3 | 1,611.7 |
| Observer-disabled control, six-method cold | 20 | 2,487.0 | 3,835.1 | 3,850.3 |
| Observer-disabled control, warm resume | 20 | 998.5 | 1,518.6 | 1,546.3 |

These measurements do not supersede or directly compare to the earlier Phase 3.5/3.6 p95=8.31 s baseline: the current fixture/run protocol is not a paired replay of those saved raw samples. They also do not predict production-provider latency.

### Diagnostic cohort raw cold samples

`SQL ms` is the sum of client-side executeQuery and executeUpdate spans; `commit ms` is the sum of JDBC commit spans; these are inclusive, may overlap across workers, and must not be added to estimate root wall time. Flush is the sum of Hibernate flush listener spans and can contain SQL work. Wait columns count 20 ms observer snapshots, **not durations**.

| Run | Wall ms | SQL ms | Commit ms | Flush ms | GC ms | XID-lock snapshots | WALSync snapshots | WALWrite LWLock snapshots |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 3,612.9 | 1,418.2 | 282.4 | 205.7 | 34 | 6 | 0 | 0 |
| 1 | 2,949.2 | 1,267.7 | 245.9 | 184.6 | 29 | 5 | 0 | 0 |
| 2 | 3,197.5 | 1,373.4 | 282.9 | 216.5 | 30 | 5 | 0 | 0 |
| 3 | 2,501.4 | 1,156.6 | 224.8 | 161.5 | 26 | 5 | 0 | 0 |
| 4 | 2,622.6 | 1,191.5 | 234.7 | 171.6 | 26 | 3 | 0 | 0 |
| 5 | 2,705.4 | 1,248.6 | 259.3 | 169.7 | 29 | 3 | 0 | 0 |
| 6 | 2,537.0 | 1,232.6 | 254.1 | 154.4 | 29 | 3 | 0 | 0 |
| 7 | 5,116.2 | 3,153.5 | 935.2 | 205.1 | 42 | 11 | 5 | 3 |
| 8 | 4,655.7 | 2,431.2 | 909.3 | 227.8 | 34 | 9 | 3 | 10 |
| 9 | 2,495.4 | 1,290.1 | 234.2 | 142.4 | 25 | 4 | 0 | 0 |
| 10 | 2,167.5 | 1,053.0 | 220.5 | 124.4 | 25 | 2 | 0 | 0 |
| 11 | 2,089.0 | 1,043.0 | 216.7 | 123.4 | 26 | 3 | 0 | 0 |
| 12 | 2,414.0 | 1,185.3 | 268.5 | 145.0 | 24 | 5 | 0 | 0 |
| 13 | 2,195.7 | 1,091.8 | 221.6 | 130.1 | 27 | 5 | 0 | 0 |
| 14 | 2,087.4 | 1,051.9 | 219.3 | 123.3 | 23 | 5 | 0 | 0 |
| 15 | 2,278.5 | 1,161.7 | 230.7 | 159.5 | 27 | 3 | 0 | 0 |
| 16 | 2,160.5 | 1,129.9 | 221.4 | 119.8 | 27 | 4 | 0 | 0 |
| 17 | 2,186.0 | 1,095.6 | 235.8 | 128.9 | 26 | 3 | 0 | 0 |
| 18 | 2,389.9 | 1,160.9 | 249.9 | 153.5 | 31 | 4 | 0 | 0 |
| 19 | 2,367.8 | 1,217.1 | 288.3 | 130.9 | 26 | 3 | 0 | 0 |

Observer-disabled control raw cold wall times in run order (ms):

`3105.7, 3651.0, 2708.5, 3000.5, 3121.9, 2124.9, 2368.4, 2792.4, 2430.1, 2506.4, 3850.3, 1857.6, 1988.2, 2678.4, 3835.1, 2252.3, 2044.9, 2467.6, 2315.0, 2176.4`

## Transaction timeline and backend spans

The measured path is:

1. Analyze creates the source-update transaction and computes snapshot/diff/impact. The median `source.diffNanos` was 89.8 ms (p95 207.5 ms); snapshot capture median was 8.3 ms (p95 15.8 ms).
2. Generation builds per-stage context and fingerprints, then calls the mock provider. Total provider-call time median was 7.2 ms/run (p95 9.2 ms), across 7 calls. The configured provider delay was zero; this number is local call/serialization overhead, not network/provider latency.
3. Each worker validates output and opens short persistence/checkpoint transactions. LLM calls are outside these transactions. No transaction observed here spans provider latency.
4. `assertLease` locks the shared PIPELINE sentinel. The transaction retains this lock through artifact persistence and commit. Another worker's corresponding `assertLease` can then wait on the holder's transaction ID.
5. Persistence runs Hibernate flush and JDBC statements, followed by JDBC commit. Transaction-level `transaction.hold`, SQL spans, commit spans, and flush spans are nested/overlapping measurements, not additive components.

Across the 20 diagnostic cold runs, median per-run summed spans were: JDBC executeQuery 979.6 ms (p95 2,008.6 ms); executeUpdate 205.3 ms (p95 422.7 ms); JDBC commit 240.9 ms (p95 909.3 ms); Hibernate flush 82.6 ms (p95 110.4 ms); auto-flush 65.4 ms (p95 105.9 ms); auto-pre-flush 4.9 ms (p95 7.6 ms); connection acquisition 6.9 ms (p95 16.4 ms). These are sums across calls/workers and may overlap each other and the root wall clock.

There were 162 new physical transactions per cold run on average (112 read-only REQUIRED, 32 writable REQUIRED, 18 REQUIRES_NEW), plus 412 joined transaction interceptions per run. The joined calls are not additional physical transactions. Physical `transaction.hold` spans overlap between workers; their sum is not end-to-end time. The longest observed single physical transaction was 733.5 ms. The longest `executeBatch` physical transaction was 516.3 ms. No evidence showed a transaction held across an LLM request.

## PostgreSQL diagnostics and causal evidence

### Direct sampled lock evidence

- Diagnostic sampler collected 131 `pg_stat_activity` observations during the 20 cold runs. Observed wait labels were: `Lock/transactionid` 91; `LWLock/WALWrite` 13; `IO/WALSync` 8; `Client/ClientRead` 2; `IO/WALWrite` 1; `IO/DataFileExtend` 1; no wait event 3. Samples are periodic snapshots and do not represent milliseconds.
- In 86 sampled observations, the waiter's ungranted `pg_locks` transactionid matched the blocker PID's granted transactionid for the same XID; these represent 42 unique run/XID pairs after collapsing repeated 20 ms observations. The waiter was executing `generationCheckpointStore.assertLease` in every matched observation. The observed stage split was BUSINESS_RULE 52 observations, TEST_CASE 32, UNIT_TEST 2.
- This is direct evidence of transaction-level blocking at the `assertLease` query, with the blocking transaction holding the same row-lock/fence lifecycle. It is not 86 independent incidents or a measured wait-duration total. `pg_stat_activity` and `pg_locks` are read sequentially, so the pair is a same-round sampled identity rather than an atomic database snapshot.
- Only 7 of the 86 matched samples observed the blocker inside its JDBC `commit` span; 5 observed its `commit` span with `IO/WALSync` and 1 with `LWLock/WALWrite`. Other samples caught the holder in work before commit or between profiled JDBC spans. Consequently, these observations do not prove that WAL sync causes most of the lock wait.
- The per-call `assertLease` query span had median 1.78 ms, p95 62.69 ms, and max 271.65 ms across observed calls. The slowest calls were in TEST_CASE/ BUSINESS_RULE batches. This is consistent with occasional row-lock wait; the sampled wait data does not capture each complete interval.

### Flush, commit, pool, GC, and I/O

- Two highest diagnostic roots (5,116.2 ms and 4,655.7 ms) also had the highest summed SQL/commit time and the most lock samples. The third highest root (3,612.9 ms) had six transaction-ID lock samples and no sampled WAL wait. Thus WAL wait is neither necessary evidence for all tail samples nor the only measured contributor.
- Root 7 had 935.2 ms summed JDBC commit spans and 3,153.5 ms summed SQL execute spans; root 8 had 909.3 ms commits and 2,431.2 ms SQL. Concurrent span sums can exceed wall time and overlap. The largest individual commit was 296.1 ms. Commit API duration includes driver/server response and is not a measurement of WAL sync alone.
- Median Hibernate flush and auto-flush work was substantially smaller than aggregate query/commit spans. No evidence identifies Hibernate flush as the primary tail cause.
- Connection-acquisition spans were small (median sum 6.9 ms/root, p95 16.4 ms); 20 ms Hikari snapshots showed no waiting borrowers. This rules against a sustained pool queue in sampled windows, not brief unsampled waits.
- Process CPU is process-wide and can exceed root wall time with concurrent workers; it cannot establish that the host was CPU-saturated. GC was 23–42 ms in the 20 cold samples and does not explain multi-second tails.
- PostgreSQL reported `fsync=on`, `full_page_writes=on`, `synchronous_commit=on`, `wal_sync_method=fdatasync`; none was changed. `track_io_timing=off` and `track_wal_io_timing=off` mean WAL/data-file I/O timing counters are unavailable (reported zero), not that I/O wait is zero. `pg_stat_statements` was not installed and was not added.
- Cohort-wide `pg_stat_wal` deltas included 2,040 WAL writes and 2,036 syncs; `wal_write_time`/`wal_sync_time` remained zero because timing collection was disabled. `pg_stat_database` and `pg_stat_bgwriter` are cumulative across concurrent test/setup/observer activity and cannot attribute these deltas to individual roots. One timed checkpoint occurred during the cohort. No causal checkpoint write-amplification evidence was obtained.
- Windows host `_Total` physical-disk counter had 377 one-second-ish rows: median average-write time 0.217 ms, p95 5.571 ms, max 18.742 ms, maximum queue length 39. These are machine-wide counters, not mapped to the PostgreSQL container's data volume, so they cannot prove or exclude host-storage stalls.

## Root-cause ranking

| Rank | Candidate | Evidence and conclusion |
|---:|---|---|
| 1 | Worker contention on the run-level lease/fence row | **Observed and causally linked at sampled instants.** 86 matching waiter/blocker XID snapshots in `assertLease`, with the XID lock belonging to the peer worker. Strongest database bottleneck evidence. Exact wait-duration share of wall time is unknown. |
| 2 | Aggregate JDBC query and commit path | **Observed.** SQL and commit spans inflate in the two slowest roots. SQL spans include lock wait and nested work; commit spans do not isolate WAL sync. |
| 3 | WAL sync/write | **Observed but not established as primary.** WAL wait labels occur in tails, but are absent in other slow roots; WAL I/O timing is disabled, and host disk data is not PostgreSQL-volume-specific. |
| 4 | Hibernate flush | **Measured, not dominant in available evidence.** Flush spans are below query/commit sums and do not isolate SQL duration. |
| 5 | Connection pool | **No sustained queue observed.** Acquisition time small and no sampled waiters; sub-sample waits remain possible. |
| 6 | JVM GC / host CPU | **Not supported as primary.** GC is tens of milliseconds; CPU is process-wide and concurrent. |
| 7 | Checkpoint write amplification / host storage | **Undetermined.** Stats are cumulative/off or lack volume mapping; no causal measurement. |

## One follow-up optimization proposal (not implemented)

Run a **controlled persistence-worker concurrency experiment** for the same six-method fixture: compare concurrency 1 against concurrency 2, keeping source digest, database, provider mock, request count, lease checks, transaction boundaries and validations identical. This is an experiment on the existing bounded worker setting, not a DAG or scheduler redesign.

- **Expected benefit:** if contention on the one run-level lease row is a material part of p95, removing simultaneous writers may lower lock waits and tail variance. It is not expected to remove the sequential checkpoint/persistence work, and total wall time may worsen if useful method work loses parallelism. No latency benefit is claimed until measured.
- **Correctness risks:** worker-count configuration may govern both generation and persistence; changing it can alter scheduling and error order. Preserve checkpoint atomicity, fresh source/fingerprint checks, lease/fence assertions, retries/resume, and artifact output/provenance. Do not remove or defer `assertLease` to obtain a faster result.
- **Rollback:** keep current default concurrency and compare using a test-only/config override. Do not change production defaults unless the paired experiment shows repeatable end-to-end p50/p95 improvement without output, provenance, retry, or correctness regressions. Revert the override immediately otherwise.
- **GO/NO-GO:** **NO-GO for implementation/default change now.** First run the concurrency comparison in randomized/interleaved order with at least 20 paired valid samples and include independent review. The current investigation stops before that experiment.

## Verification, scope, and limitations

- Full offline regression completed: 1,951 tests, 0 failures, 0 errors, 65 skipped; build success. The five opt-in profiling suites account for 60 skips when their benchmark flags are absent; the remaining 5 skips are existing tests. Focused transaction instrumentation/lock tests previously passed against PostgreSQL; the advisory-lock test verifies observer capture, not lease-row behavior.
- The diagnostic and control benchmark each ran six generated UT fixture tests through compile/JUnit successfully. No provider HTTP request occurred. No production source, scheduler, database schema, WAL/durability setting, main branch, or remote was changed.
- Test-only instrumentation was independently reviewed for listener delegation, transaction correlation and concurrency safety. That review did not independently validate benchmark raw data or this report's analysis. The earlier Phase 3.6 independent review remained pending due reviewer-agent availability.
- No confidence interval is reported: the cohorts are sequential and not paired/randomized. Do not compare their percentiles as a causal treatment effect.
- No exact lock-wait duration, per-transaction WAL sync time, PostgreSQL-volume host I/O, pg_stat_statements breakdown, or production-provider latency was available. Do not infer or claim these from sampled labels or cumulative counters.
- No runtime optimization was implemented. The test-only profiler additions are local, uncommitted work on `perf/source-update-performance`; no changes were made to `main` and nothing was pushed.
