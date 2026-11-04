# Phase 3.4 — LLM critical path: profiling và đề xuất

Ngày: 2026-11-04, Asia/Bangkok. Baseline: `d1aee0d`, nhánh `perf/source-update-performance`, sau khi gỡ thử nghiệm compatibility. Đã đọc báo cáo [Phase 3.3](source-update-phase3-3-performance.md), [Phase 3.2](source-update-phase3-2-results.md), [critical path](source-update-e2e-critical-path.md), [thiết kế scheduler](source-update-dag-scheduler-design.md), [checkpoint/resume](reports/2026-10-24-incremental-generation-optimization.md) và implementation hiện tại.

**Kết luận trong phạm vi đo:** backend/JDBC chi phối fixture mock. Chọn duy nhất bottleneck **round trips kiểm tra lease/fence**, nằm trong nhóm JDBC lớn nhất có thể quy về cùng một guard trên workload sáu method. Đề xuất gộp hai câu SQL của mỗi `assertLease` thành một statement vẫn khóa row trước khi kiểm tra thời gian DB. **Chưa triển khai runtime hoặc chạy SQL thay thế.** Chưa xác định bottleneck của provider thật và chưa chứng minh giảm thời gian chờ production.

## 1. Phạm vi, môi trường và phương pháp

- Giữ B1+B2 và Lombok directory reuse bật ở tất cả lượt đo. Không khôi phục source-only cache, đổi scheduler, batching, concurrency, prompt hoặc validator/fingerprint.
- Java 17.0.18, Maven 3.9.10, Spring Boot 3.3.4; PostgreSQL 15.17, database test riêng `greytest_incremental_test_20261024`. Xác minh 28 project trước đo; harness kiểm tra số project không đổi và cleanup riêng fixture của mình. Không truncate/restore hoặc chạy benchmark vào DB ứng dụng.
- DB test ban đầu chưa truy cập được, sau đó hoạt động lại. Guard từ chối tạo cluster tạm vì port đã có listener; xác minh đúng DB test trước khi chạy. Không tạo/khởi động/dừng database nào trong phase này.
- API key cục bộ có cấu hình nhưng **chưa được ủy quyền dùng API thật**. Cả hai series dùng `@MockBean LlmClient`, `LLM_PROVIDER=mock`, model metadata `gpt-6-luna`, concurrency 2, retry-attempts 0. Không gọi endpoint LLM bên ngoài.
- Một warmup/workload, sau đó 6 mẫu/workload/series. Workloads: thêm một method, sửa sáu method độc lập, dense artifact, một method với 128 Java files không liên quan, injected UT failure → resume → warm. Tổng **66 cửa sổ profile + 66 control**.
- Control tắt AOP/JDBC profiler, payload probe và JFR; production counters vẫn giữ. Profile/control là hai JVM chạy nối tiếp, cùng fixture/provider/config, **không phải baseline/optimized A/B**. Không có runtime optimization mới để đo after. Không dùng chênh lệch giữa hai series làm speedup.
- Root wall đo bằng monotonic clock: ngay trước Analyze đến GenerateAll trả về. Warm/resume chỉ GenerateAll. Loại Spring startup, fixture setup/cleanup, thu thập evidence và compile/JUnit khỏi cửa sổ. Không gồm HTTP/upload/extract/Apply/FE polling.
- p50=median; p95 nội suy tuyến tính. Sáu mẫu chỉ mô tả lượt đo, không đủ tail/CI production ổn định. Giữ toàn bộ outlier.
- Correlation run/update/stage/batch/target dùng profiler hiện có. Input/output tokens thật, remote queue và network latency là `null`, không phải zero. Ký tự không được đổi thành token thực tế.

## 2. Pipeline thực tế và tính năng đã tồn tại

`analyzeUpdate` thực hiện snapshot proof, parse/diff và dependency impact. `generateAll` giữ run/lease xuyên BR → TP → TC → UT; mỗi stage chờ các batch hoàn tất, verify source và commit stage marker trước stage tiếp theo. Batch executor đã có bounded concurrency, deterministic error ordering và retry budget.

