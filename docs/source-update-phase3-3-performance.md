# Phase 3.3 — Fast source-update pipeline

Ngày đo: 2026-11-04, Asia/Bangkok. Nhánh `perf/source-update-performance`; không push/merge hoặc đổi main. Baseline là working tree hiện tại, gồm B1+B2 của Phase 3.2 và các thay đổi compatibility Phase 4.1 đã có trước lượt này; benchmark isolation không được bật. Không so sánh wall time trực tiếp với số lịch sử của Phase 3.1/3.2 vì runtime/config/protocol khác.

Phạm vi: một tối ưu cục bộ trong dependency loader, quyết định giữ/loại bằng paired end-to-end benchmark. Không DAG scheduler, không schema change, không đổi batch grouping/provider/prompt output contract, không khôi phục source-only context cache đã rollback ở Phase 3.2.

## 1. Audit những gì đã có

| Thành phần | Implementation hiện tại và quyết định |
| --- | --- |
| `SourceDiffService` | So sánh normalized AST body/signature/annotations cùng dependency evidence, không chỉ so tên method. Candidate parse được chuyển cho persistent analysis. Không thay impact set hoặc bỏ fingerprint khi DTO/config thay đổi. |
| `IncrementalGenerationService` | Chọn target từ impact, giữ thứ tự BR → TP → TC → UT. GenerateAll dùng chung run/lease và snapshot analysis. Không transaction bao provider call. Giữ nguyên stage barriers. |
| `IncrementalCheckpointCoordinator` | Atomic claim/fence, heartbeat, target checkpoint, input/output fingerprint, draft digest/revision, revalidation của reviewed/blocked output và fresh reads trước commit đã tồn tại. Warm/resume không phải lúc nào cũng cần gọi provider. Không thêm một hệ checkpoint khác. |
| `GenerationContextBuilder` | B1 bulk cases và B2 immutable overlay read bundle theo input epoch đã có. Nested precommit epoch đọc ownership/version/revision mới. Không memoize upstream thay đổi theo stage bằng methodId và không phục hồi cache A đã bị rollback. |
| `GenerationFingerprintService` | Fingerprint theo target dependency closure, upstream thực sự được dùng, build/runtime/resource evidence, template/source-contract/validator version. Review-only metadata được loại có chủ đích. Whole-source digest dùng cho identity của index không có nghĩa mọi artifact phải regenerate khi một file không liên quan đổi. |
| `GenerationJobContext` | Truyền actor/metrics/input values sang bounded workers; cache lifecycle đã có. Map directory mới không nằm trong job-wide map và không giữ AST/JPA entity. |
| `SourceSnapshotSession` | Watch source/config/classpath, chia sẻ index trong run, final hash trước kết thúc stage/run, fallback khi không chứng minh được snapshot. Không bỏ hoặc trì hoãn các check này để đạt số đo. |
| `LlmBatchExecutor` | Worker queue, concurrency theo cấu hình/tier, deterministic output ordering và retry budget đã có. Không tăng concurrency hoặc chạy child trước khi parent validate/commit. |
| `LlmResponseCache` / persistent cache | Có cache theo prompt/fingerprint và provenance validation, lưu sau điều kiện validation/transaction thích hợp. Không làm key rộng/hẹp tùy tiện. Checkpoint skip được báo riêng với response-cache hit. |
| `SourceUpdateModal` | Poll 2 giây để quan sát trạng thái; main generateAll request hoàn tất sẽ cập nhật state trực tiếp. Poll interval không phải stage wait của backend. Không sửa UI để làm progress trông nhanh hơn. |

Cold mock calls đã là 4 cho một method và 7 cho sáu method; warm có 0 call khi checkpoint còn hợp lệ. Không có bằng chứng phải đổi scheduler để loại call dư trong các fixture này. Các barrier wait có thể chứa thời gian worker xử lý thật; không gọi mọi `CompletableFuture.join` là lock contention hay idle lãng phí.

## 2. Profiling trước tối ưu

Dùng profiler hiện có, DB PostgreSQL thật **riêng cho benchmark** `greytest_incremental_test_20261024`, provider mock xác định, concurrency 2. Hai lượt diagnostic dùng 2 mẫu/workload sau warmup; chỉ dùng xác định chi phí, không coi là thống kê tail đáng tin.

