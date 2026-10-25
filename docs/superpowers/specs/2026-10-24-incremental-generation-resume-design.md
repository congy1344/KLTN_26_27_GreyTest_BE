# GreyTest Incremental Generation: audit và thiết kế tối ưu

Ngày: 2026-10-24. Trạng thái: **DRAFT — cần duyệt thiết kế và migration trước khi triển khai**.

## 1. Phạm vi và hiện trạng

Mục tiêu: chỉ phân tích và sinh phần thiếu hoặc không còn hợp lệ, giữ thứ tự
BR → Test Plan → Test Case → Unit Test, bảo toàn dữ liệu người dùng.
Không thêm Redis, message broker hoặc chạy build/source người dùng trên server.

Đã đọc luồng frontend upload/clone, analyze, generate từng stage, generate all,
review, apply/cancel; các controller, source revision/storage, diff/impact,
context builder, prompt/parser/validation, batch executor, quota/cache và repository liên quan.
Các đường dẫn backend trong bảng dưới tính từ `src/main/java/com/greytest/`;
frontend tính từ repo `KLTN_26_27_GreyTest_FE`.

| Vấn đề | File, function hiện tại | Nguyên nhân và hệ quả |
| --- | --- | --- |
| Chạy lại cả bốn stage | FE `src/features/projects/components/SourceUpdateModal.tsx:380`, `handleGenerateAll` | Luôn gọi BR, TP, TC, UT. Không có checkpoint server để chỉ chạy phần thiếu. |
| Dấu hoàn tất mất khi đóng modal | Cùng file, `completedStages` tại 113 và reset tại 133 | Trạng thái chỉ trong React, không xác minh fingerprint khi mở lại hoặc retry. |
| Request không claim nguyên tử | `service/IncrementalGenerationService.java:175`, `generateIncrementalStage`; `controller/SourceUpdateController.java:104` | Đọc trạng thái rồi save GENERATING riêng; hai request có thể cùng vượt qua. Lock fallback không khóa bản ghi đã đọc được. |
| Snapshot bị parse lại giữa batch | `service/agent/GenerationContextBuilder.java:499`, `sourceAnalysis`; `service/GenerationJobContext.java:31`, `memoize` | Endpoint cập nhật không bind shared job context. Khi inputs null, memoize gọi loader mỗi lần; `SnapshotAnalysisLoader.load` scan toàn snapshot. |
| Candidate parse hai lần ngay ở bước diff | `service/diff/SourceDiffService.java:67`, `compareSnapshots`, `extractMethodSnapshots` | Scan candidate để kiểm tra lỗi rồi scan lại để trích method. |
| AI cache không được ghi ngoài transaction | `service/agent/LlmResponseCache.java:51`, `putAfterSuccessfulTransaction`; `service/agent/AIAgentService.java:322` | Chỉ đăng ký afterCommit nếu có transaction ngoài, nhưng incremental cố ý không giữ transaction qua LLM. |
| Cache mất khi restart, thiếu cấu hình trong key | `LlmResponseCache`, constructor và `key` tại 68 | Chỉ là LinkedHashMap trong RAM. Key provider/model + prompt không bao gồm đầy đủ temperature, token limit và phiên bản validator. |
| Sinh lại các target đã có output | `IncrementalGenerationService`, `generateIncrementalBusinessRules/TestPlans/TestCases/UnitTests` tại 229/293/377/450 | Chọn lại impact IDs và draft CREATE IDs; không kiểm tra output đã thành công và fingerprint còn khớp. |
| Mất kết quả batch BR trước khi lỗi batch sau | `generateIncrementalBusinessRules` | Gom toàn bộ response trước khi persist; một batch lỗi có thể khiến response thành công trước đó chưa có checkpoint/draft. |
| Có thể ghi đè review của người dùng | `IncrementalGenerationService.java:511`, `saveSourceUpdateItem`; `persistTestCases` tại 398 | Ghi lại afterData, action, reviewStatus; có nhánh xóa artifact CREATE tạm. Chưa kiểm tra nguồn gốc quyết định và revision của người dùng. |
| Re-analyze xóa toàn bộ đề xuất | `service/SourceUpdateService.java:237`, `analyzeUpdate`, delete tại 274 | Xóa items trước khi tạo lại, không bảo vệ đề xuất đã sửa/duyệt và checkpoint đang chạy. |
| Dependency invalidation chưa đầy đủ | `service/diff/ImpactAnalysisService.java:72`, `analyzeImpact` | Lọc Service trước khi xét dependency. affectedServiceMethods có callers nhưng chọn BR vẫn dựa changedMethods trực tiếp. |
| Caller graph chưa đủ làm cache dependency | `SourceDiffService.java:236`, `buildCallerGraph` | Dựa tên method cùng class, không đủ định danh receiver/module/overload cho dependency chính xác. |
| Context downstream có thể đọc giá trị cũ | `GenerationContextBuilder`, `buildTestPlanContext/buildTestCaseContext/buildUnitTestContext`; incremental persist* | UPDATE nằm trong SourceUpdateItem.afterData, nhưng context lấy BR/TP/TC từ repository chính. Cần overlay draft của đúng update. |
| BR đang sửa analysis đang hoạt động | `IncrementalGenerationService.java:620`, `resolveTargetMethodBatches` | Có save sourceCode mới của method trước Apply; cần đọc candidate trong bộ nhớ để không làm source/context active lẫn với draft. |
| Cache hit chưa chứng minh cả stage hoàn tất | `AIAgentService.validateSemanticOutput`, BR validation trong `BusinessRuleService.validateGeneratedBusinessRules` | Validation có nhiều tầng. Cache response hoặc một afterData không thể thay thế kiểm tra đủ target/coverage và persist thành công. |
| Apply không có kiểm tra checkpoint mới | `SourceUpdateService.applyUpdate`; FE `handleApply:515` | FE kiểm tra có bất kỳ TC output thay vì đủ mọi target; còn tự duyệt pending. Cần backend xác nhận không còn stale/missing/blocked/conflict. |
| Chưa có metric theo run | `IncrementalGenerationService`, `AIAgentService.call` | Log gọi AI không đủ phân biệt cache hit, checkpoint skip, actual provider attempts và tổng thời gian. |

