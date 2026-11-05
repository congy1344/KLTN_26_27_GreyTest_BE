# Source update Phase 3.2 — kết quả và quyết định

> Workflow: thực hiện tuần tự theo `executing-plans`, kiểm tra Task A trước Task B; reviewer read-only trước khi báo hoàn thành.

**Goal:** giảm source context work và JDBC reads lặp lại, giữ nguyên output/provenance/validation và scheduler.

**Kết quả chính:** source-only context cache đã được thử nghiệm rồi **rollback**, vì giảm parse/allocation không chuyển thành lợi ích wall time đáng tin và warm129 có tín hiệu regression. Runtime cuối chỉ giữ hai tối ưu JDBC nhỏ, có flag rollback: bulk cases và immutable overlay read bundle theo input epoch. Không đổi scheduler, batching, provider, prompt contract hoặc validator.

**Architecture cuối cùng:** giữ nguyên snapshot/index/proof hiện có; bulk case reads và immutable overlay projections trong input epoch. Precommit luôn có input snapshot mới và protected-edit/fence checks. Cache source-only của Task A chỉ còn trong lịch sử thử nghiệm.

**Tech Stack:** Java 17, Spring Boot/JPA, PostgreSQL, JavaParser; không thêm dependency.

**Spec:** yêu cầu Phase 3.2; [critical path baseline](source-update-e2e-critical-path.md), [thiết kế source context và scheduler](source-update-dag-scheduler-design.md). Baseline runtime `ed391fb`; không stage/revert các thay đổi của người dùng ở `.env.example` và `application.yml`.

## Ràng buộc

- Không đổi scheduler, batch grouping, provider, prompt output contract, validation/fingerprint/provenance hoặc database schema.
- Cache chỉ chứa records/strings/lists bất biến; không chứa AST, solver hay entity JPA.
- Key gồm snapshot identity/content, classpath/config/analyzer version, qualified signatures và mapping ID; fallback nếu snapshot không đáng tin.
- Không giữ upstream BR/TP/TC xuyên input scopes; không bỏ fresh read trước commit; không transaction xuyên provider call.
- Paired A/B cùng fixture/config/provider, randomized/interleaved order; control không gắn profiler/JFR; không suy diễn production speedup.
- Giữ flag rollback và chỉ giữ optimization nếu có giảm work/query có số liệu, không gây wall regression đáng kể.

## Kế hoạch và checkpoint

### Task A — source-only context

Files: `GenerationContextBuilder`, `SourceSnapshotSession`, identity accessor của `SourceDependencyIndex`; unit/regression tests và benchmark harness Phase 3.1.

- [x] Test trước: immutable nested lists, reuse đúng signature/snapshot/config, fallback, parallel workers; upstream và precommit vẫn đọc mới.
- [x] Thử cache source class/method contract, excerpts, closure và dependency-call evidence; không chia sẻ thêm mutable solver.
- [x] Flag legacy path cho A/B, sau đó revert toàn bộ cache mới khỏi runtime.
- [x] Paired benchmark + generated UT compile/JUnit; canonical artifacts, impact và provenance tương đương.
- [x] Task A được xác minh, commit/revert có bằng chứng, rồi mới bắt đầu Task B.

### Task B — JDBC theo nhóm

Files dự kiến: context builder, repository queries tương ứng, tests; coordinator/Apply không đổi.

- [x] Trace query theo repository/call site/mục đích; tách preview và safety/precommit reads.
- [x] B1: bulk cases, giữ ordering/membership. Không đổi previous-UT selection khi thiếu bằng chứng.
- [x] B2: bundle overlay trong input scope, nested precommit scope fresh; không cache entity/safety checks xuyên run.
- [x] TDD/regression từng nhóm; B1 smoke, sau đó formal B1+B2 có control thật.

### Hoàn tất

- [x] Randomized/interleaved A/B + profiler overhead control, CPU/allocation/lock/query/context metrics.
- [x] Full regression suite và reviewer; commit riêng A/B1/B2, không push main.
- [x] Kết quả, giới hạn, rollback và evidence; dừng để review.

## Audit ban đầu

Phase 3.1: một method mới có 278 JDBC query, 36 lần đọc toàn update item list; 12 coordinator snapshotContext calls, 20 class parses/4 method parses. Cache phải giảm phần tính source bên trong context, không bỏ 12 boundary calls bảo vệ input/output/commit.

