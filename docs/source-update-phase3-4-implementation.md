# Phase 3.4 — assertLease: correctness và benchmark

Ngày: 2026-11-04, Asia/Bangkok. Nhánh `perf/source-update-performance`. Baseline code `08b1e74`; đọc trước [profiling Phase 3.4](source-update-phase3-4-performance.md). Chỉ tối ưu guard tại `GenerationCheckpointStore.assertLease`; không đổi schema, scheduler, provider, prompt, batch grouping, semantic validation, fingerprint hoặc các fresh precommit reads khác. B1+B2 và Lombok directory reuse luôn bật trong cả baseline/optimized.

## 1. Thứ tự kiểm chứng

1. Đọc toàn bộ callers/transaction boundaries và PostgreSQL semantics; viết SQL ứng viên trong test, chưa sửa production.
2. Chạy prototype đối chiếu legacy: **19 PostgreSQL tests pass**, không skip; plan đặt volatile clock ở CTE Scan ngoài LockRows. Log `target/assertlease-proof.log`.
3. Thêm test đòi runtime một statement: **RED**, expected 1 actual 2 trên code cũ. Đây là TDD failure về query count, không phải concurrency failure. Log `target/assertlease-red.log`.
4. Chỉ sau các gate trên mới thêm production branch sau feature flag. Chạy **28 tests pass**: 20 lease tests + 8 durable-store tests, cả hai nhánh legacy/optimized. Log `target/assertlease-runtime-gates.log`.
5. Chạy paired benchmark, microbenchmark/focused tests, full regression; independent review thực hiện sau verification. Kết quả cuối được ghi ở các mục sau.

Prototype execution path đã được bỏ khỏi test sau bước proof: regression hiện gọi **production `assertLease` ở cả hai flag values**, không chứng minh lại một bản SQL sao chép thay cho code đang dùng. SQL constant còn giữ cho EXPLAIN và kiểm tra query production đúng statement đã được proof.

## 2. SQL và transaction semantics

Legacy thực hiện trên cùng connection/transaction:

```sql
SELECT id FROM generation_checkpoint
WHERE project_id=? AND source_update_id=?
  AND stage='PIPELINE' AND target_key='__run__' FOR UPDATE;

SELECT COUNT(*) FROM generation_checkpoint
WHERE project_id=? AND source_update_id=?
  AND stage='PIPELINE' AND target_key='__run__'
  AND owner=? AND fence=? AND state='RUNNING'
  AND lease_until > clock_timestamp();
```

Ứng viên đã được test và đưa sau flag:

```sql
WITH locked AS MATERIALIZED (
    SELECT owner,fence,state,lease_until FROM generation_checkpoint
    WHERE project_id=? AND source_update_id=?
      AND stage='PIPELINE' AND target_key='__run__'
    FOR UPDATE
)
SELECT COUNT(*) FROM locked WHERE owner=? AND fence=?
  AND state='RUNNING' AND lease_until > clock_timestamp();
```

Guard vẫn từ chối trước SQL khi không có transaction thực. Không dùng NOWAIT/SKIP LOCKED, không đổi lock mode hoặc thứ tự lấy lock. Identity predicate ở trong CTE; ownership/fence/state/expiry chỉ ở ngoài. Row lock giữ đến commit/rollback của transaction hiện có. Không giữ transaction qua provider call và không cache kết quả lease.

### Vì sao clock nằm sau lock