| Diagnostic | One method, 1 Java file | One method, 129 Java files | 129 files warm |
| --- | ---: | ---: | ---: |
| Root wall median ms | 1619.94 | 2942.09 | 1009.41 |
| Analyze ms | 219.46 | 1465.95 | Không chạy lại |
| Dependency loader inclusive ms | 105.19 | 1449.74 | 395.13 |
| `hasConfiguration` inclusive ms | 21.13 | 481.87 | 137.75 |
| Config lookup calls | 3 | 387 | 129 |
| Ancestor file probes | 51 | 6579 | 2193 |

Một diagnostic trước khi thêm timer riêng cho config đo root 129-file khoảng 3077.59 ms, Analyze 1550.11 ms, dependency constructor chỉ 10.91 ms. Vì loader bao cả I/O/proof/classpath/cache wait, không coi constructor time là tổng dependency cost. JFR cũng có samples ở `LombokSourceSymbols.hasConfiguration`; timer riêng mới là evidence chọn phần xử lý này.

`SourceDependencyIndex.loadVerified` gọi `hasConfiguration(sourceParent)` cho **từng source file** để ghi manifest. Nhiều file cùng folder lặp lại cùng ancestry; `watchFile` tránh đăng ký watcher trùng nhưng `Files.exists` vẫn được gọi lại. Đây là chi phí backend đã đo trong đường Analyze → GenerateAll, không phải suy đoán LLM chậm.

Các timer inclusive có thể lồng nhau/chồng giữa workers; không cộng các hàng thành root wall hoặc tính phần còn lại bằng phép trừ tùy tiện. Warm chỉ đo GenerateAll, không cộng một Analyze giả bằng 0 vào so sánh cold.

## 3. Tối ưu duy nhất được thử

- `LombokSourceSymbols.hasConfiguration`: thêm count physical lookups/probes và duration, giữ nguyên logic watch/exists/return.
- `SourceDependencyIndex.loadVerified`: dùng `Map<Path, Boolean>` cục bộ để gộp các lookup cùng source parent directory trong **một verified load**. Chuỗi manifest, thứ tự source, source hashes và giá trị config boolean không đổi.
- `SourceSnapshotSession.hasMonitoredSnapshot`: observer dưới session lock; chỉ cho dùng kết quả directory khi root đã được capture và monitoring còn hoạt động. Kiểm tra lại điều kiện ở mỗi source, nên mất monitoring giữa load sẽ quay về đường cũ.
- Không giữ map qua batch/stage/session, không giữ source contract/AST/context mới. Không thay `checked()`/`toRealPath()`, source read/hash, dependency solver, watches của config chưa tồn tại, classpath final checks hoặc precommit reads.

Feature flag: JVM property `greytest.source-update.lombok-directory-reuse-enabled`, hoặc process environment `GREYTEST_SOURCE_UPDATE_LOMBOK_DIRECTORY_REUSE_ENABLED`. Scoped override chỉ phục vụ paired harness. **Giữ optimization, default true sau lượt confirmation 20 cặp** bên dưới. A/B luôn override false/true rõ ràng, nên đổi default không đổi nhánh thực thi đã đo. Lookup không được gộp nếu thiếu monitored snapshot, dù flag đang true.

TDD: test flag=true đã fail đúng **expected 1 lookup, actual 5** trước implementation. Focused suite sau sửa: **88 tests pass**. Test chứng minh ngoài monitored session vẫn lookup 5 lần và config mới ở ancestor vẫn làm session bị invalid trước stage success. Reviewer read-only không có finding actionable trong phạm vi loader/observer/harness.

## 4. Protocol A/B và kiểm chứng correctness

Tái sử dụng `PairedSourceUpdateBenchmarkTest`, thêm variant `CONFIG_DIRECTORY`; variant B cũ giữ nguyên. Ở variant mới, **B1+B2 bật ở cả hai nhánh**; chỉ directory-reuse flag thay đổi. Không đổi fixture, batching, concurrency, retry policy, provider/model metadata hoặc heap giữa hai nhánh.