Coordinator kiểm tra checkpoint theo từng target: effective input fingerprint, output dependency fingerprint, item IDs/digest/user revisions; output đã review được revalidate theo quy tắc hiện có. Agent response cache có project/stage/input fingerprint và output provenance; cache hit vẫn parse/validate lại. Fresh input/source/protected-edit/fence checks trước persist tiếp tục hoạt động. B1 bulk cases và B2 immutable overlay bundle chỉ reuse trong input epoch, nested precommit epoch đọc mới.

Không thêm checkpoint/cache thứ hai, không thu hẹp fingerprint hoặc bỏ các check vì tên timer bị lặp. Các optimization hiện có đã loại provider calls khi warm và chỉ gọi lại UT batch còn thiếu khi resume.

## 3. Wall time hiện tại

Đơn vị ms. **Control** là số đo root ưu tiên; cột profile giúp đối chiếu sensitivity của instrumentation, không là một bản optimized khác.

| Workload | Control p50 / p95 | Profile p50 | Calls/lượt |
| --- | ---: | ---: | ---: |
| Một method mới | 1085.65 / 1266.09 | 959.92 | 4 |
| Một method warm | 449.94 / 624.34 | 411.81 | 0 |
| Sáu method | 1862.19 / 2149.42 | 1569.94 | 7 |
| Sáu method warm | 756.04 / 890.63 | 670.95 | 0 |
| Dense | 1223.89 / 1543.58 | 2270.28 | 4 |
| Dense warm | 516.60 / 634.01 | 1154.64 | 0 |
| Một method + 128 unrelated files | 1243.30 / 1547.05 | 3007.98 | 4 |
| 129 files warm | 582.27 / 654.84 | 1465.12 | 0 |
| Sáu method, injected UT failure | 1565.90 / 1948.93 | 1792.74 | 7 |
| Resume phần thiếu | 730.35 / 836.05 | 867.62 | 1 |
| Warm sau resume | 570.61 / 746.62 | 690.08 | 0 |

Profile/control ratio dao động **0.843–2.516**. Instrumentation và host/JIT/GC/I/O variation chưa được cô lập bằng randomized crossover; không quy toàn bộ chênh lệch cho một nguyên nhân, không trừ một “overhead cố định”. Các số Phase 3.3 là build/protocol lịch sử khác và không phải cột before của phase này.

Stage p50 từ **control**, ms; timer có boundary riêng, không cộng các median để suy ra root:

| Workload | Analyze | BR | TP | TC | UT |
| --- | ---: | ---: | ---: | ---: | ---: |
| Một method | 118.41 | 175.94 | 204.46 | 166.72 | 289.18 |
| Sáu method | 272.99 | 278.89 | 347.57 | 261.64 | 482.95 |
| Dense | 102.70 | 134.27 | 275.83 | 208.71 | 340.20 |
| 129 files | 474.45 | 259.52 | 119.02 | 124.23 | 188.68 |
| Resume | Không chạy lại | 136.20 | 157.00 | 95.23 | 258.38 |

## 4. Backend và provider được phân biệt thế nào

Profile ghi interval `provider.local` quanh việc dựng response mock. Payload probe chạy bên ngoài span này nhưng bên trong lời gọi `complete` được agent đo bằng `llmNanos`; vì vậy **không dùng `llmNanos` của profile như provider latency thuần**. Probe không thay prompt/response, không lưu text prompt vào evidence và bị tắt trong control.

Hợp các interval trên đồng hồ root bằng interval union, kể cả khi workers chồng lấp. Không cộng thời gian provider/worker/JDBC/fingerprint/context/transaction lồng nhau thành tổng root.

| Profile, p50 ms | Một method | Sáu method | Dense | 129 files | Resume |
| --- | ---: | ---: | ---: | ---: | ---: |
| Mock provider interval union | 0.762 | 1.021 | 1.207 | 0.772 | 0.333 |
| JDBC execute + commit interval union | 443.98 | 854.92 | 926.72 | 1133.90 | 367.95 |
| Input fingerprint, inclusive | 91.03 | 306.70 | 353.34 | 377.07 | 261.79 |
| Output fingerprint, inclusive | 31.94 | 119.53 | 136.72 | 36.38 | 67.34 |
| Semantic validation, inclusive | 67.26 | 192.53 | — | 64.39 | 90.14 |
| Process CPU, toàn process | 1304.69 | 2335.94 | 1476.56 | 2218.75 | 781.25 |

