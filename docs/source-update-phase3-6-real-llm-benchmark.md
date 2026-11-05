# Phase 3.6 ? Offline Performance Benchmark

Ng?y: 2026-11-04, Asia/Bangkok. Nh?nh `perf/source-update-performance`, baseline `372f1bf`. B?o c?o n?y **thay th? k? ho?ch real LLM** tr??c ??; kh?ng c?n y?u c?u ph? duy?t chi ph?. Filename ???c gi? theo y?u c?u nh?ng to?n b? s? ?o l? **OFFLINE**. Kh?ng s? d?ng API key th?t ho?c g?i HTTP t?i provider LLM. Kh?ng thay production Java, scheduler, main ho?c push; `assertLease` optimization gi? **OFF**. B1+B2 v? Lombok reuse gi? nguy?n.

**K?t lu?n hi?u n?ng: NO-GO cho vi?c coi backend ?? ?n ??nh ho?c k?t lu?n kh?ng c?n t?i ?u th?m.** Ngay ? mock delay 0 ms, s?u method c? p95 **8,315 ms**, m?t m?u **10,651 ms** v?i JDBC commit interval union **9,124 ms**. C?n ?i?u tra commit/transaction latency tr??c khi th?m cache ho?c ??i scheduling. ??y kh?ng ph?i k?t lu?n vi ph?m m?t SLA do user ??t, c?ng kh?ng ph?i regression/speedup so v?i m?t implementation t?i ?u: phase n?y kh?ng c? thay ??i runtime ?? l?m A/B.

## 1. Protocol v? m?i tr??ng

- PostgreSQL th?t **15.17**, READ COMMITTED, database test `greytest_incremental_test_20261024`. Sau benchmark x?c minh 28 project v? 27 migration th?nh c?ng; m?i cohort ki?m tra project count kh?ng ??i, cleanup ri?ng fixture s? h?u, kh?ng truncate.
- Java 17.0.18, Spring Boot 3.3.4, Maven offline; Surefire JVM heap 512 MB/SerialGC cho c? b?n m?c. Full regression d?ng heap 768 MB/context cache 2, kh?ng d?ng th?i gian full-suite l?m s? benchmark.
- Deterministic in-process `@MockBean LlmClient` t? fixture hi?n c?. Test x?c nh?n Mockito mock, kh?ng c? bean OpenAI/Google client th?t, key env r?ng v? lease flag false. Runner kh?ng import `.env`, lo?i key variables kh?i child environment v? pin mock b?ng CLI. Kh?ng test auth/access/billing/model t? xa.
- Delay m?i mock call: **0 / 500 / 2000 / 5000 ms**, `Thread.sleep` t?i provider boundary, g?m c? call b? c? ? fail. ??y l? **requested delay**; th?i gian th?c c?a interval c?n c? scheduling v? mock payload processing. Delay 0 kh?ng c? ngh?a x? l? mock response b?ng 0.
- Concurrency **2**, stage-wide barriers gi? nguy?n. Batch automatic retry-attempts **0** nh? mock baseline; l?i ch?n l? non-retryable, sau ?? benchmark g?i resume ri?ng. Kh?ng ??i retry/lease semantics c?a production.
- **10 measured fixtures/workload/delay + 1 warm-up ri?ng**. M?i cohort c? th?m-method, s?a-m?t-method, s?a-s?u-method v? failure/resume. Cold + warm; failure + retry + warm: **90 measured windows/cohort, 360 t?ng**, 160 warm, 40 retry, 40 injected failure v? 120 successful cold windows. Warm-up v? smoke kh?ng v?o b?ng latency. C?c cohort ch?y n?i ti?p, kh?ng ch?y benchmark kh?c song song.
- Kh?ng lo?i outlier. p50 l? median, p95 n?i suy tuy?n t?nh; bootstrap percentile CI 95% v?i 5000 resamples/seed 36026 trong JSON. V?i n=10, **p95 ch? th?m d?**, CI r?ng. V? d? six/0 p50 CI 1985.72?4280.69 ms; p95 CI 2493.20?10650.85 ms. Kh?ng suy non-inferiority ho?c production SLA t? nh?ng m?u n?y.

Workload d?ng fixed synthetic `BenchmarkService`: base s?u method tr? 41; ONE_ADDED gi? s?u method r?i th?m method tr? 42; ONE_MODIFIED ch? ??i value0 th?nh 42; SIX/RESUME ??i c? s?u th?nh 42. Existing BR/TP/TC/UT l? **synthetic seed** trong harness c?, kh?ng ph?i developer tests c?a project th?t. ??nh gi? ch?t l??ng ch? tr?n proposal mock sinh cho target b? ?nh h??ng, kh?ng ph?i to?n b? baseline test corpus.