- Balanced AB/BA, shuffle seed 32026 theo workload, một warmup pair/workload; không loại outlier.
- Workloads: ONE_ADDED, SIX_INDEPENDENT, DENSE_ONE, ONE_ADDED_128_UNRELATED và injected failure/resume; có warm sau các cold run và warm sau resume.
- Root: monotonic wall từ trước Analyze tới GenerateAll trả về. Warm/resume không Analyze lại. Loại Spring startup, fixture setup/cleanup, evidence queries và javac/JUnit verification khỏi cửa sổ đo.
- Control tắt BPP advice, JDBC proxy và JFR; intrinsic metrics giống nhau giữa hai nhánh. Profiled run dùng để phân rã/query counts, không lấy elapsed command làm pipeline latency.
- Harness so canonical impact/artifact/checkpoint provenance giữa hai nhánh, normalize technical IDs/labels nhưng giữ source logic và parameter order. Warm không gọi lại LLM và giữ output/provenance digest; resume hoàn tất phần còn thiếu rồi warm không gọi provider.
- Warmup quality gate compile nguyên proposal UT và chạy JUnit thật; không chỉ đếm output JSON. Production source của fixture được tạo trong vùng test, không sửa production project của người dùng.

Input/output tokens thực của provider mock không có usage payload; phải báo **unavailable/null**, không gán 0 token hoặc lấy prompt characters làm token. Harness có prompt/response character counters để kiểm tra payload; live provider không được gọi vì chưa có authorization riêng. Chi phí provider thật không phát sinh trong thí nghiệm này.

## 5. Kết quả paired và quyết định

### Control không BPP/JDBC proxy/JFR: 10 cặp mỗi workload

Đơn vị ms, p50=median, p95=linear interpolation; ratio là median **optimized/legacy trong từng pair**, CI bootstrap 95%, 10.000 resamples seed cố định. Không tính speedup bằng cách lấy tỷ lệ hai median cột rồi gọi đó là paired effect.

| Workload | Legacy p50 / p95 | Optimized p50 / p95 | Paired ratio | CI ratio 95% |
| --- | ---: | ---: | ---: | --- |
| Một method | 1056.95 / 1365.29 | 1081.38 / 1493.93 | 1.047 | 0.967–1.178 |
| Một method warm | 445.46 / 516.14 | 484.80 / 582.05 | 1.029 | 0.970–1.188 |
| Sáu method | 1999.47 / 2622.07 | 1858.85 / 3001.41 | 0.988 | 0.888–1.083 |
| Sáu method warm | 685.92 / 999.50 | 749.92 / 1132.70 | 1.055 | 0.999–1.184 |
| Dense | 1357.01 / 1482.35 | 1416.43 / 1538.18 | 1.025 | 0.980–1.100 |
| Dense warm | 544.96 / 745.03 | 591.21 / 702.51 | 0.998 | 0.949–1.141 |
| Một method, 129 files | 2795.31 / 3349.63 | 2379.38 / 3217.00 | 0.867 | 0.771–1.052 |
| 129 files warm | 1061.27 / 1397.06 | 845.11 / 2349.21 | 0.840 | 0.736–0.886 |
| Injected UT failure | 1797.00 / 2524.98 | 1698.20 / 2104.56 | 0.907 | 0.867–0.976 |
| Resume retry | 947.61 / 1117.31 | 916.90 / 1383.07 | 0.988 | 0.905–1.148 |
| Resume warm | 703.28 / 900.84 | 680.45 / 1022.11 | 0.975 | 0.943–1.133 |

Giữ mọi outlier. Ở lượt này warm 129-files có bằng chứng cải thiện median nhưng cold CI còn chứa 1. Không chọn riêng một median thuận lợi rồi kết luận cold tốt hơn. Các fixture một source file không có directory lookup trùng trong mỗi load, nên không kỳ vọng benefit đáng kể; dữ liệu cũng chưa chứng minh cải thiện hoặc non-inferiority chặt 5% cho mọi workload. Không tuyên bố mọi p95 giảm: warm 129-files có outlier làm p95 tăng.

### Confirmation độc lập: cố định 20 cặp, một method trong 129 files