`sourceContextClasses` là source-only: chọn methods, giữ validationSourceCode, tìm supporting types/implementations, kiểm tra parse failure, package/module ambiguity và giới hạn source. `dependencyCalls` đọc source/metadata; có thể reuse kết quả immutable theo batch target. Resource evidence vẫn đọc/validate theo semantics hiện hành. Existing tests metadata, approved artifacts, overlays, previous generated UT và review statuses vẫn thuộc DB input scope, không đưa vào cache source-only.

Các N+1 thấy trong source: `loadApprovedTestCases` đọc từng plan; `generatedUnitTests` đọc từng case. `overlay` xác minh ownership và đọc toàn update item list cho từng loại BR/TP/TC; các read này chưa được bundle chung trong input scope. Đây mới là ứng viên, chưa triển khai Task B trước khi Task A được xác minh.

Trace một cửa sổ legacy ONE_ADDED từ paired smoke (278 query), theo profiler `purpose` + parent span:

| Nhóm query | Số query | Mục đích / quyết định |
|---|---:|---|
| Full update item list | 36 | 12 context overlay; 12 executeBatch; 9 withRun; 2 Analyze; 1 mutate |
| Lease assertions | 26 | Giữ nguyên safety checks |
| Checkpoint complete | 16 | Gồm fencing/checkpoint persistence; giữ nguyên |
| Source update theo ID/project | 19 | Có 12 ownership checks từ overlay; chỉ cân nhắc bundle trong cùng input scope |
| Cases theo plan | 15 | N+1 candidate B1; repository bulk đã tồn tại |
| Rules theo project | 14 | Upstream preview/precommit reads; không cache xuyên scope |
| Project theo ID | 11 | Access/current-state reads; giữ nguyên |
| Rules theo ID | 10 | Parent/target resolution; chưa tối ưu |
| Existing tests theo project | 8 | Read boundary hiện tại; chưa cache nguồn DB |

`withRun`/`executeBatch` là call site ngoài cùng mà proxy quan sát được, có thể chứa private safety helpers; không coi đó là preview rồi xóa. Context full-list 12 query phân bố TP=2, TC=4, UT=6. B2 có thể gộp overlay reads BR/TP/TC trong mỗi input scope, nhưng phải giữ 24 full-list reads còn lại và các fresh scopes trước commit. Query/transaction/commit durations là inclusive, không cộng thành root wall.

## Task A — thử nghiệm hoàn tất, đã rollback

`GenerationContextBuilder.sourceFacts` chỉ memoize hai phần: class/method DTO cùng syntactic supporting-source closure; và dependency-call evidence của BR. Lists ở mọi tầng được copy/unmodifiable; phần tử là records chứa scalar/string. Không cache AST, symbol solver, entity JPA, existing-test DB metadata hoặc upstream artifacts. Semantic dependency closure vẫn dùng cache/index và validation sẵn có.

`SourceSnapshotSession` giữ map theo run. Key gồm UUID snapshot/session, canonical root + file identity, source content digest, classpath digest + watched config digests (cả config chưa tồn tại), analyzer/context version, project/mapping IDs, file path và qualified method signatures. BR implementation-discovery có key riêng với context downstream. Thiếu source/config provenance → uncached loader; snapshot changed → exception như cũ; lỗi thực sự trong loader/validator không bị nuốt.

Thử nghiệm nằm ở commit `a454a4f`; commit `38d058f` đã revert runtime/cache-specific tests sau benchmark. **Cache source-only mới không còn trong runtime cuối cùng.** Shared dependency index/proof của Phase 2 vẫn giữ nguyên. Flag source cache chỉ tồn tại ở revision thử nghiệm để A/B/rollback, không phải cấu hình runtime cuối cùng.

TDD đã quan sát failure vì source slice bị tính lại; sau implementation, các unit test chọn lọc context/fingerprint/session/profiler pass. Test fallback missing classpath cũng được quan sát fail trước khi sửa fallback. Reviewer xác nhận lỗi initialization provenance đã sửa, không còn finding trong Task A. Bổ sung cold parallel initialization, immutable nested lists, fresh upstream/precommit scope, source/POM/Lombok stage proof và ZipFS/unmonitored fallback. Integration mutation giữa provider call đang kiểm chứng riêng.