Hash source base/candidate tr??c/sau m?i fixture; k?t qu? x?c nh?n impact count 1/1/6/6, source digest v? canonical artifact/impact/provenance gi?ng nhau ? m?i m?u v? m?i delay. Canonical ch? chu?n h?a technical IDs/labels theo helper ?? c?; source logic/parameter order gi? nguy?n. Fresh fingerprint, snapshot proof v? precommit checks v?n ch?y.

## 2. End-to-end wall-clock

??n v? **gi?y, p50 / p95**, m?i ? 10 samples. Cold g?m Analyze ? GenerateAll tr? v?; warm/retry ch? GenerateAll. L??t th?t b?i ?o th?i gian t?i l?i, **kh?ng ph?i completion latency**. Kh?ng g?m upload/HTTP/FE polling, fixture setup/cleanup, evidence collection, Apply ho?c compile/JUnit.

| Workload | 0 ms | 500 ms | 2000 ms | 5000 ms |
| --- | ---: | ---: | ---: | ---: |
| Th?m m?t method cold | 1.129 / 1.892 | 3.575 / 4.358 | 9.620 / 10.413 | 21.353 / 22.513 |
| S?a m?t method c? artifact | 0.890 / 0.999 | 3.033 / 3.818 | 9.252 / 11.639 | 21.287 / 22.176 |
| S?a s?u method | 2.269 / 8.315 | 4.498 / 4.824 | 9.906 / 13.502 | 22.693 / 23.249 |
| Warm sau th?m method | 0.444 / 0.657 | 0.671 / 0.757 | 0.551 / 0.860 | 0.582 / 1.334 |
| Warm sau s?a m?t method | 0.367 / 0.430 | 0.420 / 0.464 | 0.498 / 2.913 | 0.516 / 2.132 |
| Warm sau s?a s?u method | 1.099 / 2.746 | 0.883 / 1.488 | 0.853 / 1.361 | 0.982 / 1.349 |
| L??t c? ? th?t b?i | 1.793 / 2.164 | 3.864 / 5.022 | 10.088 / 11.400 | 22.054 / 22.361 |
| Retry/resume sau l?i | 0.848 / 1.036 | 1.408 / 1.751 | 3.000 / 4.823 | 6.082 / 6.324 |
| Warm sau resume | 0.718 / 0.856 | 0.794 / 1.015 | 0.757 / 2.347 | 0.840 / 1.297 |


C?c m?c delay l? th? nghi?m ?? nh?y, kh?ng ph?i baseline/optimized A/B. JVM/DB variation c? th? kh?c gi?a cohort, n?n kh?ng l?y ch?nh l?ch median l?m causal backend speedup/regression. Gi? nguy?n c?c warm tail d? ch?ng kh?ng c? provider calls.

## 3. Backend th?c ?o v? provider latency m? ph?ng

??n v? ms. Provider union l? h?p c?c interval `provider.simulated`, kh?ng c?ng c?c calls song song. Backend exclusive elapsed = root wall ? provider interval union, l? elapsed ngo?i provider intervals, **kh?ng ph?i backend CPU ho?c to?n b? backend work**: backend worker kh?c c? th? ch?y trong khi provider mock ?ang ng?. C?c median b?n d??i kh?ng b?t bu?c c?ng ch?nh x?c th?nh median root.

| Delay / workload | E2E p50 | Provider union p50 | Backend exclusive p50 | Median provider/root |
| --- | ---: | ---: | ---: | ---: |
| 0 / ONE_ADDED | 1128.71 | 2.35 | 1126.36 | 0.22% |
| 0 / SIX_INDEPENDENT | 2268.62 | 4.05 | 2264.52 | 0.18% |
| 0 / RESUME_RETRY | 847.60 | 0.74 | 846.71 | 0.08% |
| 500 / ONE_ADDED | 3574.83 | 2033.74 | 1542.48 | 56.81% |
| 500 / SIX_INDEPENDENT | 4498.15 | 2096.66 | 2406.84 | 47.00% |
| 500 / RESUME_RETRY | 1407.51 | 512.66 | 892.79 | 36.59% |
| 2000 / ONE_ADDED | 9619.56 | 8039.16 | 1591.03 | 83.46% |
| 2000 / SIX_INDEPENDENT | 9905.69 | 8074.67 | 1844.27 | 81.38% |
| 2000 / RESUME_RETRY | 3000.10 | 2014.47 | 984.20 | 67.19% |
| 5000 / ONE_ADDED | 21352.62 | 20026.69 | 1322.55 | 93.81% |
| 5000 / SIX_INDEPENDENT | 22693.24 | 20069.23 | 2633.12 | 88.40% |
| 5000 / RESUME_RETRY | 6082.32 | 5007.77 | 1070.49 | 82.40% |