`MATERIALIZED` chặn folding/predicate pushdown qua CTE; query ngoài nhận tuple từ `LockRows` trước khi áp dụng filter trên CTE Scan. Predicate expiry phụ thuộc `lease_until` của tuple đó, còn `clock_timestamp()` là volatile current DB time, không dùng transaction/statement start time. [PostgreSQL WITH](https://www.postgresql.org/docs/15/queries-with.html), [DB time functions](https://www.postgresql.org/docs/15/functions-datetime.html).

Plan PostgreSQL 15.17 thực tế: `Aggregate → CTE Scan(Filter owner/fence/state/clock)`; CTE `locked` có `LockRows → Seq Scan(identity only)`. Không dựa vào thứ tự viết các điều kiện WHERE. Test xác nhận worker đang bị chặn bằng `pg_blocking_pids`, giữ row lock đến khi DB clock vượt deadline rồi mới commit locker. Cả hai implementation từ chối; artifact được insert trước guard trong transaction worker cũng rollback. [Plan thực tế](benchmarks/assertlease-proof-plan.json).

### Vì sao state/ownership không bị stale

Sau khi lock được cấp, transaction khác không thể sửa sentinel cho đến lúc transaction guard kết thúc. Legacy đọc lại đúng sentinel đó; CTE dùng tuple đã được lock. Ở READ COMMITTED, khi concurrent updater commit, PostgreSQL trả phiên bản row đã cập nhật cho locking read; kiểm tra owner/fence/deadline ở ngoài phải dùng phiên bản này. Đã test replacement, renewal và deletion trong lúc worker chờ. [PostgreSQL isolation](https://www.postgresql.org/docs/15/transaction-iso.html).

Không đưa owner/expiry vào inner WHERE: nếu lọc trước LockRows, statement có thể nhận quyết định expiry từ trước lock wait hoặc bỏ việc khóa sentinel khi owner sai. Không thay `clock_timestamp()` bằng `CURRENT_TIMESTAMP`/`statement_timestamp()`.

### Phạm vi tương đương

- PostgreSQL 15.17 thực tế, default isolation **READ COMMITTED**. GreyTest không override isolation trong production code; test kiểm tra `SHOW default_transaction_isolation` để không âm thầm chạy trên cấu hình khác.
- Thêm regression REPEATABLE READ và SERIALIZABLE: concurrent row replacement gây SQLSTATE **40001** ở cả hai đường, transaction artifact rollback. Không coi đây là tuyên bố toàn bộ ứng dụng hỗ trợ các isolation cao hơn.
- Lease là token đã phát bởi `claim` cho sentinel tồn tại; owner UUID/fence là identity của run. Claim/release/heartbeat semantics không đổi. Không tạo API cho việc xóa rồi tái tạo sentinel với cùng owner/fence để hồi sinh token cũ.
- Expiry được đánh giá tại DB time thực tế sau lock của **từng invocation**, không yêu cầu hai lượt A/B có cùng timestamp tuyệt đối. Loại một round trip có thể làm invocation nhanh hơn; không được dựa vào clock trước lock để chấp nhận lease.
- Không bổ sung một đảm bảo mới rằng lease không thể hết hạn giữa lần guard cuối và physical commit. Mọi invocation/fresh boundary hiện có vẫn giữ nguyên; fence row lock và transaction behavior tương đương legacy.

## 3. PostgreSQL integration tests

Database test riêng `greytest_incremental_test_20261024`, PostgreSQL thật; không H2, không mock row locks/clock. Mỗi case tạo project/update riêng rồi cascade cleanup theo own project ID; không truncate dữ liệu cũ. Flyway chỉ validate 27 migration đã có, không thêm migration.

| Gate | Bằng chứng |
| --- | --- |
| Valid / expired lease | Cả hai flag values accept/reject tương ứng |
| Sai owner/fence/project/update, missing sentinel, không transaction | Cùng rejection; không SQL ngoài transaction |
| Worker khác claim sau expiry | Fence tăng, owner đổi; token cũ không commit artifact được |
| Hai worker cùng lease | Worker hai thực sự chờ lock; cả hai dùng lease còn hạn được tuần tự |
| Expiry trong khi chờ lock | Statement bắt đầu trước expiry, DB clock qua deadline khi đang blocked; từ chối sau unlock |
| Replacement trong khi chờ | Nhìn owner/fence mới, từ chối token cũ |
| Renewal trong khi chờ | Nhìn deadline mới sau concurrent update; chứng minh EPQ tuple freshness. Test trực tiếp update row, không thay đổi policy heartbeat vốn không gia hạn lease đã hết hạn |
| Deletion trong khi chờ | Không có sentinel hợp lệ để commit |
| Rollback | Artifact và successful checkpoint cùng rollback; transaction sau dùng lease lại được |
| Retry / stale commit | Sau reclaim, chỉ token mới hợp lệ; insert trước guard của worker cũ rollback |
| RR / SERIALIZABLE | 40001 khi concurrent updater commit; không còn artifact |
| Flag rollback + SQL fidelity | Optimized đúng một statement đã proof; false chạy nguyên hai SQL |

## 4. Benchmark protocol

Reuse `PairedSourceUpdateBenchmarkTest`, thêm selector `benchmark.optimization=LEASE`. Không xây scheduler/provider/harness mới. Baseline/optimized chỉ khác scoped flag của lease guard; source fixture/change, JVM heap 512 MB/SerialGC, PostgreSQL 15.17, mock provider/model metadata, concurrency 2 và retry config giữ nguyên.

Control: **20 cặp/workload**, cân bằng 10 AB/10 BA rồi shuffle seed hiện có 32026; 1 warmup pair trước mỗi workload. Profile: 6 cặp/workload để lấy JDBC/call-site/JFR counts. Cold, warm, one method, six independent methods, dense, 129 Java files, failure/resume và two batch workers. Setup/cleanup, compile/JUnit và evidence collection nằm ngoài cửa sổ root. Warm/resume chỉ GenerateAll; cold gồm Analyze → GenerateAll. Không cộng các span/median lồng nhau thành tổng root.

Paired ratio là median optimized/legacy **trong từng pair**; CI bootstrap 95% với 10000 resamples. p95 nội suy tuyến tính; 20 cặp vẫn hạn chế tail precision, không đo production traffic. Không loại outlier. Failure window được tách khỏi kết luận latency hoàn tất; phải đối chiếu regenerated-artifact counts của resume để không so khối lượng khác nhau.

Provider là mock xác định; API thật không được gọi, actual token usage/remote provider latency không có. Mọi lợi ích bên dưới chỉ thuộc fixture/backend đã đo.

## 5. Kết quả benchmark

### End-to-end control — 20 cặp mỗi hàng

Đơn vị ms. Ratio là median paired, không phải tỷ lệ hai median độc lập.

| Workload | Hai SQL p50 / p95 | Một SQL p50 / p95 | Paired ratio | CI ratio 95% |
| --- | ---: | ---: | ---: | --- |
| Một method cold | 817.62 / 1145.00 | 792.48 / 1050.86 | 1.0210 | 0.9637–1.0445 |
| Một method warm | 403.42 / 554.72 | 378.32 / 445.22 | 0.9193 | 0.8789–0.9730 |
| Sáu method cold | 1597.62 / 2346.35 | 1533.79 / 6755.78 | 0.9709 | 0.9173–1.0687 |
| Sáu method warm | 719.24 / 1044.25 | 624.06 / 1066.54 | 0.9571 | 0.8751–1.0118 |
| Dense cold | 1050.36 / 2419.79 | 1073.95 / 1829.06 | 0.9848 | 0.8689–1.0771 |
| Dense warm | 548.97 / 742.54 | 503.69 / 935.52 | 0.9421 | 0.8488–1.0446 |
| 129 files cold | 1155.19 / 2305.54 | 1111.29 / 1415.10 | 0.9804 | 0.8464–0.9992 |
| 129 files warm | 481.08 / 1270.59 | 478.85 / 584.22 | 0.9913 | 0.9298–1.0291 |
| Injected failure | 1548.14 / 2293.07 | 1407.87 / 2300.80 | 0.8920 | 0.8465–0.9717 |
| Resume retry | 739.67 / 1075.66 | 724.25 / 1312.54 | 1.0081 | 0.9600–1.1427 |
| Warm sau resume | 699.49 / 1331.19 | 644.15 / 1033.23 | 0.9643 | 0.8439–1.0489 |

Warm một method có tín hiệu giảm median paired **8.1%** trên fixture này. 129-file cold có ratio giảm khoảng 2% với CI sát 1. Cold một method/sáu method và resume retry **chưa chứng minh cải thiện**, cũng chưa chứng minh non-inferiority chặt 5% cho tất cả workload. Không gọi bảng này là speedup production hoặc cải thiện provider thật.

Sáu method optimized có hai mẫu lớn ở pair 14 (**7030.64 ms**) và pair 19 (**6741.32 ms**); giữ nguyên trong p95/CI. CPU process tương ứng 796.88/765.63 ms, mock provider tổng chỉ 3.04/2.76 ms. Pair 19 có TC stage 4355.09 ms; control không có SQL/row-lock trace để quy nguyên nhân cho guard, DB I/O, scheduling hay phần khác. Không mặc định outlier là “noise” và không bỏ chúng để tạo speedup. Tail này còn chưa giải thích là một lý do **không bật mặc định**.

Resume retry trong tất cả measured pairs của cả profile/control đều regenerate 2 artifact và skip 22 targets. Final output không duplicate và tương đương canonical. Failure window tách riêng, không lấy thời gian tới lỗi làm kết quả hoàn tất pipeline.

### JDBC — 6 cặp profile mỗi workload

| Workload | Guard invocations, cả hai nhánh | Lease queries hai→một SQL | Tổng JDBC queries hai→một SQL |
| --- | ---: | ---: | ---: |
| Một method cold | 25 | 50→25 | 254→229 |
| Sáu method cold | 48 | 96→48 | 506→458 |
| 129 files cold | 25 | 50→25 | 254→229 |
| Một method warm | 17 | 34→17 | 171→154 |
| Resume retry | 20 | 40→20 | 282→262 |

Đã kiểm tra **từng profile pair**, mọi workload: số guard invocations, JDBC updates và commits giữ nguyên; tổng query giảm đúng bằng số guard invocations. Không skip check, gộp transaction hoặc memoize lease. Calls vẫn 4 cho một method/129 files, 7 cho sáu method, 0 khi warm và 1 khi resume. Các con số là JDBC execute calls, không phải số TCP packets.

### Latency của assertion và database lock wait

Microbenchmark real PostgreSQL: 30 cặp uncontended, cân bằng/shuffle seed 3400, 4 warmup pairs; timer trong transaction đã mở, quanh lời gọi guard production và common scoped-flag binding. Không gồm begin/commit/connection creation. Đây là latency guard với overhead harness chung, không dùng để suy ra root wall.

| Guard operation, ms | Hai SQL p50 / p95 | Một SQL p50 / p95 |
| --- | ---: | ---: |
| Uncontended, 30 cặp | 5.610 / 7.023 | 4.159 / 5.044 |
| Có row-lock wait, 10 cặp | 46.864 / 96.230 | 40.761 / 60.220 |
| Observed blocked lower bound, cùng 10 cặp | 26.409 / 73.672 | 26.125 / 40.409 |

Uncontended paired ratio **0.7275**, bootstrap CI **0.7002–0.8070**: có bằng chứng operation nhanh hơn trên DB fixture. Không lấy ratio này làm tỷ lệ giảm Analyze → GenerateAll.

Contended case xác nhận `pg_blocking_pids` và `pg_stat_activity.wait_event_type='Lock'`, sau đó giữ lock thêm tối thiểu 25 ms. Lower bound đo từ lúc xác nhận blocked đến trước commit locker; chưa gồm thời gian trước polling hoặc commit/network latency. Sleep/scheduling thực tế khác nhau giữa mẫu, nên **không diễn giải chênh lệch contended latency là causal speedup**. Pipeline control không đo server-side row-lock duration riêng; không báo nó bằng zero.

Trong 132 profile windows, mọi JDBC span có `failed=false`: **0 unexpected DB errors/timeouts/deadlocks** được quan sát. Micro contended operations đều thành công. Các negative correctness tests cố ý tạo guard rejection/40001 là kết quả mong đợi, không cộng vào lỗi benchmark. Không tuyên bố hệ thống không thể deadlock ở mọi workload từ các số này.

## 6. Verification và quality

- Profile **132 windows**, control **440 windows**; tổng **572**. Hai entrypoint đều BUILD SUCCESS. Canonical impact/artifact/input+output provenance equality được kiểm tra **130 measured pairs**. Warm giữ output/provenance digests chính xác trong cùng fixture, không provider calls.
- **72 generated UT fixture tests compile + JUnit pass**, nguyên bản trước repair, ở warmup của hai series (36 mỗi series, gồm cả hai variants). Không tính chúng vào root timing hoặc suy diễn chất lượng project thật từ constant-return fixtures.
- Focused + real PostgreSQL/micro verification: **117 tests pass**, 0 fail/error/skip; `target/assertlease-verification.log`, 85.266 giây.
- Sau khi bổ sung assert lease còn hạn lúc worker đã bị block và xác nhận native Lock wait, chạy lại **21 integration/micro tests pass**, 0 fail/error/skip; `target/assertlease-final-gates.log`, 69.484 giây.
- Full regression **cuối** bật nhánh một SQL bằng JVM property: **150 suites, 1914 tests = 1873 pass + 41 skip, 0 failure/error**, exit 0 / BUILD SUCCESS; `target/assertlease-full-final.log`, 508.625 giây. Protected edits, source/config/dependency changes, retry/resume, temporary artifacts, Apply, export provenance và concurrency suites đã thực thi.

41 skip: ba profiler/paired classes có 12 method/template mỗi class; 2 oracle live/replay; 1 golden manifest; 1 scan benchmark; 1 lease microbenchmark opt-in đã chạy riêng. Không tính skip là pass. Tổng lấy theo suite names của invocation cuối và Surefire XML tương ứng, không cộng report cũ.

Lượt full trước cũng pass (1916 tests, 29 skip) nhưng phát hiện profiler subclass cũ thiếu opt-in gate riêng và đã chạy thêm. Đã sửa annotation test-only, xác minh riêng 12 skip khi không có `e2e.profile`, rồi chạy lại toàn full suite nêu trên. Khi enabled, các parameterized templates mở rộng thành 14 invocations; khi class disabled, JUnit đếm 12 templates skipped, nên tổng tests hai lượt khác nhau. Lượt cũ có thể ghi đè raw `target/phase3-4-profile` của phase profiling trước; committed JSON trước đó không đổi. Dataset `target/assertlease-*` dùng cho bảng A/B này nằm riêng và không bị ảnh hưởng. Không dùng dữ liệu của lượt full không chủ đích đó để tạo kết luận benchmark.

## 7. Feature flag và quyết định rollout

Flag **đã triển khai**, mặc định **false**:

```text
-Dgreytest.source-update.single-statement-lease-check-enabled=true
GREYTEST_SOURCE_UPDATE_SINGLE_STATEMENT_LEASE_CHECK_ENABLED=true
```

JVM property thắng process environment; scoped override dùng cho paired tests. Đây không phải Spring property tự đọc từ một `.env` chưa export. Rollback bằng false/unset và drain/restart theo quy trình hiện có; dùng nguyên hai SQL, không migration, cache purge, đổi fingerprint hoặc regenerate artifact chỉ vì rollback.

**GO về correctness cho nhánh opt-in đã kiểm chứng trên PostgreSQL 15.17; NO-GO bật mặc định/rollout rộng.** Có query reduction và tín hiệu warm e2e, nhưng primary cold one/six-method chưa đủ bằng chứng speedup; tail sáu method chưa giải thích. Giữ đường cũ là default để không tự áp thay đổi latency chưa đủ chắc chắn lên luồng bình thường. Cần review và đo lại trên workload/host phù hợp trước khi cân nhắc bật. Không tiếp tục tối ưu scheduler hay component khác trong task này.

## 8. Evidence và tái hiện

- [Profile compact](benchmarks/assertlease-profile.json): 6 cặp/workload, query purposes, semantic evidence và JFR aggregates.
- [Control compact](benchmarks/assertlease-control.json): 20 cặp/workload, từng sample và paired CI.
- [Microbenchmark](benchmarks/assertlease-micro.json): từng assertion và native Lock wait lower bound.
- [Summary/verification](benchmarks/assertlease-summary.json): query reduction per pair, guard/commit invariants, test results và digests.
- [EXPLAIN](benchmarks/assertlease-proof-plan.json): query plan PostgreSQL thật, fixture IDs/owner đã cleanup.

Raw spans/JFR ở `target/assertlease-profile/`; raw control ở `target/assertlease-control/`. Không commit binary/raw spans lớn. Logs giữ riêng cho prototype proof, TDD RED, runtime gates, verification, final gates và full-final. Không xóa log fail để báo pass.

Reproduce trên dedicated DB đã xác minh, với credentials test cục bộ được export; không đưa secret vào command/report:

```powershell
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024'
$env:LLM_PROVIDER='mock'
$env:LLM_MODEL='gpt-6-luna'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
$env:MAVEN_OPTS='-Xms16m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
mvn -o -B -ntp '-Dtest=LeaseAssertionPostgresIntegrationTest,DurableGenerationStoresIntegrationTest' '-Dlease.benchmark=true' '-Dgreytest.source-update.single-statement-lease-check-enabled=true' '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test
mvn -o -B -ntp '-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=20' '-Dbenchmark.optimization=LEASE' '-Dbenchmark.instrumentation=false' '-Dbenchmark.jfr=false' '-De2e.outputDirectory=target/assertlease-control' '-Dgreytest.source-update.jdbc-read-optimization-enabled=true' '-Dgreytest.source-update.overlay-read-bundle-enabled=true' '-Dgreytest.source-update.lombok-directory-reuse-enabled=true' '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test
# Profile: cùng command với samples=6, instrumentation=true, jfr=true, outputDirectory=target/assertlease-profile.
python docs/benchmarks/summarize-source-update-paired.py --self-check
python docs/benchmarks/summarize-source-update-paired.py target/assertlease-control/samples.json --compact-output docs/benchmarks/assertlease-control.json
mvn -o -B -ntp '-Dgreytest.source-update.single-statement-lease-check-enabled=true' '-Dspring.test.context.cache.maxSize=2' '-Dbenchmark.phase=assertlease-regression-final' '-DargLine=-Xms16m -Xmx768m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test
```

Không sửa production source của PetClinic hoặc source project người dùng, không thay main/push. Hai file config có sẵn của người dùng (`.env.example`, `application.yml`) được giữ nguyên. Dừng để review sau verification; không mở rộng tối ưu sang component khác.

## 9. Independent review và commits

Reviewer độc lập, chỉ đọc, đã trace SQL/callers/transaction boundaries, concurrency tests và benchmark claims; không có finding actionable. Reviewer tự đối chiếu 8 source/evidence hashes và 150 XML suites theo log full-final; khớp 1914 tests, 41 skip, 0 failure/error. Reviewer không chạy lại tests trong sandbox chỉ đọc; giữ khuyến nghị opt-in vì cold/tail evidence còn chưa chắc chắn.

- Baseline: `08b1e74`.
- Implementation + PostgreSQL correctness tests: `3942b5f`.
- Paired benchmark selector + opt-in profiler gate: `3274764`.
- Báo cáo/evidence được commit riêng sau hai commit code trên, trên cùng nhánh; không push/merge.

Xác minh sau full suite: database test vẫn 28 project, 27 migration thành công, PostgreSQL 15.17 / READ COMMITTED. Không tạo hoặc sửa schema. Kết luận giữ nguyên: **GO correctness opt-in; NO-GO bật mặc định**.
