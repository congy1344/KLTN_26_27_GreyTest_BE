# Phase 3.5 — Source Update tail latency

Ngày đo: 2026-11-04, Asia/Bangkok. Nhánh `perf/source-update-performance`, baseline `e5d0fcd`. Đọc trước: [Phase 3.3](source-update-phase3-3-performance.md), [Phase 3.4 profiling](source-update-phase3-4-performance.md), [Phase 3.4 implementation](source-update-phase3-4-implementation.md).

## Phạm vi và protocol

Không sửa production Java, schema, scheduler, prompt/provider contract, validation, fingerprint hoặc provenance. B1+B2 và Lombok directory reuse giữ nguyên. Không đưa source-only cache Phase 3.2 trở lại. Chỉ mở rộng test profiler và bộ tổng hợp evidence. Không gọi LLM thật.

A là guard hai SQL; B là guard một SQL qua scoped feature flag hiện có. PostgreSQL 15.17, READ COMMITTED, JVM Java 17, Surefire fork 512 MB/SerialGC, mock xác định, batch concurrency 2, provider retry-attempts 0 giữ nguyên giữa A/B. Không chạy các series đồng thời.

- Diagnostic sáu method: 20 cặp; diagnostic một method/129 file/failure-resume: 6 cặp mỗi workload.
- Control: 20 cặp cho **từng** workload trên; tắt JDBC/service profiler, JFR và PostgreSQL sampler. Giữ các metric runtime sẵn có và GC MXBean counters.
- Mỗi workload mỗi series có 1 cặp warm-up riêng, không đưa vào thống kê. Cân bằng AB/BA rồi shuffle seed 32026 như harness hiện có. Không loại outlier.
- Source fixture SHA-256 toàn bộ file base/candidate phải bằng nhau trong từng cặp. Database fixture được tạo từ cùng factory/schema, với ID kỹ thuật riêng và cleanup theo project sở hữu; **không phải hai lượt dùng cùng row ID**. Harness so sánh impact, artifact và provenance canonical sau repeat/resume. Cold là checkpoint cold; không phải OS page-cache/JIT cold, vì setup/source proof chạy trước timer.
- Cold root gồm Analyze → GenerateAll; warm và resume chỉ GenerateAll. Thời gian chờ người dùng thao tác giữa lỗi và retry không nằm trong timer. Failure window được tách khỏi completion latency. Compile/JUnit chất lượng và thu thập evidence nằm ngoài root.

Root wall được đo một lần bằng monotonic clock. p95 nội suy tuyến tính. Median paired ratio là median của B/A theo từng cặp, không phải tỷ lệ hai median độc lập. CI 95% percentile bootstrap 10.000 lần; CI p50/p95 resample nguyên cặp. Với 20 cặp, CI tail vẫn rộng và nhạy với vài mẫu; không diễn giải CI như cam kết production SLA.

## Instrumentation và giới hạn attribution

`CriticalPathProfiler` thêm PostgreSQL backend PID lấy từ native connection, không thêm SQL lấy PID. `TailLatencyObserver` dùng một connection riêng ngoài Hikari, lấy `pg_stat_activity`/`pg_blocking_pids` với fixed delay 20 ms. Chỉ giữ quan sát khi cùng JDBC span còn active trước và sau sampler query; lưu run/update/project, PID, thread, stage, batch và target IDs. Không thu SQL text, bind parameters hay prompt/source. Sampler không đổi DB settings.

Pool samples ghi active/idle/total/awaiting. `jdbc.getConnection` đo wall acquire; không đồng nhất toàn bộ acquire time với pool starvation. Database wait observations là **mẫu rời rạc**, không phải duration chính xác. Khoảng giữa hai mẫu không chứng minh wait liên tục; absence không loại trừ wait ngắn. Query/commit/transaction unions chỉ loại overlap trong từng nhóm, không cộng các nhóm thành root.

GC MXBean count/time delta được ghi trong cả control/diagnostic, là process-wide counter quanh boundary gần root. Diagnostic JFR giữ `GCPhasePause` và `GarbageCollection` kể cả không có application thread. Dùng pause interval union để phân tích stop-the-world; không cộng pause và GC cycle cùng nhau. ThreadPark/Monitor/SocketRead JFR có threshold 1 ms và attribution theo thread/window; chúng có thể chồng nhau, không được cộng thành tổng wall.