M?t method c? BR?TP?TC?UT, b?n calls ph? thu?c nhau: 5 gi?y/call t?o kho?ng **20 gi?y** provider union. S?u method c? **7 calls** nh?ng trong b?n stage waves v?i 2 workers, n?n provider union c?ng kho?ng 20 gi?y, **kh?ng ph?i 35 gi?y**. Kh?ng c? b?ng ch?ng t?ng worker s? gi?p fixture n?y: m?i downstream stage ch? c? hai batch, ?? c? hai workers. Kh?ng tri?n khai DAG.

0 ms l? m?i tr??ng g?n backend-only v?i x? l? mock response th?t, kh?ng ph?i zero-cost provider. ? 5 gi?y, median provider/root kho?ng **93.81%** cho added-one v? **88.40%** cho six; ??y l? t? l? do sleep m? ph?ng, kh?ng ?o provider network/queue/inference production. Token, billing, TTFT v? remote queue/network ?? **null**, kh?ng ??c l??ng t? k? t?.

## 4. Stage v? batch waiting

Median ms. Analyze envelope l? AOP call bao outer transaction commit. Stage timer hi?n c? b?t ??u sau initial access/status transition; v? v?y **kh?ng ph?i decomposition ??y ??** c?a root. Kh?ng c?ng stage medians, worker durations, query v? provider spans th?nh E2E.

| Delay / workload | Analyze envelope | BR | TP | TC | UT |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 / ONE_ADDED | 204.64 | 204.36 | 175.36 | 155.56 | 308.53 |
| 0 / ONE_MODIFIED | 172.75 | 150.23 | 134.15 | 122.80 | 239.97 |
| 0 / SIX_INDEPENDENT | 327.57 | 342.44 | 463.40 | 387.19 | 613.09 |
| 0 / RESUME_RETRY | n/a | 156.31 | 176.31 | 111.33 | 308.31 |
| 5000 / ONE_ADDED | 167.93 | 5250.02 | 5216.60 | 5195.75 | 5330.10 |
| 5000 / ONE_MODIFIED | 199.48 | 5232.50 | 5192.40 | 5174.73 | 5301.92 |
| 5000 / SIX_INDEPENDENT | 355.67 | 5367.05 | 5510.35 | 5348.08 | 5629.80 |
| 5000 / RESUME_RETRY | n/a | 181.33 | 244.89 | 125.77 | 5372.00 |


Mock calls theo stage: one = **1/1/1/1**, six/failure = **1/2/2/2**, retry = **0/0/0/1**, warm = **0/0/0/0**. Kh?ng c? request m?i khi valid checkpoint replay. Main thread ch? batch-map ch?a provider + backend c?a worker; th?i gian ?? kh?ng t? ??ng l? scheduling overhead c? th? b?.

`batch.readyToStart` gap l?n nh?t to?n dataset **35.35 ms** (kh?ng ??ng nh?t v?i pure queue wait). ? six/0 sample 8 tail, gap ch? **0.184 ms** trong khi map union **5419.55 ms**, worker union **5372.66 ms**. ?i?u n?y kh?ng ?ng h? quy tail nhi?u gi?y cho vi?c thi?u worker; worker c?n ph?i ho?n t?t persistence/validation trong barrier.

## 5. JDBC, checkpoint v? cache

Counts l? **correlated caller/worker spans** trong measured window, kh?ng ph?i server-global audit. SELECT = `executeQuery`, updates v? commits ghi ri?ng. Lease heartbeat m?i 20 gi?y d?ng thread kh?ng truy?n profiler context; **UPDATE/commit c?a heartbeat ch?a ???c ?o**, ??c bi?t ? 5-second runs. Kh?ng b?o ch?ng b?ng 0; total server UPDATE/commit/heartbeat latency unavailable. Kh?ng s?a profiler/runtime gi?a c?c cohort ?? ?p counts b?ng nhau.