Process CPU gồm workers, GC/background/JFR, có thể lớn hơn wall; không phải pure backend CPU. JDBC gồm driver/network/DB wait, chưa tách PostgreSQL server CPU, hydration hoặc row-lock wait. Lease SQL time cũng có thể chứa lock wait, không được coi toàn bộ là network round trip.

Trong từng mẫu control, `wall − tổng llmNanos` là **lower bound của wall không có provider call đang chạy**, vì tổng call có thể chồng lấp. Median lower bound: một method **1083.36 ms**, sáu method **1858.77 ms**, dense **1221.96 ms**, 129 files **1241.66 ms**, resume **729.89 ms**. Đây gồm backend, DB/queue/idle; không gọi là CPU processing time. Khoảng ngoài mock provider trong profile còn chứa overhead của probe.

Control tổng `llmNanos` p50: 2.321 / 3.513 / 1.733 / 1.687 / 0.495 ms tương ứng các workload trên. Không suy diễn các số mock sang model thật. Remote provider queue/network/TTFB/body-generation breakdown chưa đo; với mock chúng không có đối tượng đo.

Audit client thật: `LlmHttpTransport` dùng `sendAsync(...ofString())` rồi chờ toàn body; timer client gộp queue/network/provider/body parsing. `LlmUsageRecorder` và `LlmTokenUsage` đã ghi usage theo stage khi chủ động bật, có phân biệt `PROVIDER` với `ESTIMATED`; không cần tạo một bộ đếm token khác. Mock benchmark không đi qua usage metadata của provider nên tokens thực tế giữ `null`. Chưa có provider-side telemetry để tách queue khỏi network chỉ bằng một client timer.

## 5. Calls, payload và cache/checkpoint

Median ký tự tại biên mock, **không phải tokens**; sums theo stage, nên sáu method có hai prompt ở BR/TC/UT.

| Stage | Calls: 1 / 6 method | Prompt chars: 1 / 6 method | Response chars: 1 / 6 method | Context JSON chars: 1 / 6 method |
| --- | ---: | ---: | ---: | ---: |
| BR | 1 / 2 | 23368 / 48968 | 119 / 676 | 1773 / 5056 |
| TP | 1 / 1 | 12009 / 15290 | 161 / 923 | 1930 / 4881 |
| TC | 1 / 2 | 19611 / 42618 | 225 / 1330 | 2158 / 7448 |
| UT | 1 / 2 | 70497 / 145888 | 455 / 2684 | 2526 / 9358 |
| Tổng | 4 / 7 | 125485 / 252764 | 960 / 5613 | 8387 / 26743 |

UT template tĩnh 59770 ký tự; shared source contract 6233 ký tự, được append một lần/call. Bốn calls gửi lại 24932 ký tự contract; bảy calls gửi 43631. UT chiếm khoảng 56–58% tổng prompt chars. Phần context không phải toàn bộ payload: rút vài field không tự động rút phần lớn prompt.

Probe chỉ đếm textual fields có tên chứa `source`, dài ít nhất 32 ký tự, trùng **nguyên chuỗi** trong cùng JSON; median phần lặp này bằng 0 ở một/sáu method. Nó không phát hiện mọi lặp ngữ nghĩa hoặc lặp giữa các call. Cùng contract/source cần xuất hiện ở các request độc lập để giữ API/overload/generic/Lombok/exception evidence; không coi sự lặp xuyên request là dữ liệu có thể xóa an toàn. Không rút instructions hoặc unrelated files nếu chưa chứng minh chúng dư thừa bằng correctness/quality regression.

| Profile median | Một method cold / warm | Sáu method cold / warm | Resume retry / warm |
| --- | ---: | ---: | ---: |
| Response cache hit / miss | 0/4 ; 0/0 | 0/7 ; 0/0 | 0/1 ; 0/0 |
| Target checkpoint skips | 0 / 4 | 0 / 24 | 22 / 24 |
| Checkpoint hit rate | 0% / 100% | 0% / 100% | 91.67% / 100% |
| JDBC query / update / commit, cold hoặc retry | 254 / 34 / 69 | 506 / 107 / 162 | 282 / 23 / 111 |
| JDBC query / update / commit, warm | 171 / 17 / 40 | 262 / 17 / 104 | 262 / 17 / 104 |