Hiện tại vẫn có lọc impact, không sinh lại toàn bộ project trong mọi lần cập nhật.
Tuy nhiên, chỉ thêm cache vào code hiện tại sẽ giữ lại các lỗi context, concurrency
và mất review ở trên. Phải xử lý cùng cơ chế commit checkpoint.

## 2. Hai phương án lưu trữ

**Đề xuất: PostgreSQL hiện có.** Thêm cache và checkpoint riêng, transaction ngắn
để claim/commit. Có thể resume sau restart và phối hợp nhiều request/backend instance.
Không giữ connection/row lock trong lúc gọi LLM.

Phương án khác là JSON/file cache với atomic rename và process lock. Cách này chỉ
phù hợp một backend trên local filesystem; không chọn vì khó phối hợp nhiều instance
và commit nguyên tử với artifact trong PostgreSQL.

Migration đề xuất: `V27__incremental_generation_checkpoint_and_cache.sql`.
Không drop/truncate dữ liệu và không sửa migration cũ.

- `llm_response_cache`: project scope, stage, fingerprint/version, validated response,
  response digest, created/expiry timestamps. Unique `(project_id, stage, fingerprint)`.
  Chỉ chứa response đã qua tất cả validation của stage; lỗi không được lưu như cache success.
- `generation_checkpoint`: update, stage, stable target key, input fingerprint,
  dependency manifest, trạng thái, output item IDs/digest/revisions, attempt/lease token,
  lease deadline, fencing version, error và metric. Unique `(source_update_id, stage, target_key)`.
  Một key nội bộ điều phối toàn update để các stage của cùng update không ghi đồng thời.
- Thêm `user_revision` và optimistic `version` cho `source_update_item`, optimistic
  `version` cho `source_update`. Không thêm fingerprint vào BR description/review note
  hoặc sửa schema JSON do LLM trả về.
- Giữ dữ liệu legacy; item đã review nhưng chưa có provenance được coi là cần bảo vệ.
  Không tự xóa duplicate lịch sử để migration pass.

## 3. Shared Analysis Context

- Một `GenerationRunContext` bind actor, locale, run ID, metric và shared immutable inputs;
  các batch nhận cùng instance qua cơ chế wrap hiện có và luôn clear ThreadLocal.
- Shared context chứa candidate/base analysis, index module/type/method, dependency index,
  file/build/resource hashes, Java/framework info và prompt/config versions.
- Dùng `source_revision.analysis_snapshot` hiện có để persist analysis đã hoàn chỉnh,
  kèm content digest, parser/index format version. Không ghi response/checkpoint vào trường này.