| Workload | SELECT | Updates | Commit calls | Mock calls | Checkpoint skips / regenerated |
| --- | ---: | ---: | ---: | ---: | --- |
| Added-one cold | 254 | 34 | 69 | 4 | 0 / 4 |
| Modified-one cold | 235 | 41 | 69 | 4 | 0 / 4 |
| Six cold | 506 | 107 | 162 | 7 | 0 / 24 |
| Added-one warm | 171 | 17 | 40 | 0 | 4 / 0 |
| Modified-one warm | 144 | 17 | 40 | 0 | 4 / 0 |
| Six warm | 262 | 17 | 104 | 0 | 24 / 0 |
| Retry sau l?i, 39/40 samples | 282 | 23 | 111 | 1 | 22 / 2 |
| Retry sau l?i, 1/40 samples | 288 | 27 | 111 | 1 | 20 / 4 |

Cold cache misses one=4, six=7. Response-cache hit count 0; warm skip tr??c provider/cache lookup n?n **0 response-cache hit kh?ng c? ngh?a cache/checkpoint h?ng**. 160 warm windows ??u 0 mock calls, exact output/provenance digest trong c?ng fixture kh?ng ??i. Gi? m?i mandatory validation/fresh read; kh?ng c?t query ch? ?? ??p s?.

? delay 500 ms, m?t failure/resume sample c?n regenerate 4 artifact thay v? 2: fail-next ?p d?ng v?o UT batch t?i tr??c, batch sizes kh?c nhau. Final canonical output b?ng nhau. Gi? sample trong histogram v? percentile; kh?ng so failure/retry latency nh? c?ng l??ng c?ng vi?c ho?c causal speedup ? sample ??. Kh?ng s?a scheduler/failure policy ?? lo?i s? kh?c bi?t n?y.

## 6. Tail latency: b?ng ch?ng v? ph?m vi attribution

Six/0 sample **8**, wall **10650.85 ms**, CPU process **1734.38 ms**, GC MXBean delta **26 ms**:

- JDBC commit union **9124.30 ms**, execution union **907.93 ms**, transaction-hold union **10177.62 ms**. C?c nh?m n?y ch?ng l?p; **kh?ng c?ng** ch?ng. Root c? kho?ng **85.7%** wall overlap ?t nh?t m?t commit call.
- Commit d?i nh?t **2316.14 ms**, TC batch 1; Analyze commit **837.55 ms**; c?c status/checkpoint commit kh?c kho?ng 600?729 ms. Analyze envelope **1115.18 ms**.
- Provider delay 0; mock processing r?t nh? so v?i tail. Ready-to-start gap 0.184 ms, GC 26 ms kh?ng gi?i th?ch 9.12 gi?y commit intervals.

Six/0 sample 5 c?ng wall **5459.68 ms**, commit union **4081.47 ms**. Six warm/0 sample 9 wall **2860.37 ms**, commit union **2214.53 ms**, **0 mock calls**. C?c m?u ??u ???c gi? nguy?n. ??y l? attribution t?i **JDBC commit boundary**, kh?ng t?ch ???c DB server execution, WAL/storage, network ho?c host scheduling trong benchmark n?y. [Phase 3.5](source-update-phase3-5-tail-latency.md) c? WAL/lock observations ri?ng; kh?ng g?n h?i t? t?t c? tail m?i cho c?ng wait event khi main series n?y kh?ng b?t server sampler.

GC counter delta l?n nh?t to?n dataset **296 ms**; process-wide/approximate boundary, kh?ng ph?i JFR pause decomposition. Counts parse/context/fingerprint v? CPU/allocation inclusive c?n trong raw metrics. Backend context/validation c? chi ph? th?t, nh?ng kh?ng c? b?ng ch?ng b? ch?ng l? c?ch an to?n gi?i quy?t tail commit. M?i commit call kh?ng ??ng ngh?a m?t fsync ri?ng, kh?ng t?t fsync/synchronous_commit ?? ??t t?c ??.

## 7. Generated UT v? correctness

**56/56 mock-generated UT proposals compile/JUnit pass** ? warm-up quality runs c?a b?n cohort; 14 smoke proposals pass ri?ng, kh?ng c?ng v?o main 56. Original proposals ???c l?u tr??c compile ? `target/phase3-6-offline/<delay>/quality/<workload>/original-proposals.json`. Kh?ng s?a generated test ho?c production source ?? l?m pass. Compile/JUnit ? ngo?i root, th?i gian k?t h?p:

| Delay ms | Workload | Proposals compile/JUnit pass | Compile + JUnit ms |
| --- | --- | ---: | ---: |
| 0 | ONE_ADDED | 1 | 2324.30 |
| 0 | ONE_MODIFIED | 1 | 1176.30 |
| 0 | SIX_INDEPENDENT | 6 | 6141.29 |
| 0 | RESUME | 6 | 5860.19 |
| 500 | ONE_ADDED | 1 | 2089.44 |
| 500 | ONE_MODIFIED | 1 | 1088.78 |
| 500 | SIX_INDEPENDENT | 6 | 7649.46 |
| 500 | RESUME | 6 | 3932.79 |
| 2000 | ONE_ADDED | 1 | 2296.17 |
| 2000 | ONE_MODIFIED | 1 | 700.71 |
| 2000 | SIX_INDEPENDENT | 6 | 3627.29 |
| 2000 | RESUME | 6 | 2959.58 |
| 5000 | ONE_ADDED | 1 | 1738.74 |
| 5000 | ONE_MODIFIED | 1 | 897.24 |
| 5000 | SIX_INDEPENDENT | 6 | 5791.81 |
| 5000 | RESUME | 6 | 5254.50 |

Helper compile/ch?y **t?ng proposal ri?ng** b?ng GreyTest test classpath (Spring/JUnit ?? c?), kh?ng ph?i standalone Maven target project ho?c merged Apply suite. M?i proposal mock c? m?t @Test. K?t qu? n?y ch?ng minh fixture/code ???c sinh ch?y ???c trong scope ??; **kh?ng ??nh gi? n?ng l?c, coverage ho?c mutation quality c?a LLM th?t**. Kh?ng d?ng developer tests t? PetClinic/project ngo?i v? kh?ng ch?y request th?t.

C? 40 provider failures c? ? trong measured windows, m?i failure resume ???c ch?y ri?ng. 920 calls l? **mock invocations**, s? outbound LLM provider requests **0**. Kh?ng ??m nhi?u failed nested spans th?nh nhi?u l?i. Kh?ng failed correlated JDBC spans; kh?ng observed unexpected SQL error/timeout/deadlock trong benchmark. Heartbeat kh?ng trace, n?n kh?ng d?ng c?u n?y nh? b?o ??m server-global kh?ng l?i.

TDD: `offline-delay-red.log` c? 3 failures mong ??i tr??c implementation; `offline-delay-green.log` **14 pass** sau ??. Smoke **9 windows/14 quality pass**. Focused **132 tests = 131 pass + 1 opt-in microbenchmark skip, 0 failure/error**, g?m PostgreSQL lease/fencing/expiry-after-lock/stale-worker rollback ? hai flag values, durable checkpoints, snapshot/fingerprint/diff/batch v? delay/interruption. Kh?ng H2 thay concurrency.

Full regression: **1934 tests = 1881 pass + 53 skip, 0 failure/error, 153 suites**, `target/offline-full-final.log`, exit 0 / BUILD SUCCESS. Skip g?m b?n opt-in profile classes ?12, oracle acceptance 2, golden manifest 1, scan benchmark 1 v? lease microbenchmark 1. PostgreSQL lease correctness v? delay tests kh?ng skip. C? protected edits/Apply, stale source/config, checkpoint retry/concurrent worker, semantic validation v? generated-file checks trong full suite. Regression HTTP transport tests d?ng loopback server/key gi?; kh?ng ph?i request t?i provider th?t. ?? ki?m tra log/SHA v? s? suite kh?p; kh?ng d?ng s? test c? ho?c report XML t?n t? l??t tr??c.

**Independent review: PENDING ? tool quota unavailable.** Reviewer `phase36_offline_review` ???c g?i theo AGENTS nh?ng th?t b?i v? usage limit tr??c khi tr? finding. Kh?ng c? review pass/no-findings ?? tuy?n b?. Benchmark v? regression verification do primary agent th?c hi?n; gate review code ??c l?p ch?a ho?n t?t.

## 8. GO/NO-GO v? ?? xu?t ti?p theo

**NO-GO ch?t backend ?? ?? ?n ??nh/kh?ng c?n ?i?u tra th?m.** Median happy-path m?t method d??i kho?ng 1.2 gi?y ? zero delay, nh?ng six/0 tail 10.65 gi?y v? warm tail kh?ng c? provider v?n xu?t hi?n. Kh?ng ??t SLA h?i t?; k?t lu?n ch? trong fixture/instrumentation hi?n t?i. Ch?a c? optimized-runtime A/B, n?n **kh?ng c? measured speedup ?? rollout**, gi? lease optimization OFF.