Denominator checkpoint là unique `(stage,target)` từ batch inputs trong run, không chứa `__stage__` hoặc `__run__`. Dense warm skip 13 target, không lấy 16 artifact làm mẫu số. Warm không tra response cache nên response-cache hit rate là N/A, không phải 0% reuse. `cache.invalid` và `checkpoint.invalid` bằng 0 trong các measured samples.

Nguyên nhân miss được giới hạn đúng bằng chứng:

- Cold fixture có project/update mới và chưa có entry theo project/stage/input fingerprint. Miss này là compulsory miss, chưa có dấu hiệu key quá rộng.
- Warm checkpoint đã skip trước provider/cache boundary: không render/request LLM lại. Output digest và provenance digest giống chính xác trong cùng fixture.
- Injected UT response bị lỗi trước successful validation/cache write; resume gọi đúng một UT batch còn thiếu, giữ 22 target đã hoàn tất. Không gọi lại BR/TP/TC. Cold failure với hai workers không được dùng làm phép so speedup vì các batch khác vẫn có thể commit.
- Key hiện theo effective upstream input, source/dependency/resource/build, prompt/source-contract, validator và provider/config/locale. Metadata review thuần hiển thị đã được loại có chủ đích. Không có bằng chứng để nới key hoặc dùng source digest chung thay dependency closure.
- Counter hiện có không phân biệt hết `absent/expired/untrusted provenance/fingerprint changed`. Không gán missing provenance thành lỗi LLM hoặc khẳng định đã đo đầy đủ miss taxonomy từ counter tổng.

## 6. Batch barriers và method độc lập

Một method: mỗi stage chỉ có một batch, không tạo thêm work độc lập bằng cách tăng workers. Sáu method: BR `[3,3]`, TP `[6]`, TC `[2,4]`, UT `[2,4]` trong representative run; grouping giữ nguyên từ backend.

Profile median khoảng từ batch đầu hoàn tất đến batch cuối hoàn tất cùng stage: BR **52.81 ms**, TP **0**, TC **76.03 ms**, UT **108.58 ms**. Tổng ready-to-worker intervals chỉ **1.07 ms** cho sáu method; một method **0.58 ms**. Đây là local dispatch, không phải provider queue. Completion skew gồm work/commit thật, không phải toàn bộ thời gian có thể tiết kiệm.

TP batch dùng input của cả sáu method nên không thể cho TP đó chạy ngay sau BR batch đầu nếu giữ grouping hiện tại. Stage proof/marker cũng chưa được commit lúc batch đầu xong. Cho descendants chạy sớm cần thiết kế cohort/dependency/claims/fencing riêng; **không chọn thay scheduler** trong phase này và không cộng các skew để hứa một mức giảm root wall.

## 7. Bottleneck duy nhất được chọn và đề xuất nhỏ nhất

Trong từng profile sample, JDBC union/root ratio median là **46.17%** cho một method và **55.50%** cho sáu method. Chọn phần **hai SQL round trips của lease assertion** để đề xuất thử tiếp; không chọn prompt trimming, provider concurrency hoặc source context cache.

`GenerationCheckpointStore.assertLease` hiện:

1. `SELECT id ... FOR UPDATE` theo project/update/sentinel.
2. Sau khi đã lấy lock, `SELECT COUNT(*) ... owner/fence/state/lease_until > clock_timestamp()`.

Hai câu có lý do correctness: phải kiểm tra giờ DB **sau** lock wait. `save` gọi guard cho mọi `mark/complete`; coordinator còn assert trực tiếp tại mutation/commit boundaries. Không được xóa query thứ hai hoặc kiểm tra expiry trước khi đợi lock.