- Phần persist giữ identity source ổn định; ánh xạ JavaMethod/JavaClass ID hiện tại được
  kiểm tra lại khi dựng draft context, không dùng ID của record tạm đã bị hủy/xóa ở lần trước.
- Reuse analysis giữa các stage/retry nếu snapshot identity và digest còn khớp;
  không reuse khi thiếu file, parse lỗi, parser version đổi hoặc source thực tế đổi.
- Phân biệt hash/đọc file để xác minh tính toàn vẹn với parse AST; metric đếm riêng.
- Không cache BR/TP/TC mutable trong shared source analysis. Context downstream đọc overlay
  draft của đúng update, tôn trọng user revision và kiểm tra quan hệ cha-con.
- Dùng scan candidate đã có trong compareSnapshots, không scan lần hai để extract.

## 4. Fingerprint và dependency

Định dạng versioned SHA-256 của canonical JSON, thứ tự danh sách/key ổn định.
Fingerprint của artifact dựa vào các input có ảnh hưởng, không dựa toàn project/revision ID.
Không đưa timestamp, thời gian chạy, progress, vị trí dòng hoặc output AI tự sinh của
chính artifact vào input; tránh self-invalidation sau mỗi lần sinh.

Thành phần chung: project/module identity, stage/schema/validator version, target identity,
source slice + dependency manifest, input nội dung/revision liên quan, prompt file và
source-contract digest, locale, provider/model/endpoint identity, temperature, token limit,
Java/test framework/build/resources liên quan. Không đưa secret vào key/log.

| Artifact | Định danh và input đặc thù |
| --- | --- |
| BR | Module + FQN + method signature + branch identity; method AST và các dependency cần cho rule. Một lần gọi có thể trả nhiều BR nhưng mỗi output có fingerprint riêng. |
| Test Plan | Logical rule/covered-rule IDs + nội dung rule hiệu lực trong draft, source/dependency của method được liên kết. |
| Test Case | Plan identity + nội dung plan, covered BR hiệu lực, fixture/resource/dependency liên quan. |
| Unit Test | Case identity + đủ input/expected result đã duyệt, BR/TP context cần thiết, existing tests đầu vào và dependency/build contract. |

- Thực hiện checkpoint theo đơn vị công việc ổn định (method/rule group/plan/case);
  batch LLM chỉ là cách gom các đơn vị còn thiếu, không phải identity của checkpoint.
- Reuse response giữa revision chỉ khi logical IDs còn phù hợp; không tự gán response
  mang ID của artifact/project khác sang target hiện tại.
- Dependency identity có module + FQN + signature. Ưu tiên resolver/relations trong project;
  không nối graph chỉ vì hai method cùng tên.
- Chỉ đưa body của target/reachable helper/collaborator vào dependency closure. Khai báo
  field, constructor, annotation, DTO/enum và resource liên quan vẫn là dependency.
  Một method không liên quan trong cùng file không tự làm mọi artifact stale.
- Dependency index còn ghi các production API mà output test thực tế gọi tới sau validation,
  tránh tái dùng test gọi một helper khác nhưng helper đó đã đổi.
- Nếu không xác định chắc dependency, đánh dấu không đủ bằng chứng để skip và ghi lý do;
  không đoán quan hệ hoặc invalidate toàn project để che sự mơ hồ.
- Thay đổi dependency/DTO/build contract được xét ngay cả khi Service body không đổi;
  lan truyền tới đúng artifact phụ thuộc, giữ nguyên các artifact độc lập.

## 5. Resume, cache và concurrency

1. Kiểm tra project access và snapshot/update còn hoạt động.
2. Claim điều phối update bằng transaction ngắn/unique key/CAS; request thứ hai nhận
   trạng thái đang chạy thay vì chạy thêm LLM. Lease có heartbeat và fencing version.
3. Đọc checkpoint; chỉ skip khi SUCCEEDED, input/dependency fingerprints còn khớp,
   output còn đủ, digest/revisions khớp và không có conflict.
4. Với đơn vị stale/missing/failed, bảo vệ review và claim đơn vị trước khi sinh.
5. Cache hit phải parse, kiểm tra đủ target/coverage/schema và semantic validation với
   context hiện tại. Cache hỏng bị loại; cache hit không tự trở thành checkpoint success.
