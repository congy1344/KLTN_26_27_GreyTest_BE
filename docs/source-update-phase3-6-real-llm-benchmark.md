# Phase 3.6 — Real LLM benchmark: preflight và kế hoạch xin phê duyệt

> For agentic workers: dùng skill `executing-plans` để thực hiện từng gate sau approval; dừng tại các review/budget boundary được ghi dưới đây.

**Goal:** Đo real-provider E2E và quality trên fixed source, với request/cost budget được duyệt.
**Architecture:** Harness và telemetry trong test scope; giữ nguyên production pipeline, validators, lease/fencing và cache provenance.
**Tech stack:** Java 17, Spring Boot 3.3.4, PostgreSQL, Maven/JUnit, OpenAI-compatible client hiện có.
**Spec:** Yêu cầu GREYTEST Phase 3.6 của user và các quality/budget gates trong báo cáo này.

Ngày 2026-11-04, Asia/Bangkok. Baseline `257b887`, nhánh `perf/source-update-performance`. **Trạng thái: PREFLIGHT_AWAITING_APPROVAL. Chưa gọi LLM thật; chưa có latency, token usage, cost hoặc generated-UT quality measurements Phase 3.6.** [Results JSON](source-update-phase3-6-results.json) dùng `null` cho các số chưa đo, `samples=[]`; không chép số mock vào kết quả thực.

Đã đọc [Phase 3.3](source-update-phase3-3-performance.md), [3.4 implementation](source-update-phase3-4-implementation.md), [3.5 tail](source-update-phase3-5-tail-latency.md), [critical path](source-update-e2e-critical-path.md) và [3.4 profiling](source-update-phase3-4-performance.md). Phase 3.5 thấy WAL/transaction-lock waits trong diagnostic và chưa chứng minh E2E improvement ổn định; không tiếp tục tối ưu backend từ mock. `assertLease` giữ OFF và được pin false trong benchmark, không có A/B bật lại flag trong phase này.

## 1. Preflight cấu hình, không xác thực qua API

| Hạng mục | Kết quả đọc local/source |
| --- | --- |
| Config loading | `spring.config.import=optional:file:.env[.properties]`; phải chạy từ backend directory |
| Provider / model | `.env`: `openai` / `gpt-6-luna`; không thấy process overrides liên quan trong shell hiện tại |
| Endpoint hiệu lực dự kiến | `https://api.openai.com/v1/chat/completions` từ `.env`; YAML fallback khác endpoint này, nên không chạy thiếu import rồi dùng nhầm gateway |
| API key | Có giá trị local; chỉ ghi boolean presence, không ghi key, prefix, suffix, hash hoặc Authorization header |
| Request shape | Chat Completions, JSON object mode, temperature 0.3, `reasoning_effort=none`, `max_completion_tokens` cho model này |
| Output limits BR / TP / TC / UT | 4096 / 6144 / 8192 / 12288; fallback 16384; giữ nguyên để không giảm chất lượng |
| Timeout | 60 s toàn HTTP response; connect timeout cùng cấu hình |
| Batch | Local concurrency 4; retry-attempts fallback 2, retry delay 200 ms/cap 2000 ms |
| Agent retries | 2 attempts thông thường; tối đa 3 cho malformed JSON/shared-pool exhaustion; delay có thể tới 60 s; có batch-level call budgets và splitting |
| Usage | Default disabled; recorder có `PROVIDER` và `ESTIMATED`; không lấy estimated calls làm số đo token thật |
| Context logs | Local file logging đang bật, console tắt; benchmark sẽ override cả hai tắt, không sửa `.env`/YAML |
| Account RPM/TPM/balance/access | **Chưa biết**; không gọi `/models`, usage/billing hoặc completion để dò trước approval |

Đây là configured values, không phải bằng chứng một server đang chạy đã nạp đúng config hoặc tài khoản có quyền model. Trước request đầu tiên phải assert allowlisted effective Spring Environment và bean thật `OpenAiLlmClient`, endpoint/model/flag; không export toàn Environment. Nếu khác kế hoạch thì dừng. Không tự đổi gateway hoặc model.