| Profile workload | Guard invocations | Lease SQL queries | Lease-query interval union p50 | Tổng JDBC queries |
| --- | ---: | ---: | ---: | ---: |
| Một method cold | 25 | 50 | 51.89 ms | 254 |
| Sáu method cold | 48 | 96 | 187.87 ms | 506 |
| Một method warm | 17 | 34 | 32.10 ms | 171 |
| Resume retry | 20 | 40 | 37.33 ms | 282 |

Lease-query group gồm `jdbc.executeQuery` dưới `assertLease`, `complete`, `mark`; source của ba đường này xác nhận hai query guard, còn checkpoint INSERT là `executeUpdate` và bị loại khỏi nhóm. Dùng interval union qua threads. Trên sáu method, nhóm này lớn hơn query của full update-item-list (~67.93 ms tổng inclusive theo purpose); không cộng các repository/JDBC spans lồng nhau.

**Phương án đề xuất sau review:** thử một statement có `WITH locked AS MATERIALIZED (...)`, khóa sentinel theo đúng project/update bằng `FOR UPDATE`, rồi outer query mới so owner/fence/state và `clock_timestamp()`. Giữ nguyên mọi lần gọi `assertLease`, transaction boundary, error khi không có đúng một lease hợp lệ và tất cả fresh protected-edit/source/checkpoint reads. Không cache fence hoặc lease, không giữ transaction qua provider call, không thêm schema/durable task claims.

Đây là thiết kế cần kiểm chứng, không phải SQL đã được chứng minh tương đương. Việc đặt volatile clock trong projection hoặc để predicate owner/expiry bị đẩy xuống trước lock có thể tạo stale lease acceptance. Phải kiểm tra query plan và hành vi PostgreSQL thực với hai connections trước khi viết implementation giữ lại.

**Lợi ích dự kiến có thể định lượng hiện tại chỉ là cấu trúc round trips:** nếu tương đương, một method giảm 254→229 query; sáu method 506→458; warm một method 171→154. Calls, token payload, artifact count, updates/commits và số guard invocations giữ nguyên. **After wall time chưa có**, không coi giảm query là đã giảm end-to-end. 51.89/187.87 ms là toàn bộ interval guard quan sát, gồm lock/server wait và overlap với workers khác; không phải số ms chắc chắn tiết kiệm và không mặc định giảm một nửa.

Không có bằng chứng hiện tại rằng thay đổi nhỏ này đạt mức giảm đáng kể trên provider thật. Nếu round trip không nằm trên critical worker, query mới tốn hơn hoặc wall A/B không cải thiện, loại optimization như nguyên tắc Phase 3.2.

## 8. Correctness gates và rollback cho bước triển khai sau review

Chưa thay đổi runtime nên chưa tuyên bố chứng minh correctness cho statement mới. Trước khi giữ nó cần:

- Block ở row lock cho đến khi lease hết hạn: cả hai đường phải từ chối sau khi được cấp lock, kể cả statement bắt đầu lúc lease còn hạn.
- Trong lúc đợi lock, connection khác thay owner/fence/state hoặc xóa sentinel: không worker cũ nào commit được.
- Thiếu transaction, missing sentinel, expired lease, sai project/update/owner/fence: giữ rejection hiện tại; rollback toàn bộ artifact/checkpoint khi guard fail.
- Hai worker, retry/resume, heartbeat dài hơn 20 giây, process khác claim sau expiry; không duplicate/lost output và không transaction xuyên provider.
- Source/POM/Lombok/config đổi giữa provider call, protected edits/user revision đổi trước commit: giữ validation/fingerprint/fresh reads hiện tại và phát hiện lỗi thật.
- Cùng impact/canonical BR/TP/TC/UT/provenance; compile/JUnit nguyên bản trước auto-repair. Giữ nguyên prompt/provider/config/batches trong paired randomized/interleaved A/B, có uninstrumented control, cold/warm/resume/one/multi-method. Không dùng mock để kết luận speedup provider thật.

Rollback **đề xuất, chưa tồn tại trong code**: flag khởi động `greytest.source-update.single-statement-lease-check-enabled`, default false trong thử nghiệm; false chạy nguyên hai SQL hiện tại. Không migration, đổi scheduler hoặc đổi artifact identity. B1/B2/Lombok flags hiện có giữ nguyên, không dùng việc tắt chúng làm baseline cho optimization này. Nếu chưa chứng minh được clock-after-lock semantics, giữ legacy và dừng phương án này.