6. Cache miss gọi LLM qua retry/budget hiện có. Đếm mọi lần gọi provider thực tế, kể cả lỗi.
7. Sau tất cả validation, persist cache bằng bean/transaction REQUIRES_NEW độc lập với
   transaction xử lý chính. Cache là response hợp lệ, không khẳng định artifact đã commit.
8. Transaction ngắn commit output draft và checkpoint SUCCEEDED cùng nhau; kiểm tra
   fencing token, input snapshot, user revision, output ownership và unique identity.
   Nếu persist lỗi thì rollback cả output và checkpoint, vẫn có thể dùng response cache
   hợp lệ để retry bước persist sau khi revalidate.
9. Batch hỗn hợp lưu checkpoint riêng cho từng đơn vị hợp lệ; thiếu/blocked không được
   đánh dấu thành công. Stage không hoàn tất nếu còn missing/failed/blocked/conflict.
10. Worker hết lease không được commit muộn; owner mới phải dùng fencing token mới.
    Analyze/apply/cancel dùng cùng cơ chế điều phối để không đua với generate.

Không thể đảm bảo đúng một lần gọi provider nếu process chết sau khi provider trả lời
nhưng trước khi lưu response; không tuyên bố exactly-once LLM. Cam kết là không lặp
response/checkpoint đã commit hợp lệ và không có duplicate/lost artifact khi retry.

## 6. Bảo toàn người dùng và draft context

- Giữ BR/TP/TC/UT active hiện có đến khi người dùng Apply; dùng candidate source và
  overlay draft trong bộ nhớ thay cho ghi đè sourceCode analysis active trước Apply.
- Giữ hợp đồng ID và các record CREATE tạm đang có, nhưng kiểm tra ownership theo update;
  không xóa record ngoài draft hoặc record đã được người dùng sửa/duyệt.
- Mọi PATCH review/edit tăng user_revision, được kiểm tra optimistic version khi commit.
- Output hợp lệ còn nguyên thì skip. Khi fingerprint stale nhưng item đã được người dùng
  chỉnh sửa/phê duyệt/từ chối, giữ nguyên item và lưu đề xuất cạnh tranh vào checkpoint CONFLICT.
- UI hiển thị hai lựa chọn rõ ràng: giữ bản người dùng hoặc nhận đề xuất mới; dùng expected
  version khi giải quyết. Giữ bản người dùng vẫn cần revalidate; không bỏ gate để Apply.
- Kiểm tra digest/version của artifact active tại Apply để phát hiện chỉnh sửa ngoài modal
  sau lúc tạo draft. Không tự xóa nội dung hay reset cờ chỉnh sửa để vượt qua conflict.
- Re-analyze không xóa review. Khác revision/scope cần reconcile/upsert hoặc trả conflict.
- Áp dụng cùng guard cho BR, TP, TC và UT; thêm coverage/enum/test fixture không được dùng
  để thay đổi expected result hoặc business rule đã duyệt một cách ngầm định.

## 7. API, UI và metric

- Giữ API sinh từng stage. Thêm API server-orchestrated generate-all để một run có chung
  context/lease/metric, giữ thứ tự stage và dừng tại lỗi; checkpoint vẫn theo artifact.
- Đối chiếu `GenerationJobService` khi claim để luồng cập nhật không vượt qua guard sinh
  đang có của cùng project; giữ giới hạn concurrency và cách tính quota hiện hành.
- `SourceUpdateDto` trả generation summary: trạng thái stage, skipped/generated counts,
  missing/blocked/conflict targets và metric của run. UI không suy luận success từ có một item.
- Mở lại modal lấy checkpoint server; bấm Sinh Tất Cả là resume có xác minh fingerprint.
  Frontend không tự quyết định skip dựa biến completedStages cũ.
- Metric: analysis cache hit/miss, AST parse count/time, LLM cache hit/miss/invalid,
  provider attempts/errors, checkpoint hit/miss, units/stages skipped, stage duration,
  elapsed wall-clock của run, conflict/lease contention. Counter concurrency-safe.
- Log có runId/updateId/stage/target và lý do invalidate/skip, không in source/prompt/secret.
  Dùng logging và DTO/JSON hiện có, không thêm hệ thống giám sát riêng.

## 8. File dự kiến và thứ tự triển khai