Môi trường kiểm tra ban đầu gặp Windows paging-file exhaustion; dùng heap bounded/SerialGC, unit tests không fork để giảm tài nguyên. Docker/PostgreSQL test đã được khởi động lại sau connection refused. Không đổi pagefile, tắt tiến trình Java khác hay ghi lên DB ứng dụng. Paired benchmark dùng JVM fork và cùng heap/GC cho cả variants; không dùng số liệu từ lượt khởi động lỗi.

### Số liệu quyết định Task A

10 cặp/workload, balanced AB/BA shuffle seed 32026; 1 warmup pair, con=2, provider mock xác định. Mỗi lượt profile/control có 220 cửa sổ đo (kể cả RESUME_WARM). Uninstrumented control tắt BPP advice/proxy/DataSource wrapping và JFR, không chỉ bỏ trace context; kiểm tra raw control không có span. Giá trị ms; p95 dùng linear interpolation, khác nearest-rank của báo cáo Phase 3.1. Không so sánh tốc độ trực tiếp với Phase 3.1 vì heap/GC, môi trường và protocol đã đổi.

| Workload control | Legacy p50 / p95 | Cache p50 / p95 | Median paired ratio | Bootstrap 95% CI ratio |
|---|---:|---:|---:|---|
| Một method mới | 616.05 / 745.22 | 647.70 / 935.96 | 1.003 | 0.900–1.086 |
| Sáu method | 1133.17 / 1455.12 | 1119.12 / 11343.12 | 1.026 | 0.933–1.189 |
| Dense artifact | 817.28 / 1084.25 | 802.26 / 964.50 | 0.957 | 0.874–1.106 |
| Method mới + 128 unrelated | 1252.56 / 1448.80 | 1178.09 / 1326.70 | 0.939 | 0.833–1.055 |
| Một method warm | 286.95 / 369.98 | 298.82 / 378.75 | 1.002 | 0.896–1.149 |
| Sáu method warm | 471.24 / 569.79 | 503.52 / 1008.57 | 1.103 | 0.994–1.292 |
| 129 files warm | 430.59 / 560.63 | 512.14 / 584.55 | 1.150 | 1.003–1.242 |
| Resume retry | 596.52 / 753.54 | 551.75 / 619.62 | 0.919 | 0.832–1.045 |

Ratio là median của các tỷ lệ **trong từng pair**, không phải tỷ lệ hai median độc lập. Giữ toàn bộ outlier (sáu method có tail lớn); host không được cô lập nên không quy outlier cho cache hay provider. 10 cặp chưa cho tail/CI production ổn định; bootstrap chỉ phản ánh các mẫu đã đo, không chứng minh causal effect giữa các JVM.

Trong profiler, một method: class parses 20→3, method parses 4→1, observed root+worker allocation 84.50→81.32 MB; sáu method class parses 78→25, allocation 335.12→325.12 MB. Root/main allocation và worker intervals khác thread được cộng, không cộng span con lồng nhau. SnapshotContext boundary calls (12 cho một method), JDBC query/update/commit (278/34/69) và mọi fingerprint/validation vẫn giữ. Identity caching đã tránh tăng loader 24→44 như bản thử đầu; bản đo chính là 24→25.

**Quyết định:** giảm parse/allocation có đo được nhưng không chứng minh giảm end-to-end wall; warm129 có tín hiệu regression. Không giữ thêm cache/complexity chỉ để đạt counter đẹp. Task A được xác minh về semantic/regression và hiệu năng, rồi bỏ khỏi runtime theo acceptance criteria. Tiếp tục JDBC với baseline không có cache mới.

Artifact/impact/input+output provenance canonical giống nhau trong mọi pair; warm digests giữ nguyên, resume không duplicate. Mỗi lượt profile/control compile và chạy 36 generated UT nguyên bản trên candidate source, trong warmup trước các cửa sổ đo; tất cả pass. Test source/POM/Lombok/classpath đổi giữa provider call vẫn phát hiện, không công bố stage SUCCEEDED. Reviewer đã kiểm tra cache/key/fallback/concurrency và harness/control; không còn finding sau sửa initialization fallback.

## Kết quả benchmark

## B1+B2 — benchmark formal

Cùng fixture logic/source change/provider/config, mỗi workload 10 cặp legacy/optimized; thứ tự cân bằng 5 AB/5 BA rồi shuffle seed 32026. Có 1 warmup pair trước mẫu đo; javac/JUnit thực hiện trong warmup, không xen vào các measured pairs. Legacy tắt umbrella JDBC flag, optimized bật; source cache mới không tồn tại ở cả hai nhánh. Concurrency 2 giữ nguyên.