Control không có query/lock trace. Không quy một control outlier cho một PostgreSQL event chỉ vì diagnostic có event đó. Docker host logs không truy cập được trong môi trường này; không kết luận về physical disk, VM checkpoint hay container throttling nếu chỉ có server wait observations.

## Kết quả

**NO-GO cho bật mặc định.** Giảm query được tái xác nhận, nhưng chưa chứng minh giảm E2E ổn định. Tail diagnostic được định vị tới **WAL commit stalls và transaction-lock convoy giữa batch workers**. Đây là nguyên nhân quan sát được trên fixture hiện tại, không phải bằng chứng hồi tố cho hai outlier Phase 3.4 hoặc tốc độ LLM production.

### Control — 20 cặp hợp lệ mỗi hàng

Đơn vị ms. Giữ đủ **360 windows**, không loại outlier. Từng cặp có cùng lượng artifact regenerated; failure không được coi là completion. Tất cả resume retry control đều regenerate 2 artifact, checkpoint skip 22, provider call 1.

| Workload | A p50 / p95 | B p50 / p95 | Median paired B/A | CI paired ratio 95% |
| --- | ---: | ---: | ---: | --- |
| One method cold | 690.20 / 1861.37 | 613.65 / 2197.47 | 0.9863 | 0.9132–1.0945 |
| One method warm | 309.65 / 823.07 | 346.28 / 1174.44 | 1.0372 | 0.9165–1.2862 |
| Six methods cold | 1295.69 / 2003.07 | 1237.35 / 1794.88 | 1.0075 | 0.8999–1.0775 |
| Six methods warm | 578.67 / 991.51 | 528.95 / 957.23 | 0.9205 | 0.8644–1.1291 |
| 129 files cold | 1071.96 / 1393.15 | 1027.94 / 1508.15 | 0.9572 | 0.9391–1.0547 |
| 129 files warm | 463.28 / 580.38 | 453.05 / 832.57 | 0.9881 | 0.9413–1.1109 |
| Injected failure | 1240.28 / 3141.24 | 1168.43 / 2076.53 | 0.9228 | 0.8591–0.9531 |
| Resume retry | 686.07 / 2026.30 | 638.76 / 1691.05 | 0.9370 | 0.8789–1.0108 |
| Warm after resume | 573.87 / 1363.26 | 555.73 / 1265.36 | 0.9434 | 0.8890–0.9715 |

Có tín hiệu giảm median ở warm-after-resume; thời gian tới injected failure cũng giảm nhưng không phải completion improvement. Cold one/six/129 và resume retry đều có CI paired ratio đi qua 1. Không đạt gate E2E chung hoặc chứng minh non-inferiority tail chặt chẽ.

CI percentile bootstrap cho wall quantiles (ms); CI còn lại và mọi numeric sample trong [control JSON](benchmarks/phase3-5-control.json), trường `wallBootstrap95`:

| Workload | A p50 CI | B p50 CI | A p95 CI | B p95 CI | CI tỷ lệ p95 B/A |
| --- | --- | --- | --- | --- | --- |
| One cold | 604.38–835.70 | 593.47–710.86 | 914.20–2797.50 | 831.83–2538.84 | 0.698–2.175 |
| Six cold | 1233.06–1357.52 | 1145.59–1556.27 | 1382.17–2098.73 | 1588.05–2117.93 | 0.815–1.240 |
| 129 cold | 1035.35–1225.35 | 992.49–1123.67 | 1267.83–1395.21 | 1147.82–1567.67 | 0.908–1.181 |
| Resume retry | 634.21–839.28 | 624.45–677.35 | 1204.92–3160.04 | 711.39–2041.81 | 0.378–1.645 |
| 129 warm | 436.87–505.20 | 413.26–523.95 | 512.13–603.23 | 595.30–1140.59 | 1.003–2.025 |

129-file warm có tín hiệu tail xấu hơn trong series này; không chạy nhiều workload rồi chọn riêng một CI thuận lợi. Chưa hiệu chỉnh multiple comparisons, số tail samples nhỏ, không tuyên bố đây là regression production chắc chắn. Đủ lý do **không rollout default**.

### Stage-level control