[OpenAI model documentation](https://developers.openai.com/api/docs/models/gpt-6-luna) đã mở công khai ngày 2026-11-04: model hỗ trợ Chat Completions; context 1,050,000, max output 128,000. Standard text pricing/1M tokens: input $0.10, cached input $0.01, cache write $0.125, output $0.50. Prompt >272K input tokens áp dụng 2× input/cache và 1.5× output; Fast 2×; regional premium 10%. Đây là **reference tariff**, không phải account access, quota hoặc actual invoice. Không gửi credentials khi đọc public docs.

## 2. Đề xuất phê duyệt pilot, chưa phê duyệt campaign đầy đủ

**Pilot đề xuất: tối đa 12 HTTP attempts tổng cộng, ngân sách dự phòng 5 USD.** Dùng đúng provider/model/endpoint trên; một lượt cold one-method, replay checkpoint không đổi, rồi một lượt existing-method update nếu đủ ngân sách. Mọi warm-up, model/auth probe nếu có, retry, semantic repair, batch split, timeout có kết quả billing không rõ đều tính vào 12. Không gọi probe riêng nếu completion đầu tiên có thể đồng thời xác minh access. Mỗi workload một mẫu exploratory; không công bố p95 hoặc CI với số mẫu này.

Pilot config pin concurrency **2**, batch retry-attempts **2** và token/timeout/semantic checks như trên, B1+B2/Lombok bật, assertLease false. Đây là benchmark configuration công khai, không đổi runtime source hoặc cấu hình ứng dụng đang chạy. Agent retries vẫn giữ nguyên; không giả định retry-attempts=0 sẽ tắt mọi retry. Không tự giảm token limit/prompt để giữ budget.

Test-only request guard cần có trước network:

- Atomic global attempt reservation trước mỗi HTTP dispatch; không đếm mỗi stage/batch như một request. Không vượt 12 kể cả worker song song hoặc retry. Request có kết quả không rõ vẫn tiêu một reservation; không hoàn lại tiền giả định cho timeout/4xx/5xx.
- Payload text-only, không tools/images, body UTF-8 tối đa **512 KiB**, output request tối đa **16384**. Nếu vượt: **từ chối gửi**, lưu budget-stop, không truncate source contracts hoặc dependency evidence.
- Trần USD 5 là **ngân sách phê duyệt cho benchmark**, không phải provider-enforced account limit và không bao gồm usage khác của tài khoản. Lập reservation allowance bảo thủ từ payload/tokenization bound đã kiểm chứng offline và maximum output, xét cache-write/long-context/tier/regional rates; tổng allowance của request completed + unknown + in-flight không được vượt USD 5. Nếu chưa chứng minh được bound hoặc phát hiện model/tariff/tier ngoài phạm vi, không dispatch. Với 12 request/payload cap trên, dự trù theo mức cao trong tariff tham chiếu khoảng dưới USD 4; đây là planning allowance cần self-test, **không phải token/cost thực đo hay cam kết hóa đơn**.
- HTTP attempts và reservations là hard gate trong test harness; USD reconciliation chỉ là billed cost khi provider trả billing. `usage × tariff` phải có nhãn calculated tariff cost riêng. Nếu usage thiếu hoặc source ESTIMATED: token thực và actual cost để null; dừng mở rộng campaign thay vì coi là 0.
- Dừng khi hết budget, lỗi auth/model/access, 429 liên tiếp, usage thiếu, output không qua validation sau retry sẵn có hoặc source proof thay đổi. Không tự nạp credit, đổi provider, xin model rẻ hơn hoặc chạy thêm mẫu.

**Chưa có global guard/harness real-provider này**. Approval không bỏ các offline gates: chỉ sau khi test guard/isolation/telemetry bằng local HTTP server và independent review đạt mới được gửi request thật. Không có runtime code changes được đề xuất. Nếu budget chỉ đủ một phần pipeline, ghi interrupted/incomplete thay vì completion pass.

## 3. Source snapshots cố định và workload design

Results JSON đóng băng file contents, SHA-256 từng file và manifest digest cho bốn snapshot `phase36-*-v1`. Materialized preflight ở `target/phase3-6-preflight/snapshots/`; build outputs không thuộc manifest input. POM dùng Spring Boot parent 3.3.4, Java 17, Spring Context và starter-test. Không có `src/test`, developer test, approved test seed hay RAG corpus. Đây là **synthetic Java Spring fixture với provider thật**, không phải PetClinic/production benchmark.

Offline build preflight: cả bốn snapshot `mvn -o ... -DskipTests compile` exit 0; file/manifest hashes khớp. `dependency:tree` exit 0 xác nhận Spring Context 6.1.13, Jupiter 5.10.3, JUnit Platform 1.10.3, Mockito 5.11.0; Java thực tế Temurin 17.0.18. Chưa chạy generated tests/JUnit hoặc data-leak sentinel. Maven compilation không khởi động GreyTest/provider. Chưa chạy full regression lại vì thay đổi được version-control trong bước này chỉ là docs/JSON.

| Workload | Input và trạng thái DB cần có | Phạm vi đo |
| --- | --- | --- |
| Cold one method | Base-five có value0..4 trả 41; added-one thêm value5 trả 42, chưa có artifact cho method mới | Analyze → BR → TP → TC → UT |
| Existing one method | Apply bình thường output **do GreyTest/provider thật sinh từ cold run**; updated-one đổi value5 thành 43 | Analyze và regeneration có artifact sẵn |
| Six methods | Tạo baseline artifact cho cả 6 method bằng provider thật, có tính phí setup; updated-six đổi cả 6 thành 44 | Analyze → GenerateAll; setup/seeding tách thời gian và chi phí nhưng vẫn tính ngân sách |
| Unchanged resume | Replay cold-success update cùng source/upstream/checkpoint, cùng project/update IDs | GenerateAll, kỳ vọng 0 remote calls nếu checkpoint còn hợp lệ |
| Failed-batch resume | Chèn failure **test-only trước network** tại batch định danh, chờ toàn bộ worker settle rồi retry cùng source | Failure và retry windows riêng; đây là failure injection với real outputs ở calls khác, không phải observed provider outage |

Không tái dùng `IncrementalPipelineBenchmarkTest.createFixture()` nguyên trạng: factory cũ seed `testSource()` viết tay vào `unit_test` và approved upstream artifacts. Dùng nó sẽ vi phạm isolation/không phản ánh existing real artifacts. Có thể reuse source/digest/profiler/helpers phù hợp, nhưng DB bootstrap real benchmark phải không chứa handwritten test seed.

Snapshot manifest không thay cho runtime `SourceSnapshotSession` proof/classpath fingerprint. Không sửa source snapshot giữa provider call; materialize revision riêng từ frozen definitions, verify hash trước/sau. Apply chạy theo flow bảo vệ hiện có, không direct-write database để ép trạng thái. Project DB riêng, cleanup theo owned project ID, không truncate.

Chỉ pilot 3 workload đầu đã nêu ở mục 2 nằm trong request xin phép lần này; six/failure-resume cần kế hoạch budget mới sau khi thấy artifact fan-out, token và quality thực. Không dùng 7 calls mock của sáu method như bảo đảm số calls thật. Campaign tiếp theo dự kiến ít nhất 3 independent fixtures/workload để khảo sát median; muốn báo p95 ổn định cần số mẫu và budget riêng, không tự chạy 20 lượt trả phí.

## 4. Kế hoạch thực thi sau approval

Phạm vi file dự kiến: thêm `src/test/java/com/greytest/service/RealLlmSourceUpdateBenchmarkTest.java` và tests guard/isolation trong test scope; reuse `CriticalPathProfiler`/`TailLatencyObserver` khi phù hợp. Không kế thừa `@MockBean LlmClient` hoặc beforeEach bắt buộc mock trong harness cũ. Kết quả cập nhật đúng hai deliverables Phase 3.6 này; raw outputs ở `target/phase3-6-real/` riêng.

- [x] Đọc baseline/config/client/context/usage/retry paths; không gọi provider.
- [x] Freeze source-only manifests; compile cả bốn production snapshot offline với Maven thành công. Manifest/file hash self-check pass, không có developer test files. Đây không phải generated-UT compile/JUnit result.
- [ ] Nhận user approval cho endpoint/model, 12 attempts và ngân sách 5 USD.
- [ ] TDD test-only global budget/transport observation và real-provider harness; test bằng loopback HTTP server, không gọi endpoint thật. Reviewer độc lập theo AGENTS trước network.
- [ ] Verify DB test, resolved dependencies/framework và hash manifests; assert guard false, prompt/validator versions/config; giữ originals, không đưa test vào generation tree.
- [ ] Isolation sentinel dry-run trước real dispatch: đặt developer-test sentinel chỉ trong evaluation tree, assert không xuất hiện trong context/prompt ở BR/TP/TC/UT; kiểm tra ExistingTest rows rỗng, approved test rows ban đầu rỗng, retrieval không chứa evaluation tree, cache namespace/provenance riêng. Output thật trước đó chỉ được dùng như authentic GreyTest upstream cho đúng workload, có provenance; không dùng test đánh giá/compile failures làm prompt sửa lần đánh giá đầu.
- [ ] Ghi mọi raw provider output và generated proposal/test với digest **trước sửa/Apply/compile**, metadata model/usage/request ID/status/tier allowlisted; không lưu request headers hoặc key. Credential redaction test phải chặn cả exception/error-body logs. Tắt context file/console logs cho benchmark bằng process-scoped overrides.
- [ ] Chạy pilot; dừng nếu gate fail hoặc hết reservation. Lưu latency/error/retry attempts kể cả thất bại. Không tự sửa generated tests trước evaluation đầu.
- [ ] Compile/JUnit trên evaluation checkout riêng với đúng dependency scope và test framework từ POM; ghi compiler diagnostics/runtime failures/test discovery counts, thời gian riêng. Helper cũ compile bằng toàn GreyTest classpath chỉ dùng diagnostic, không coi là bằng chứng pass trên target POM. Không sửa production code.
- [ ] Phân loại lỗi provider HTTP/timeout, malformed/schema, semantic contract, stale source/provenance, compile/import/dependency, JUnit/runtime/assertion, no-tests-discovered và budget stop. Genuine product semantic bug: báo cáo, không tự sửa logic.
- [ ] Báo raw samples, usage/billing completeness và bottleneck; dừng review trước campaign tiếp theo.

## 5. Metric contract và phân tích

Correlation `campaign/run/update/stage/batch/method/attempt` nối root monotonic intervals, JDBC, worker và HTTP spans. Đo root từ trước Analyze đến GenerateAll trả về, **không cộng** stage/provider/JDBC inclusive durations. Đo envelope Analyze và từng stage gồm transaction boundaries, không gọi runtime stage sub-timers là decomposition đầy đủ nếu chúng bỏ initial status/outer commit. Compile/JUnit đo riêng; có thể thêm user-ready wall tới JUnit hoàn tất và ghi rõ boundary.

- HTTP dispatch → complete body là observed provider/client latency gồm network + server wait/compute; non-streaming client **không tách được** remote queue/network/inference/TTFT. Không đặt thời gian chưa đo là 0.
- Provider wall contribution = union các HTTP intervals clipped vào root / root wall; đồng thời báo sum attempts như tổng provider-work inclusive, không dùng sum/root làm tỷ lệ critical path. Backend-only elapsed = root trừ provider interval union chỉ là exclusive elapsed envelope, không phải backend CPU; backend có thể chạy đồng thời provider.
- Stage calls = mọi outbound attempt, không chỉ successful usage records. Client hiện chỉ ghi usage sau successful HTTP/response JSON parse; thêm test-only observation cho non-2xx/timeout/malformed và raw usage, không suy request count từ recorder summary.
- Provider input/cached/output/reasoning tokens chỉ nhận raw `usage`, đánh dấu missing fields/completeness và `usageSource`. Recorder estimate không được trộn vào measured totals. Calculated tariff cost và actual billing cost tách trường, actual billing thường unavailable.
- Duplicate analysis dùng prompt/input fingerprint + stage/target/attempt reason; cùng prompt sau timeout có thể là retry hợp lệ. Không kết luận duplicate chỉ vì hai method dùng cùng template. Check cache/checkpoint denominator riêng: warm skipped trước cache lookup không phải cache miss.
- Batch ready/start/join, pool acquire/JDBC intervals và PostgreSQL sample waits dùng profiler hiện có; không đổi scheduler. Diagnostic observer/JFR có overhead nên không so trực tiếp với số mock trước hoặc khẳng định production speedup. Pilot chưa đủ mẫu tách overhead thống kê, ghi limitation.
- Prompt duplication đo ký tự/sections và dependency evidence theo hash, không tự cắt prompt. Chỉ đề xuất sau khi có real critical-path evidence và quality results.

## 6. Kết luận preflight và approval

**GO cho chuẩn bị pilot; HOLD mọi provider call cho tới phê duyệt và offline safety gates.** Public docs xác nhận model/endpoint capability, chưa xác nhận account key/quota/balance. Chưa biết stage/provider/backend nào chi phối thực tế; chưa có số đo để tối ưu tiếp. Không push/merge/main, không bật lease optimization, không chỉnh production runtime hoặc test-project production code.

Yêu cầu phê duyệt cụ thể: **pilot `openai / gpt-6-luna / api.openai.com`, tối đa 12 HTTP attempts và ngân sách 5 USD; không tự mở rộng sang campaign six-method/failure-resume.** Lý do phải xin phép là yêu cầu trực tiếp của user: “Dừng để xin phê duyệt trước lần gọi LLM thật đầu tiên.” Không phải skill/auto-review block. Khi chưa được duyệt, `realProviderRequestsMade=0`, usage/cost/latency/pass-rate đều chưa đo.