Java 17.0.18, PostgreSQL 15.17, DB test riêng `greytest_incremental_test_20261024`; fork heap 512 MB/SerialGC, Maven heap 128 MB. Provider là mock xác định, model metadata `gpt-6-luna`, locale en-US, temperature 0.3, token limits BR/TP/TC/UT=4096/6144/8192/12288. **Không gọi model/API bên ngoài.** Không đổi batch size/grouping hoặc concurrency để lấy speedup.

### Wall: uninstrumented control

ms; p50=median, p95=linear interpolation. Ratio=median của optimized/legacy trong từng pair; bootstrap CI 95% với 10.000 resamples seed cố định. Không loại outlier.

| Workload | Legacy p50 / p95 | B1+B2 p50 / p95 | Paired ratio | CI ratio 95% |
|---|---:|---:|---:|---|
| Một method mới | 729.15 / 1324.63 | 617.73 / 926.08 | 0.921 | 0.740–1.092 |
| Sáu method | 1063.23 / 1200.99 | 1060.68 / 1233.04 | 0.976 | 0.912–1.096 |
| Dense | 844.66 / 1270.62 | 891.89 / 1059.13 | 0.975 | 0.881–1.138 |
| Method mới + 128 unrelated | 1228.18 / 1397.12 | 1194.50 / 1402.63 | 0.962 | 0.889–1.032 |
| Một method warm | 305.36 / 420.90 | 338.04 / 451.63 | 0.995 | 0.927–1.189 |
| Sáu method warm | 475.66 / 515.23 | 459.05 / 502.87 | 0.997 | 0.914–1.027 |
| Dense warm | 380.11 / 472.01 | 390.89 / 584.70 | 0.998 | 0.892–1.269 |
| 129 files warm | 472.10 / 681.45 | 543.11 / 676.02 | 1.073 | 0.845–1.342 |
| Injected UT failure | 1485.64 / 2141.23 | 931.33 / 1749.54 | 0.846 | 0.628–0.952 |
| Resume retry | 617.26 / 872.47 | 585.79 / 1202.54 | 1.013 | 0.893–1.353 |
| Resume warm | 457.27 / 831.74 | 481.33 / 681.29 | 0.988 | 0.858–1.126 |

Không diễn giải median từng cột như causal effect: dense/warm có median tăng trong khi median paired ratio gần 1; dispersion/host noise vẫn lớn. Chưa có bằng chứng regression có ý nghĩa thống kê ở workload chính, nhưng **chưa chứng minh non-inferiority chặt 5%** vì CI còn rộng. Không tuyên bố mọi workload nhanh hơn, đạt production target hoặc giảm latency theo một tỷ lệ cố định. Lý do giữ B1+B2 là giảm round trips/repeated reads được xác nhận, code nhỏ, semantic equivalence và rollback rõ; cần đo project/provider thật trước rollout rộng.

### Query và safety boundaries: profiled series

Median counts, 10 mẫu mỗi nhánh/workload. Timer/profiler không được cộng duration lồng nhau thành root wall.

| Workload | Query legacy→B | Update | Commit | Full update-item list legacy→B | snapshotContext calls |
|---|---:|---:|---:|---:|---:|
| Một method mới | 278→254 | 34→34 | 69→69 | 36→30 | 12→12 |
| Sáu method | 550→506 | 107→107 | 162→162 | 54→42 | 55→55 |
| Dense | 429→401 | 49→49 | 102→102 | 35→29 | 30→30 |
| 129 files | 278→254 | 34→34 | 69→69 | 36→30 | 12→12 |
| Một method warm | 183→171 | 17→17 | 40→40 | 23→20 | 4→4 |
| Resume retry | 313→282 | 23→23 | 111→111 | 54→46 | 27→27 |

Một method mới giảm 24 query (8.6%). B1 bỏ 12 N+1 queries; B2 bỏ thêm 6 duplicate item-list reads + 6 duplicate ownership reads trong cùng input epochs. **24 full-list reads ngoài context preview vẫn giữ nguyên**: coordinator/protected edits/checkpoint/DTO/persistence boundaries. Không loại transaction, không giảm số commit để tạo kết quả.

Class/method parses giữ nguyên sau rollback A: one-added 20/4, six-method 78/24, dense 38/4, warm one-added 5/1. Input/output fingerprint counts, source proof, dependency walks và validators giữ nguyên; giảm query không đồng nghĩa giảm validation.