Sau lượt 10 cặp, quyết định trước khi chạy thêm: chỉ workload có I/O lặp đã được timer xác nhận; một warmup pair + đúng 20 cặp AB/BA, không dừng sớm theo kết quả. Giữ nguyên provider/config/heap/fixture, lưu riêng, công bố cả lượt 10 cặp ban đầu. Không gộp hai lượt hoặc thay thế dữ liệu bất lợi.

| Workload | Legacy p50 / p95 | Optimized p50 / p95 | Paired ratio | CI ratio 95% |
| --- | ---: | ---: | ---: | --- |
| Analyze → BR → TP → TC → UT, 129 files | 3453.57 / 4994.58 | 2509.28 / 3553.23 | **0.794** | **0.751–0.851** |
| GenerateAll warm, 129 files | 1171.21 / 2562.34 | 1023.81 / 2786.73 | **0.907** | **0.786–0.989** |

Đây là evidence giữ optimization: median paired cold wall giảm khoảng **20,6%**, CI tương ứng khoảng **14,9–24,9%** trên fixture này; warm khoảng **9,3%** với CI còn gần 1. Không suy diễn p95 hay production speedup từ các tỷ lệ này. Layout 129-file fixture có source cùng parent directory, thuận lợi cho loại bỏ lookup trùng; project có nhiều directory độc lập có thể được lợi ít hơn.

Stage p50/p95 của confirmation (ms, không cộng thành tổng):

| Timer | Legacy p50 / p95 | Optimized p50 / p95 |
| --- | ---: | ---: |
| Analyze | 1553.86 / 2222.84 | 1056.66 / 1423.94 |
| BR stage | 719.23 / 1497.50 | 561.54 / 1031.08 |
| TP stage | 217.74 / 657.41 | 205.33 / 304.47 |
| TC stage | 208.81 / 612.15 | 208.00 / 419.98 |
| UT stage | 341.36 / 675.72 | 368.76 / 518.28 |

Stage timers không bao hết authorize/status/DTO/final proof và có thread activity khác nhau; median không có tính cộng. UT median tăng trong khi root giảm, không gọi đó là đã tăng tốc từng stage.

### Calls, payload, cache/checkpoint

Median counts trong control 10 cặp, **hai nhánh giống nhau**:

| Workload | Mock calls | Prompt chars | Response chars | Response-cache hit / miss | Checkpoint skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| Một method cold | 4 | 125773 | 958 | 0 / 4 | 0 |
| Sáu method cold | 7 | 253320 | 5601 | 0 / 7 | 0 |
| Dense cold | 4 | 131240 | 3719 | 0 / 4 | 0 |
| 129 files cold | 4 | 125797 | 958 | 0 / 4 | 0 |
| Một method warm | 0 | 0 | 0 | 0 / 0 | 4 |
| Resume retry | 1 | 71981 | 900 | 0 / 1 | 22 |

Cold response-cache hit rate 0%; warm không có response-cache lookup nên rate không áp dụng, không phải cache hit 0% nghĩa là warm thất bại. Warm một method chứng minh reuse 4/4 target checkpoints, không gọi LLM; các warm fixture khác cũng assert 0 calls và digest không đổi. Input/output tokens **null/unavailable** vì mock không phát usage; chars chỉ là payload proxy, không tính bill/token savings. Không cắt context để tạo số đo tốt hơn.

Resume dùng error non-retryable, executor retry-attempts=0; đây là đo resume một run lỗi, không đại diện backoff của provider thật. Với hai worker, first failing UT batch có thể khác subset target; không diễn giải riêng ratio failure/resume là causal speedup do directory map. Kết quả cuối và canonical provenance sau resume vẫn phải bằng nhau.

### Correctness và quality đã có evidence

Control 10 cặp: **220 measured windows**, canonical impact/artifact/provenance A/B bằng nhau ở mọi pair. Confirmation 20 cặp: **80 windows** cho cold/warm, cùng equality checks. Không đổi số calls, batch targets, checkpoint behavior hoặc nội dung prompt/response ngoài technical IDs vốn được normalize.

Quality gate ở warmup của mỗi workload và mỗi variant: **36 proposal UT** trong control chính và **2 proposal UT** trong confirmation được javac compile rồi thực thi JUnit thành công. Đây là **100% số proposal đã thử compile/runtime trong quality subset**, không phải compile/pass rate của tất cả project người dùng hoặc của mọi measured window. Output các pair còn lại được so canonical; không auto-repair proposal để đạt gate.