C?n ti?p t?c, theo th? t? nh? nh?t:

1. T?i hi?n commit tails v?i PostgreSQL/host I/O telemetry ???c ph?p: server wait, WAL sync/write timing, connection/network v? storage/VM. Gi? durability v? lease semantics. Kh?ng k?t lu?n disk/VM l? nguy?n nh?n cu?i c?ng t? JDBC wall.
2. Ph?n lo?i checkpoint/status persistence tr?n critical path, t?ch read-only commits v? writes. Ch? thi?t k? gi?m write/transaction khi c? proof v? protected edits, fresh reads, atomic checkpoints/fencing; kh?ng t? g?p transaction qua provider.
3. Gi? selective generation/checkpoints hi?n c?: warm ?? 0 calls, one-method ch? 4 required stage calls. Kh?ng c? b?ng ch?ng c?n th?m worker ho?c DAG cho fixture n?y; kh?ng t?i tri?n khai source-only cache Phase 3.2 ho?c c?t dependency evidence.

Kh?ng s?a production Java trong phase n?y; thay ??i gi? l?i ch? l? test harness, delay self-check, evidence/summary scripts v? docs. Nh?ng gi?i h?n ch?a x?c minh: provider th?t/rate limits/token latency/TTFT/network/quality, large-project source complexity, production DB traffic, merged-target-suite compile, exact heartbeat/server-global counts, isolated profiler overhead v? stable tail v?i n l?n. Kh?ng suy t?c ?? LLM/production t? b?ng mock.

## 9. Reproduce v? provenance

```powershell
# Ch?y n?i ti?p, t? backend root; runner kh?ng d?ng dotenv/key/provider th?t.
foreach ($delay in 0,500,2000,5000) {
  python docs/benchmarks/run-source-update-offline.py "offline-main-$delay" `
    '-Dtest=OfflineSourceUpdateBenchmarkTest#profileCriticalPath' `
    '-De2e.profile=true' '-Doffline.profile=true' '-Doffline.samples=10' `
    "-Doffline.delay=$delay" "-De2e.outputDirectory=target/phase3-6-offline/$delay"
}
python docs/benchmarks/summarize-offline-source-update.py --self-check
python docs/benchmarks/summarize-offline-source-update.py `
  target/phase3-6-offline/0/samples.json target/phase3-6-offline/500/samples.json `
  target/phase3-6-offline/2000/samples.json target/phase3-6-offline/5000/samples.json `
  --output target/phase3-6-offline/recomputed-summary.json
```

[Results JSON](source-update-phase3-6-results.json) gi? **m?i numeric sample**, stage/runtime metrics, provider intervals, query purpose counts, slow JDBC spans, source/canonical digests v? p50/p95/CI; `rawSources` c? SHA-256 c?a raw JSON ??y ??. Full raw spans ? target, kh?ng export prompt/credential. Monotonic root/stage/query/worker/batch correlation gi? nguy?n; no overlapping-duration sum. S? ch?a ?o ?? null.

Code files: `OfflineSourceUpdateBenchmarkTest.java`, `OfflineProviderDelayTest.java`; hai helper s?n c? trong `PairedSourceUpdateBenchmarkTest` ch? ??i package visibility ?? reuse. Kh?ng thay runtime profiler ho?c production class. Rollback: kh?ng b?t `offline.profile`/`e2e.profile`; production default kh?ng ??i. `.env.example`/`application.yml` dirty c? s?n c?a user kh?ng thu?c commit phase n?y. Kh?ng main/push/merge.

Raw measurement core (samples/summary/quality/digests) ?? ???c primary t?i t?nh t? b?n raw files v? kh?p ch?nh x?c sau JSON serialization. Verification/log hashes v? tr?ng th?i review trong deliverable ???c b? sung sau full suite; l?nh reproduce ghi summary ri?ng ? target ?? kh?ng x?a metadata verification n?y. Independent review v?n pending, kh?ng thay b?ng self-review.

Commit harness/self-check/scripts: **`ceefbbe`** (full tests pass, independent review pending). B?o c?o/evidence commit ri?ng; xem commit ch?a file n?y. Source hashes trong JSON kh?p Git blobs, kh?ng c? production Java diff so v?i `372f1bf`. Sau full suite DB v?n 28 project/27 migration th?nh c?ng. `main` gi? `f0b9f3d7d1204be6c2bb246785e7c710d19c1d06`; kh?ng push/merge.