### CPU, allocation, fingerprint/validation và lock

Profiled median; CPU ms và allocation MB là **observed root/main + batch-worker intervals khác thread**, không cộng span con trùng lấp. Chưa phải process-wide allocation. Windows thread CPU có lượng tử khoảng 15.625 ms, không tính phần trăm cho span ngắn. Monitor wait là JFR lower bound threshold 1 ms, không phải toàn bộ lock/DB row-lock time.

| Workload | CPU legacy→B | Allocation legacy→B | Monitor wait legacy→B | Input FP ms legacy→B | Output FP ms legacy→B | Semantic validation ms legacy→B |
|---|---:|---:|---:|---:|---:|---:|
| Một method | 382.81→398.44 | 84.55→83.93 | 0→0 | 77.72→76.72 | 19.96→23.54 | 33.78→36.96 |
| Sáu method | 695.31→687.50 | 335.19→333.71 | 33.30→32.15 | 240.96→232.18 | 76.08→81.95 | 141.98→132.76 |
| Dense | 414.06→453.12 | 208.84→208.09 | 0→0 | 109.12→107.91 | 39.91→39.82 | 81.46→80.13 |
| 129 files | 843.75→875.00 | 197.43→196.62 | 0→0 | 192.04→182.20 | 15.59→15.14 | 26.68→28.67 |
| Một method warm | 125→125 | 15.52→15.12 | 0→0 | 28.70→28.94 | 3.67→3.44 | 0→0 |
| Resume retry | 281.25→281.25 | 131.39→130.26 | 23.35→23.89 | 106.31→125.69 | 31.96→32.79 | 40.73→39.21 |

Warm semantic timer bằng 0 vì checkpoint được chứng minh reusable theo digest/provenance như legacy; không có validator bị bỏ. CPU/lock không phải nơi tối ưu này chứng minh lợi ích lớn. JDBC là client round trip, repository còn có hydration/local work; chưa đo PostgreSQL server CPU hoặc row-lock wait riêng.

### Profiler overhead control

Control tắt profiler BPP trước khi bean được proxy/wrap và không start JFR; raw control có 0 spans ở mọi cửa sổ. Production counters và root wall/OS process CPU timers vẫn dùng giống nhau ở cả A/B. Không gọi một run còn DataSource/AOP wrappers là uninstrumented.

Tỷ lệ median wall profile/control (legacy / B): one-added 0.916 / 1.033, six-method 1.072 / 1.007, dense 1.053 / 1.006, 129-files 0.960 / 0.923, one-added warm 0.995 / 0.861, resume-retry 0.895 / 0.939. Giá trị dưới 1 cho thấy host/JIT/GC noise giữa các JVM lớn hơn một hằng overhead nhỏ; **không gọi đó là overhead âm**, không trừ các ratio khỏi latency. Instrumented/control là hai JVM riêng, không phải randomized causal experiment về instrumentation; đây là sensitivity estimate có giới hạn. Kết luận wall ưu tiên control; query/work counters lấy từ profile.

### Correctness và quality đối chiếu

- Mỗi series B có 50 measured canonical A/B fixture pairs giống nhau; tổng profile+control=100 pair checks, 440 cửa sổ đo. Source/impact method set, BR/TP/TC/UT, input/output dependency provenance được so sánh.
- Comparator chỉ normalize ID/technical code của fixture và tên khai báo UT dense chứa case ID; giữ business descriptions, assertions, SUT code và parameter ordering. Digest warm giữ nguyên chính xác trong cùng fixture. Đây là comparator cho fixture hiện tại, không thay thế semantic evaluation của production.
- Profile và control mỗi lượt compile/chạy 36 actual generated UT nguyên bản trên candidate source bằng JavaCompiler + JUnit Platform: 72/72 pass, 0 fail. Chạy trong warmup, ngoài cửa sổ timing. Fixture hằng số không chứng minh mutation score/branch coverage/chất lượng trên project thật.
- Resume đều tái sinh 2 artifact, skip 22 target trong cả 10 mẫu/nhánh của profile và control; không lấy duration của hai khối lượng công việc khác nhau để tuyên bố retry speedup. Final canonical artifacts giống nhau, không duplicate.
- Unit/regression giữ lỗi thật ở source/POM/Lombok/classpath đổi, protected edits, corruption/cache provenance và Apply. Source snapshot/index implementation trở về baseline sau rollback A; không có stale-index shortcut mới.