### Profile bổ sung: 4 cặp/workload, BPP/JDBC proxy/JFR bật

Không gộp wall của series profile vào control. **88 measured windows**, các canonical checks pass; quality subset thêm 36 proposal compile/JUnit pass ở warmup. Median JDBC query/commit **không đổi** giữa hai nhánh:

| Workload | Query legacy → optimized | Commit legacy → optimized |
| --- | ---: | ---: |
| Một method cold | 254 → 254 | 69 → 69 |
| Sáu method cold | 506 → 506 | 162 → 162 |
| Dense cold | 401 → 401 | 102 → 102 |
| 129 files cold | 254 → 254 | 69 → 69 |
| Một method warm | 171 → 171 | 40 → 40 |
| Resume retry | 282 → 282 | 111 → 111 |

Đây là giữ nguyên B1+B2 và các transaction boundaries, không phải giảm query bằng cách bỏ fresh read. Riêng 129-file cold: config calls **387 → 3**, ancestor probes **6579 → 51**, config inclusive time **518.56 → 13.04 ms**. Warm: calls **129 → 1**, probes **2193 → 17**. Trong cold, dependency walks vẫn **3 → 3**, source reads **387 → 387**, input fingerprint calls **16 → 16**, output fingerprint calls **15 → 15**. Không giảm source proof hoặc validation để tạo speedup.

Mọi warm fixture ở cả ba series có **100% target checkpoint reuse**, 0 provider calls. Denominator lấy target checkpoint của scope đã settle, **loại bốn `__stage__` marker**; failure window dùng target set cuối cùng nên không gọi đó là rate của riêng số target đã tới trước lỗi. Response cache rate và target checkpoint reuse được giữ riêng.

Full regression với **default true**, DB restored đúng URL, kết thúc **exit 0 / BUILD SUCCESS**: **151 suites, 1903 tests = 1875 pass + 28 skip, 0 failure/error**. Các suite DB protected edits, temporary artifact preservation, export provenance, durable stores, retry/resume và concurrency đã thực thi; không còn 34 DB URL-gated skips của lượt Phase 4.1 dùng cluster khác.

28 skip gồm hai live/replay oracle chưa bật, một golden manifest dành cho checkout khác, một scan microbenchmark opt-in và 12 test kế thừa ở mỗi class profile/paired khi `e2e.profile` không bật. Các profile/paired entrypoint cần cho phép đo này đã chạy riêng và pass; không coi skip là pass. Dùng đúng log invocation mới và Surefire XML tương ứng để cộng số liệu. Lượt tagged mất 422.453 giây cả Maven/test setup; đây không phải pipeline wall time.

Reviewer read-only đã review loader/observer, fallback/watches, TDD regression và các thay đổi harness/selection; không còn finding actionable. Sau confirmation chỉ đổi default flag sang true; không thêm tối ưu thứ hai. FE không đổi nên không chạy lại FE suite; không có live provider, production traffic hoặc performance claim ngoài fixture đã đo.

## 6. Rollback và giới hạn

Rollback directory optimization bằng `-Dgreytest.source-update.lombok-directory-reuse-enabled=false` khi khởi động JVM, hoặc process env tương ứng bằng `false`; không cần migration. Đây là JVM/process setting, không phải property Spring tự đọc từ một file `.env` chưa được export. Không tắt B1+B2, source proof hoặc validation để rollback phần này.

Evidence raw tạm ở `target/phase3-3-*`; build manifest SHA-256 các file `src/main` ở `target/phase3-3-measured-build-manifest.json`. Source/credentials không được đưa vào báo cáo. Hai file `.env.example` và `src/main/resources/application.yml` đã dirty từ trước được giữ nguyên. Kết quả control/mock chỉ chứng minh local backend trên fixture, chưa chứng minh production/user traffic hoặc latency provider thật.

Evidence compact, giữ từng mẫu và canonical representatives:

- [Control 10 pairs/workload](benchmarks/source-update-phase3-3-control.json).
- [Confirmation 20 pairs riêng 129 files](benchmarks/source-update-phase3-3-confirmation.json).
- [Profile 4 pairs/workload](benchmarks/source-update-phase3-3-profile.json).
- [Summary mọi stage p50/p95, count/rate và token-usage null](benchmarks/source-update-phase3-3-summary.json).