1. Harness regression/benchmark và snapshot baseline trước sửa production.
2. `GenerationJobContext`, `SourceDiffService`, `SnapshotAnalysisLoader`,
   `GenerationContextBuilder`, `SourceRevision` storage: shared/persisted analysis và draft overlay.
3. Migration V27; entity/repository/services checkpoint và validated response cache;
   `SourceUpdateItem`/`SourceUpdate` version; fingerprint/dependency index.
4. `AIAgentService`, `LlmResponseCache`, stage validation: config-aware validated cache,
   actual-call metric và cache write độc lập.
5. `IncrementalGenerationService`: stable work units, atomic persist+checkpoint,
   partial batch recovery, user protection và source-update lifecycle guards.
6. `ImpactAnalysisService`: dependency-aware incremental invalidation.
7. `SourceUpdateController`, DTO/mapper, FE API/types/SourceUpdateModal: resume/status/conflicts.
8. Regression, benchmark sau, reviewer read-only và báo cáo kết quả/giới hạn thực tế.

## 9. Regression và benchmark bắt buộc

Các scenario dùng cùng source fixtures, trạng thái DB, provider test, concurrency và config
trước/sau. Hash output và assert dữ liệu để phát hiện tối ưu bằng cách bỏ việc cần thiết.

| Scenario | Kết quả cần chứng minh |
| --- | --- |
| Source không đổi, chạy lần hai | Output/ID/user edits giữ nguyên; zero provider call với checkpoint hợp lệ. |
| Đóng modal/restart backend | Resume từ DB, không dựa RAM/React; không parse lại revision hợp lệ. |
| Đổi một method giữa nhiều method | Chỉ closure phụ thuộc bị stale; phần độc lập không gọi LLM. |
| Đổi dependency, DTO/enum/build/resource | Các artifact thật sự phụ thuộc bị invalidate dù Service body không đổi. |
| Lỗi batch sau khi batch trước thành công | Đơn vị trước có checkpoint; retry chỉ chạy phần thiếu, không nhân bản CREATE. |
| JSON lỗi/response thiếu/semantic fail | Không có cache/checkpoint success; partial hợp lệ được tách rõ. |
| Hai request/multi-instance cùng update | Một owner; không duplicate LLM khi claim còn hiệu lực, không duplicate artifact. |
| Hết lease/process chết | Owner cũ không commit; owner mới resume; test crash trước/sau cache và checkpoint commit. |
| PATCH đồng thời, approved/modified/rejected | Không mất review; version conflict rõ; không auto-delete artifact đã được người dùng giữ. |
| Prompt/config/validator đổi | Chỉ stage/descendant chịu ảnh hưởng không còn checkpoint hợp lệ. |
| Cùng tên method ở class/module/overload khác | Không invalidation nhầm hoặc cache chéo project. |

Đo wall-clock thật, parse count, provider-call count, cache/checkpoint hits, stage skips,
DB writes và output digest. Tách cold run, unchanged resume, one-method change,
dependency change, retry và concurrent run; warm-up riêng, nhiều lần lặp, báo median/p95
và raw samples. Không lấy thời gian `mvn test`/startup Spring làm thời gian sinh test.

Benchmark integration local dùng provider test xác định để so sánh repeatable chi phí
pipeline và call count; phải ghi rõ provider là test, không gọi là tốc độ LLM production.
Benchmark provider thật là bảng riêng nếu môi trường cho phép, không thay bằng số giả.
Không đưa phần trăm cải thiện vào báo cáo trước khi có số đo trước/sau cùng workload.

## 10. Evidence hiện có và trạng thái phê duyệt

- Baseline regression backend ngày 2026-10-24: 61 tests, 0 failures/errors/skipped,
  `BUILD SUCCESS`, Maven total 28.181 s. Đây là thời gian chạy suite, **không phải benchmark pipeline**.
- Baseline frontend: 15 tests pass, Vitest duration 2.97 s, cũng không phải benchmark pipeline.
- Log nằm tại workspace `.greytest-generation-verification/incremental-optimization-baseline-tests.log`
  và `incremental-optimization-baseline-ui.log`.
- Chưa sửa production code, chưa tạo/chạy migration V27; chưa có số benchmark trước/sau
  của pipeline mới. Các thay đổi source ở lượt trước được giữ nguyên.

Cần người dùng duyệt lựa chọn PostgreSQL + migration additive, chính sách bảo toàn/conflict
và phạm vi dependency/benchmark trên trước khi bắt đầu phần triển khai này.