## Task B — các thay đổi giữ để kiểm chứng

### B1: bulk cases, commit `433c5cd`

Hai loader case dùng chung `casesForPlans`, gọi `findByTestPlanIdIn` sẵn có thay N+1 khi có nhiều plan trong source-update scope. Vẫn lọc đúng plan/status và sort DTO như cũ; không DB cache mới. Empty/single-plan và case code trùng dùng lại query cũ; trường hợp code trùng giữ nguyên tie ordering và batch grouping. Không đổi repository API/schema. Chưa đổi query previous-generated-UT: schema cho phép nhiều UT/case và legacy chọn `findFirst`; chưa có bằng chứng lợi ích đủ để mở rộng phần đó.

TDD bulk-read test fail trước sửa, pass sau sửa; nested input scope đọc được expected result mới, flag false gọi đường cũ. Reviewer không có finding. Paired smoke 2 cặp/workload có canonical artifact/impact/provenance giống nhau, warm/resume đúng và actual UT compile/JUnit pass. Đây là proof query reduction, chưa phải benchmark tail đáng tin:

| B1 smoke | Legacy query | Bulk query | Legacy p50 | Bulk p50 |
|---|---:|---:|---:|---:|
| Một method mới | 278 | 266 | 801.09 | 695.84 |
| Sáu method | 550 | 530 | 1797.97 | 1420.87 |
| Dense | 429 | 413 | 893.52 | 921.74 |
| 129 Java files | 278 | 266 | 1199.66 | 1229.52 |
| Một method warm | 183 | 177 | 366.44 | 358.67 |
| Resume retry | 313 | 298 | 663.96 | 1136.00 |

Các duration smoke giữ cả regression/outlier, không dùng hai mẫu để tuyên bố speedup. [Evidence B1](benchmarks/source-update-phase3-2-b1-smoke.json).

### B2: read bundle trong input epoch, commit `c0cef26`

`DraftReadKey(projectId, updateId)` nằm trong map ThreadLocal của `withInputSnapshot`, không nằm trong job-wide/source cache. Value là record chứa owned update version và list proposal records bất biến (ID, type, action, review status, afterData string, version, userRevision). Không giữ entity JPA. Ba overlay BR/TP/TC dùng cùng một read bundle trong input epoch thay vì đọc lại toàn list và ownership từng loại.

Invalidation là **lifecycle của input epoch**: mỗi worker/batch có map riêng; nested precommit map mới luôn query lại ownership và rows/version; thoát scope thì discard/restore map cũ. Không dùng cache cũ để quyết định commit, protected edit hoặc lease/fence. Ngoài input scope, đọc mới như cũ. Quy tắc KEEP/REJECTED/REMOVE/CREATE/parent-link và thứ tự items không đổi.

Test mới được quan sát fail trước implementation rồi pass: đọc preview bắt đầu scope, sửa entity underlying; bundle vẫn giữ revision ban đầu, nested scope nhận revision mới; ownership bị mất vẫn bị từ chối ở nested scope. Chỉ hai item-list reads cho hai epoch, không chia sẻ mutable entity. Toàn nhóm context/fingerprint/snapshot/profiler tests pass và reviewer không thấy finding; đang đo formal end-to-end.

Rollback: `greytest.source-update.jdbc-read-optimization-enabled=false` tắt cả B1/B2; `greytest.source-update.overlay-read-bundle-enabled=false` chỉ tắt B2, giữ B1. Hai flag mặc định true sau formal benchmark/regression; đây là cấu hình khởi động, nên drain/restart backend theo quy trình hiện có trước đổi flag. Không có migration hoặc scheduler flag mới.

## Verification và giới hạn còn lại

Full regression command kết thúc **exit 0**: **148 suites, 1878 tests = 1850 pass + 28 skip, 0 failures/errors**. Chỉ cộng Surefire XML có thời gian ghi từ lúc bắt đầu `target/phase3-2-regression-final.log`, không cộng stale report. Skip gồm 12 test opt-in mỗi class profile/paired (kể cả inherited tests), 2 oracle acceptance, 1 golden manifest, 1 scan benchmark. Các paired benchmark/UT execution đã chạy riêng với opt-in bật.