Summary có SHA-256 của measured build manifest. Sau control/confirmation, production diff duy nhất là literal default flag `false` → `true`; nhánh paired override explicit không thay đổi. Profile và regression dùng default mới. Không commit raw JFR hoặc hàng chục MB spans.

Các chi phí còn lại cần giữ phân biệt: path safety/full-source proof bắt buộc, dependency/resource resolution, fresh context/DB reads trước commit, semantic validation và provider latency thật chưa đo. Không xóa các check vì thấy tên timer lặp. Sau quyết định về tối ưu đầu tiên, dừng để review; không tự triển khai scheduler hoặc tiếp tục tối ưu speculative.

## 7. Môi trường và tái hiện

Windows 11 amd64, Temurin JDK 17.0.18+8, Maven 3.9.10, backend Spring Boot 3.3.4. Provider trong Spring test là `LlmClient` mock xác định; `LLM_PROVIDER=mock`, model metadata `gpt-6-luna`. B1+B2 bật, batch concurrency 2 trong harness, retry-attempts=0 cho injected failure. Không cần API key thật.

DB benchmark **khác database ứng dụng**, URL phải đúng điều kiện có sẵn của integration suite. DB test đã tồn tại với 28 project và 27 migration thành công trước lượt này. Harness đếm project trước/sau và chỉ cleanup fixture do mình tạo; không truncate hoặc restore đè DB. Credentials lấy cục bộ, không ghi vào evidence.

```powershell
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024'
# Đặt SPRING_DATASOURCE_USERNAME/PASSWORD bằng credentials DB test cục bộ.
$env:LLM_PROVIDER='mock'
$env:LLM_MODEL='gpt-6-luna'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
$env:MAVEN_OPTS='-Xms16m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'

mvn -o -B -ntp '-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=10' '-Dbenchmark.optimization=CONFIG_DIRECTORY' '-Dbenchmark.instrumentation=false' '-Dbenchmark.jfr=false' '-Dgreytest.source-update.jdbc-read-optimization-enabled=true' '-Dgreytest.source-update.overlay-read-bundle-enabled=true' '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test

mvn -o -B -ntp '-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=20' '-Dbenchmark.optimization=CONFIG_DIRECTORY' '-Dbenchmark.instrumentation=false' '-Dbenchmark.jfr=false' '-Dbenchmark.workload=ONE_ADDED_128_UNRELATED' '-De2e.outputDirectory=target/phase3-3-confirmation-control' '-Dgreytest.source-update.jdbc-read-optimization-enabled=true' '-Dgreytest.source-update.overlay-read-bundle-enabled=true' '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test

mvn -o -B -ntp '-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=4' '-Dbenchmark.optimization=CONFIG_DIRECTORY' '-Dbenchmark.instrumentation=true' '-Dbenchmark.jfr=true' '-Dgreytest.source-update.jdbc-read-optimization-enabled=true' '-Dgreytest.source-update.overlay-read-bundle-enabled=true' '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test

python docs/benchmarks/summarize-source-update-paired.py --self-check
python docs/benchmarks/summarize-source-update-paired.py target/phase3-3-config-directory-control/samples.json --compact-output docs/benchmarks/source-update-phase3-3-control.json

mvn -o -B -ntp '-Dspring.test.context.cache.maxSize=2' '-Dbenchmark.phase=phase3-3-regression' '-Dpetclinic.sourceOnlyPath=../benchmarks/real-projects/petclinic-rest/source-only-input' '-DargLine=-Xms16m -Xmx768m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test
```

Lượt full suite đầu dùng phase mặc định `before` đã bị guard `benchmarkColdAndUnchangedRepeat` từ chối do baseline có sẵn (**1 error**, không failure assertion của pipeline). Hai phương án an toàn là dùng tag `benchmark.phase` riêng hoặc chạy trong output/worktree riêng; đã chọn tag có sẵn, không sửa guard hoặc xóa baseline. Log cũ giữ ở `target/phase3-3-regression-final.log`, lượt chạy lại ở `target/phase3-3-regression-tagged.log`; không báo lượt lỗi là pass.