Median ms A→B từ runtime metrics sẵn có, không thêm JDBC profiler vào control:

| Workload | Analyze metric | BR | TP | TC | UT |
| --- | ---: | ---: | ---: | ---: | ---: |
| One cold | 83.04→79.03 | 121.47→111.52 | 113.23→104.69 | 99.09→94.98 | 164.73→160.49 |
| Six cold | 186.94→185.51 | 224.47→209.72 | 280.79→275.29 | 183.30→172.10 | 312.26→302.71 |
| 129 cold | 388.38→364.12 | 221.07→217.55 | 106.65→102.86 | 104.51→104.16 | 171.07→152.98 |
| Resume retry | n/a | 133.10→129.74 | 151.35→146.59 | 97.55→90.30 | 217.36→200.81 |

Boundary quan trọng: Analyze runtime metric nằm trong service, không gồm outer transaction commit; stage timer bắt đầu sau initial access/status transition. Đây là measured sub-boundaries, **không phải decomposition đầy đủ** của root. Các median không cộng thành root median. Diagnostic có AOP service envelope và JDBC commit để lấp khoảng attribution này; ví dụ Analyze envelope ở tail dưới là 815 ms, metric nội bộ chỉ 357 ms.

### Query reduction và checkpoint overhead

164 diagnostic windows; [six diagnostic](benchmarks/phase3-5-six-profile.json), [other diagnostic](benchmarks/phase3-5-other-profile.json).

| Workload | Guard checks | Lease SQL A→B | Total SELECT A→B | JDBC updates | JDBC commits |
| --- | ---: | ---: | ---: | ---: | ---: |
| One cold | 25 | 50→25 | 254→229 | 34 | 69 |
| Six cold | 48 | 96→48 | 506→458 | 107 | 162 |
| 129 cold | 25 | 50→25 | 254→229 | 34 | 69 |
| One/129 warm | 17 | 34→17 | 171→154 | 17 | 40 |
| Six warm / warm after resume | 17 | 34→17 | 262→245 | 17 | 104 |
| Resume retry, equal partial work | 20 | 40→20 | 282→262 | 23 | 111 |

Bảng counts đúng với từng cặp cùng lượng công việc; không lấy mean để che biến động. Guard count bao gồm external `assertLease` và self-invocation trong `save()` của `complete`/`mark`. AOP trực tiếp không đếm self-invocation, nên bộ tổng hợp đối chiếu code path và số complete/mark calls; SELECT delta bằng đúng tổng guard checks. Không gọi 162 JDBC commits là 162 fsync: read-only/group commit có hành vi khác nhau.

Sáu-method cold chỉ giảm **48/506 = 9.49% SELECT**, không giảm 107 updates hoặc 162 commit calls. Các transaction/checkpoint/status boundaries và thời gian giữ row lock không thay đổi về semantics. Một round trip bị bỏ không loại được commit stall hoặc chờ worker khác giữ lease lock.

Cold one/129 có 4 calls, six có 7, warm 0, resume retry 1. Cold cache miss tương ứng 4/7; warm dùng checkpoint skip 4 hoặc 24 artifact và skip đủ 4 stage, nên **0 response-cache hit không phải cache bị hỏng**: pipeline đã skip trước provider lookup. Resume control skip 22 và generate 2. Không thay fingerprint để tạo hit giả.

Một cặp **diagnostic RESUME pair 1** có partial work khác nhau: A regenerate 20 trước lỗi rồi retry 4; B regenerate 22 rồi retry 2. Mock fail-next áp dụng vào batch gọi trước, kích thước batch UT khác nhau. Final canonical output/provenance bằng nhau, không có artifact mất/trùng. Giữ nguyên các mẫu trong raw/descriptive statistics, ghi `differentPartialWorkPairs`; **không dùng diagnostic resume aggregate làm bằng chứng causal speedup**. Control không có mismatch này. Không sửa scheduler hoặc failure semantics để ép số đẹp.

### Tail root cause có correlation