## 9. Verification, evidence và điểm dừng

Hai entrypoint profiling/control đều exit 0 / BUILD SUCCESS, mỗi lượt 1 opt-in integration test; kiểm tra 132 measured windows và warm output/provenance stability. Có **18 generated UT nguyên bản compile + JUnit pass trong warmup mỗi series**, tổng **36/36**, không auto-repair hoặc sửa production project để pass. Không diễn giải thành compile rate của mọi measured window hoặc project thực tế. 162 measured mock provider calls trong profile không overlap recorded new-transaction hold trên chính worker thread đó; không chứng minh mọi execution path khác.

Focused regression: **88 tests pass, 0 failure/error/skip**, exit 0 / BUILD SUCCESS, 36.875 giây; gồm diff, fingerprint, checkpoint coordinator, snapshot và batch executor. Log: `target/phase3-4-focused.log`. Không chạy lại full regression suite trong phase chỉ thay test/profiling; không dùng số full suite lịch sử như kết quả của phase này. Lượt probe đầu fail do nhận header `Prompt:` thay vì template `# Prompt:`; lỗi ở test instrumentation đã sửa, log fail giữ ở `target/phase3-4-profile.log`, không tính là pass. Không sửa business logic để xử lý lỗi probe.

Thay đổi code chỉ ở test scope: thêm `LlmCriticalPathProfileTest`; mở package visibility cho helper compile/JUnit sẵn có của paired benchmark để reuse, body không đổi. Thêm script tổng hợp với self-check interval union/percentile/unavailable values. Production Java và prompts không có Git diff so với HEAD; `.env.example` và `application.yml` là thay đổi có sẵn của người dùng, được giữ nguyên. Reviewer chỉ đọc đã đối chiếu harness, script, báo cáo và raw intervals; không có finding actionable. Reviewer không tự chạy lại benchmark hoặc kiểm chứng SQL đề xuất.

Evidence:

- [Profile: từng sample, stage metrics, interval groups, JFR aggregates](benchmarks/source-update-phase3-4-profile.json).
- [Uninstrumented control: wall/counters, trường không đo giữ null](benchmarks/source-update-phase3-4-control.json).
- [Build/source digests và verification](benchmarks/source-update-phase3-4-verification.json).
- Raw spans/JFR ở `target/phase3-4-profile/`, raw control ở `target/phase3-4-control/`; không commit binary hoặc 52 MB raw spans.
- Harness: `src/test/java/com/greytest/service/LlmCriticalPathProfileTest.java`; summary: `docs/benchmarks/summarize-source-update-llm.py`.

Tái hiện trên DB test đã xác minh; đặt username/password cục bộ, không đưa secret vào command/evidence:

```powershell
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024'
$env:LLM_PROVIDER='mock'
$env:LLM_MODEL='gpt-6-luna'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
$env:MAVEN_OPTS='-Xms16m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
mvn -o -B -ntp '-Dtest=LlmCriticalPathProfileTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=6' '-De2e.outputDirectory=target/phase3-4-profile' '-Dbenchmark.instrumentation=true' '-Dgreytest.source-update.jdbc-read-optimization-enabled=true' '-Dgreytest.source-update.overlay-read-bundle-enabled=true' '-Dgreytest.source-update.lombok-directory-reuse-enabled=true' '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' test
# Chạy lại cùng command với instrumentation=false và outputDirectory=target/phase3-4-control.
python docs/benchmarks/summarize-source-update-llm.py --self-check
python docs/benchmarks/summarize-source-update-llm.py target/phase3-4-profile/samples.json docs/benchmarks/source-update-phase3-4-profile.json
python docs/benchmarks/summarize-source-update-llm.py target/phase3-4-control/samples.json docs/benchmarks/source-update-phase3-4-control.json
```

**Dừng ở profiling và đề xuất này để review.** Không có optimized runtime/after wall, không đổi main/push, không thay scheduler hoặc tự triển khai kiến trúc lớn. Provider thật, actual token/cost, remote queue/network và production quality/latency chưa được đo.