TDD/regression có bằng chứng cho bulk query/fallback ordering, immutable bundle không giữ entity, nested scope đọc revision/ownership mới. Full suite giữ tests impact/dependency, source/config mutation, protected edits, retry/resume, cache corruption/provenance, concurrent batch và Apply. Reviewer read-only đã review Task A, fallback/identity refinement, harness và B1/B2; các finding initialization fallback đã sửa trong experiment trước khi rollback. Không còn finding code cần sửa ở các lượt review đó.

Root production change chỉ nằm trong `GenerationContextBuilder`; `SourceSnapshotSession`, `SourceDependencyIndex`, diff/impact, scheduler, provider, validator/fingerprint implementation và database schema không đổi so với baseline. Cache source-only mới đã bị loại. Không push, merge hoặc triển khai ứng dụng.

Không đo production traffic, provider network/queue, DB server CPU/row-lock riêng, mutation score hoặc compile/JUnit rate của project người dùng. Fixture dùng no-arg methods/hằng số và dense STMT artifacts; không đại diện mọi Spring/Lombok/DTO graph. CI của wall còn rộng, không đủ chứng minh không có mọi regression nhỏ. Hai flag cho phép rollback khi workload thật khác fixture. Không dùng số liệu historical Phase 3.1 làm phép so sánh speedup với lượt này.

## Evidence và reproduce

Các file compact giữ đủ mỗi mẫu, paired statistics/CI, metric counts, CPU/allocation, JDBC purposes, JFR aggregates và semantic SHA-256; full representative provenance/trace được giữ cho mỗi workload/variant. Raw spans/JFR vẫn ở `target`, không commit binary hoặc hàng trăm MB raw JSON.

- [A profile](benchmarks/source-update-phase3-2-a-profile.json), [A uninstrumented control](benchmarks/source-update-phase3-2-a-control.json).
- [B1 smoke](benchmarks/source-update-phase3-2-b1-smoke.json).
- [B1+B2 profile](benchmarks/source-update-phase3-2-b-profile.json), [B1+B2 uninstrumented control](benchmarks/source-update-phase3-2-b-control.json).
- [Harness](../src/test/java/com/greytest/service/PairedSourceUpdateBenchmarkTest.java), [profiler](../src/test/java/com/greytest/service/CriticalPathProfiler.java), [summary/self-check](benchmarks/summarize-source-update-paired.py).

Từ backend, dùng credentials **DB test riêng** đang quản lý cục bộ; URL phải đúng `jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024` để test condition bật. Không trỏ vào DB ứng dụng. Đặt provider mock/model metadata và heap giống protocol:

```powershell
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024'
# SPRING_DATASOURCE_USERNAME/PASSWORD: credentials của DB test, không commit secret.
$env:LLM_PROVIDER='mock'
$env:LLM_MODEL='gpt-6-luna'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
$env:MAVEN_OPTS='-Xms16m -Xmx128m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC'
mvn -q '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' '-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=10' '-Dbenchmark.optimization=B' '-Dbenchmark.instrumentation=true' '-Dbenchmark.jfr=true' test
mvn -q '-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' '-Dtest=PairedSourceUpdateBenchmarkTest#profileCriticalPath' '-De2e.profile=true' '-De2e.samples=10' '-Dbenchmark.optimization=B' '-Dbenchmark.instrumentation=false' '-Dbenchmark.jfr=false' test
python docs/benchmarks/summarize-source-update-paired.py --self-check
python docs/benchmarks/summarize-source-update-paired.py target/phase3-2-b-profile/samples.json --compact-output target/phase3-2-b-profile/compact.json
python docs/benchmarks/summarize-source-update-paired.py target/phase3-2-b-control/samples.json --compact-output target/phase3-2-b-control/compact.json
mvn -q '-DargLine=-Xms16m -Xmx768m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' '-Dspring.test.context.cache.maxSize=2' '-Dbenchmark.phase=phase3-2-regression' test
```

Để replay A, dùng checkout/worktree **riêng** ở `a454a4f`, chạy cùng protocol với `benchmark.optimization=A`. Revision cuối đã bỏ code A và harness cuối chỉ chạy B; không checkout đè workspace có user edits. Giữ token limits/model/locale trong evidence khi so sánh; không cần API key thật vì client mock. `benchmark.phase` riêng tránh ghi đè protected baseline `before`.

Dừng tại kết quả này để review, không triển khai DAG scheduler hoặc bước tối ưu tiếp theo.