Tổng diagnostic: **894 matched DB observations**, **6852 pool observations**, **0 observer errors**, 178 observations có blocker. Phân bố wait labels: IO/WALSync **570**, LWLock/WALWrite **93**, IO/WALWrite **23**, Lock/transactionid **178**, không wait label **28**, Client/ClientRead **2**. Đây là sample counts, không phải % wall hoặc duration cộng dồn. Theo [PostgreSQL 15 monitoring](https://www.postgresql.org/docs/15/monitoring-stats.html), WALSync là chờ WAL tới durable storage, transactionid là chờ transaction kết thúc.

**Ví dụ 1 — commit stalls trên critical path**, six diagnostic B pair 1, run `8961c45b-3c40-4432-b116-514a36fc579c`:

- Root **5448.76 ms**, process CPU **2375 ms**; GC MXBean 18 ms, JFR pause tổng overlap **17.30 ms**. Mock provider union **1.85 ms**.
- JDBC commit union **2915.54 ms**, execution union **1312.13 ms**, transaction-hold union **4445.82 ms**; chúng chồng nhau, không cộng thành root.
- 57 WALSync, 23 LWLock/WALWrite, 6 IO/WALWrite và 7 transactionid observations trong root. Long commits: Analyze **455.4 ms**, BR status **554.6 ms**, UT batch 1 **534.3 ms**.
- Stage sub-boundaries BR/TP/TC/UT **716.1 / 548.1 / 1099.8 / 1437.7 ms**. `CompletableFuture.join` có 4 ThreadPark events, inclusive 3247.69 ms; đây là main thread đợi workers, không thêm 3.25 giây độc lập vào root.

**Ví dụ 2 — WAL stall lan thành lease-lock convoy**, six diagnostic B pair 0, run `ab56d8a4-7c99-4912-9c20-0b5475b9a571`:

| Correlated interval, tương đối đầu run | JDBC span / PID / worker | Bằng chứng |
| --- | --- | --- |
| 1854.36–2597.86 ms | span 18731, PID 3185, thread 86, UT batch 1 | `assertLease` 743.50 ms; 24 transactionid observations, blocker PID 3184 |
| 1883.25–2597.85 ms | span 18777, PID 3184, thread 87, UT batch 2 | commit 714.60 ms; 23 observations WALWrite/WALSync |
| 1812.49–2597.94 ms | transaction.hold, thread 87, batch 2 | Transaction chứa commit của blocker |

Ở cùng sampler round tại khoảng **1892.64 ms**, PID 3185 bị PID 3184 block, PID 3184 đang LWLock/WALWrite. Tới round **2569.09 ms**, waiter vẫn transactionid, holder được quan sát IO/WALSync. Không tuyên bố wait liên tục giữa các round. Hai JDBC calls kết thúc gần đồng thời sau holder commit; **không cộng 743.50 + 714.60 ms**. Đây là bằng chứng tại sao guard một SQL vẫn có tail: nó vẫn phải lấy đúng row lock, và lock vẫn giữ qua transaction commit.

**Warm cũng có tail khi không gọi provider**: six B pair 0 warm **2653.65 ms**, commit union **1791.27 ms**, 50 WALSync observations, provider calls 0, GC 5 ms. Baseline warm pair 1 cũng **2210.54 ms**, commit union **1520.58 ms**, 45 WALSync observations. Stalls không chỉ xuất hiện ở B.

DB settings đọc sau benchmark: synchronous_commit=on, fsync=on, wal_sync_method=fdatasync, checkpoint_timeout=300 s; track_io_timing và track_wal_io_timing đều off. Vì vậy không có per-run exact server I/O duration, không diễn giải các counter timing chưa bật là zero. Không đổi durability để đạt tốc độ.

### Các chi phí khác và điều chưa chứng minh

- **Pool**: awaiting maximum 0 trên 6852 samples; getConnection union lớn nhất mỗi root **11.86 ms**. Không có bằng chứng pool starvation hàng giây trong diagnostic; không loại trừ wait ngắn hơn sampling period.
- **GC**: six diagnostic pause overlap lớn nhất **32.16 ms**; other diagnostic **231.31 ms** ở 129-file A pair 2. GC có đóng góp nhưng không giải thích tail commit nhiều giây nêu trên. Control GC counter delta lớn nhất 193 ms; không có JFR control để chia pause theo stage.
- **JVM contention**: JFR JavaMonitorEnter inclusive tổng lớn nhất/root 106.68 ms (six), 161.09 ms (others). Trace lớn ghi chờ ConcurrentHashMap memoize và SourceSnapshotSession monitor; không thấy chúng giải thích commit stall hàng giây. Park/idle worker counts bao gồm worker chờ task và polling cancel queue, không quy tất cả thành scheduler bottleneck.
- **Context/validation**: six cold vẫn 78 class parses, 24 method parses, source-only cache không được tái đưa vào. Ở B tail pair 1, semantic metric 277.70 ms, input fingerprint 484.85 ms, output fingerprint 108.29 ms; đây là inclusive worker durations có overlap. Source/context work còn đáng kể, nhất là 129-file workload; không sửa hoặc skip checks. Context input/output giữ canonical equality.
- **Mock provider**: provider.local union lớn nhất/root **2.23 ms** trong diagnostic; không artificial sleep/network/remote queue. Actual LLM input/output token usage, cost và provider thật **không đo được**, không báo bằng zero. Prompt/response character metrics còn trong raw JSON; chúng không phải token usage.
- **Profiler overhead**: six diagnostic A/B p50 **1607.62/1572.35 ms**, so với control **1295.69/1237.35 ms**; 129 diagnostic **2365.41/2355.21 ms**, control **1071.96/1027.94 ms**. Instrumentation ảnh hưởng đáng kể, nhưng các series chạy nối tiếp, nên chênh lệch này gồm temporal/JIT/DB variation, **không phải isolated overhead estimate**. Không dùng diagnostic absolute latency thay control.
- **Control outliers**: resume retry A pair 15 **3160.04 ms**, CPU 390.63 ms, GC 3 ms; one A pair 1 **2797.50 ms**, CPU 1171.88 ms, GC 7 ms. Chúng phù hợp với giả thuyết có wait đáng kể nhưng không có query trace để chứng minh cùng WAL chain. Hai outlier sáu method Phase 3.4 cũng chưa thể quy nguyên nhân hồi tố. Tail 6–7 giây cũ không tái xuất hiện trong control Phase 3.5.

## Correctness và quyết định

Benchmark hoàn tất **524 measured windows**, **118 canonical fixture pairs** và **56 generated UT compile/JUnit pass** trên source candidate không sửa. Warm/retry kiểm tra exact output/provenance digest trong cùng fixture và 0 warm LLM calls. 52 failure windows được cố ý chèn provider failure; không bỏ khỏi raw. Diagnostic có **0 failed JDBC spans**, không observed unexpected timeout/deadlock; không biến kết quả này thành bảo đảm cho mọi tải production.

Focused verification: **127 tests, 126 pass, 1 skip**, 0 failures/errors (`target/tail-correctness.log`). Bao gồm real PostgreSQL lease tests ở cả hai flag values, lock-wait expiry/replacement/fencing/stale-worker rollback và durable stores; snapshot/fingerprint/checkpoint/batch/diff tests. Skip là opt-in lease microbenchmark đã đo Phase 3.4, không phải concurrency test. Observer self-check trước benchmark: **10 pass**, gồm PgSleep native wait/correlation, cleanup sau failure và process-wide GC event không có application thread (`target/tail-observer-check.log`).

Full regression: **1917 tests = 1876 pass + 41 skip, 0 failures/errors**, 151 suites, `target/tail-full-final.log`. Bao gồm protected edits/Apply, source/config mutation trong provider window, checkpoint retry/resume, semantic validator và lease integration tests. 41 skip: 3 profiler classes × 12, approved-oracle 2, PetClinic golden manifest 1, scan benchmark 1, lease microbenchmark 1. Real PG lease correctness và observer test không skip. Full-suite CLI bật optimized branch; dedicated lease tests tự chạy cả true/false và xác nhận rollback SQL count. JVM full-suite dùng heap 768 MB/cache-context 2 như Phase 3.4; không dùng thời gian full-suite làm benchmark A/B.

Independent read-only reviewer `phase35_independent_review`: **không actionable findings**. Reviewer đọc profiler/observer lifecycle, native PID/span mapping, GC attribution, source/canonical assertions, bootstrap và production `save/assertLease` paths; chạy summary self-check và tái tạo cả ba evidence JSON trong bộ nhớ, khớp chính xác candidate files và rawSource hashes. Reviewer xác nhận 524 windows/118 semantic pairs/894 DB samples/6852 pool samples/0 observer errors và hai row partial-work mismatch đã ghi rõ. Reviewer không rerun Java/PG benchmark, không sửa file; verification chạy bởi primary agent theo logs ở trên.

**Recommendation: giữ default OFF**, không tối ưu tiếp assertLease trong phase này. Correctness không cần đổi để giải thích query reduction không dẫn tới speedup: dữ liệu đã chỉ ra commit durability wait và lock convoy vẫn tồn tại. E2E CI của workload chính chưa chứng minh cải thiện; p95 có rủi ro và uncertainty lớn. Rollback tiếp tục dùng `GREYTEST_SOURCE_UPDATE_SINGLE_STATEMENT_LEASE_CHECK_ENABLED=false` / `greytest.source-update.single-statement-lease-check-enabled=false`, đúng implementation hai SQL đã test. Không flip runtime flag, không push/main.

Bước tiếp theo chỉ nên là investigation riêng về WAL/storage latency trên môi trường đại diện với DB/host I/O telemetry được phép, và lượng checkpoint/status transactions trên critical path. Chưa đề xuất bỏ row locks, gộp transaction qua provider, bỏ validation hoặc tăng concurrency. Context/parsing và stage barriers là các chi phí còn lại, chưa được phê duyệt thay đổi trong phase này.

## Reproduce

Runner local `target/run-assertlease.py` đọc credential từ cấu hình vào environment của process con, không in secrets. Dùng database test riêng `greytest_incremental_test_20261024`; không truncate hoặc sửa dữ liệu project gốc. Maven offline và cùng JVM configuration cho mọi nhánh.

```text
# Mỗi command dưới chạy qua: python target/run-assertlease.py <log-label> <arguments>
-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath
-De2e.profile=true -Dbenchmark.optimization=LEASE

# Diagnostic six
-De2e.samples=20 -Dbenchmark.workload=SIX_INDEPENDENT
-Dbenchmark.instrumentation=true -Dbenchmark.jfr=true -Dbenchmark.tail-observer=true
-De2e.outputDirectory=target/phase3-5-six-profile

# Diagnostic others
-De2e.samples=6 -Dbenchmark.workload=ONE_ADDED,ONE_ADDED_128_UNRELATED,RESUME
-Dbenchmark.instrumentation=true -Dbenchmark.jfr=true -Dbenchmark.tail-observer=true
-De2e.outputDirectory=target/phase3-5-other-profile

# Control (không bật benchmark.tail-observer)
-De2e.samples=20 -Dbenchmark.workload=ONE_ADDED,SIX_INDEPENDENT,ONE_ADDED_128_UNRELATED,RESUME
-Dbenchmark.instrumentation=false -Dbenchmark.jfr=false
-De2e.outputDirectory=target/phase3-5-control
```

`docs/benchmarks/summarize-tail-latency.py` reuse bộ tổng hợp paired hiện có, giữ mọi numeric sample, GC/pool/database observations và query spans liên quan; giữ full traces của hai cold window dài nhất mỗi workload/variant. Raw JSON/JFR đầy đủ ở các thư mục target trên; không commit binary JFR. Các span inclusive, sample counts và interval unions không thay thế root timer.

[Verification manifest](benchmarks/phase3-5-verification.json) ghi SHA-256 của raw series, compact evidence và logs; tổng suite/case và skip list có thể đối chiếu mà không cần dựa vào mô tả bằng lời. Các artifact JSON được ghi UTF-8/LF. Sampler không thu secrets. Hash source snapshot A/B kiểm tra 118 measured fixture pairs; warm-up source/canonical assertions cũng chạy nhưng không tính vào 118.

Phạm vi thay đổi chính xác: test profiler thêm PID/active SQL correlation; sampler test-only và native PostgreSQL self-check; JFR process-wide GC + MXBean counters; paired harness thêm multi-workload selector/source digest assertion; script summary/CI và evidence/report. `src/main/java` không có diff. Bản `application.yml`/`.env.example` đã dirty từ trước không thuộc Phase 3.5 và không được đưa vào commit. `main` giữ `f0b9f3d7d1204be6c2bb246785e7c710d19c1d06`.

Commit instrumentation/self-check: **`a6eefdb`**. Báo cáo và evidence được commit riêng sau independent review; xem commit chứa file này. Không push hoặc merge.
