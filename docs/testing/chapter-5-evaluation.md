# 5.3 Đánh giá hiệu quả — dữ liệu hiện có

Ngày tổng hợp 10/10/2026. Chưa đủ dữ liệu so sánh 3 phương pháp hoặc kết luận chất lượng AI tổng quát. Metric N/A là chưa đo/không có mẫu số phù hợp, không phải 0.

## Tính đúng đắn

Backend retest 553/553 invocation thực thi đạt, 3 skipped trong 556; frontend 175/175 test đạt. Đây là correctness trong bộ test hiện có, không đo độ chính xác ngoài bộ test. Integration 9/9 gồm 5 full-stack MockMvc/PostgreSQL + 4 controller; LLM boundary fixture. Counter coverage integration 50%→100% thuộc XML tổng hợp, không phải coverage đo từ generated tests. [Baseline](D:/.KLTN/outputs/chapter-5/baseline-20261009/baseline-notes.md), [Integration](./chapter-5-integration.md).

## Pilot AI tổng hợp

Một trial, hai đường direct/reviewed, 7 call và 0 retry/provider error. Provider openai, api.vietapi.tech, gpt-5.6-luna, temperature0, output max6000/call. JDK23 compile fixture release17. 2/2 cặp giữ test equality: implementation >= đạt và > fail nghiệp vụ. Đây là khả năng giữ kỳ vọng equality trên fixture, không phải accuracy100%.

| Metric | Thực đo | Mẫu số/phạm vi |
|---|---:|---|
| Thời gian Direct ba stage | 82,232 s | 1 đường, gồm call/parse/ghi response |
| Reviewed ba stage | 41,748 s | 1 đường, chưa gồm BR review |
| BR review | 6,756 s | 1 call |
| Reviewed cộng BR review | 48,504 s | 1 đường |
| Surefire live method | 134,681 s | Có setup, compile/execution; không latency HTTP |
| Provider total token | 39.992 | 7 usage record; không cộng cached/reasoning vào total |
| Input/output token | N/A | Adapter ghi0 nhưng không breakdown đáng tin cậy |
| Raw case/selected equality | 11/2 | Direct6 + Reviewed5; selected không đại diện toàn output |
| Case ngoài domain | 3 | Negative balance, zero/negative amount; không dùng làm general accuracy |
| Human UMR, stability | N/A | Không human edits, không nhiều trial matching |
| JaCoCo line/branch | N/A | Trial này chưa đo |

[Raw evidence và phép đo](D:/.KLTN/outputs/chapter-5/live-oracle-20261009/evidence.md). Không dùng một trial để kết luận reviewed nhanh hơn nói chung; hai đường có prompt/output khác và không lặp. Không tính chi phí tiền khi chưa có đơn giá provider.

## Hiệu năng sơ bộ — snapshot Oct9, chưa nghiệm thu lại

Nguồn [summary.json](D:/.KLTN/outputs/chapter-5/system-20261009/run2/summary.json) và raw read-c*.json. Workload fixture local, client/server cùng máy, 100 request mỗi concurrency, tổng400. Giữ nguyên snapshot đo Oct9; source/measurement evidence đã được đối chiếu, không chạy lại để tạo số giả. System evidence pipeline mới dùng lượtOct10 riêng. Không có SLA đã chốt hoặc cam kết production. Máy baseline Windows11/i5-11400H, RAM N/A; không gán cấu hình baseline cho server nếu run không có metadata riêng.

| Concurrency | n | Median ms | p95 ms | Request/s | Error rate |
|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 25.121 | 38.279 | 40.924 | 0 |
| 3 | 100 | 20.979 | 32.44 | 151.714 | 0 |
| 5 | 100 | 12.418 | 29.753 | 327.798 | 0 |
| 10 | 100 | 15.119 | 78.775 | 468.588 | 0 |

Throughput = n/duration; median/p95 theo raw request. Thời lượng mỗi mức ngắn, warm-up/JIT/cache và nhiễu local có thể chi phối; số p95 ở concurrency10 cao hơn c5 không đủ chứng minh điểm chịu tải tối đa. Không tải LLM thật.

## Quy mô source sơ bộ — synthetic tách corpus OSS

3 size ×3 repeat =9 lượt. Fixture mỗi class 1 method. Mỗi lượt status200, counters actual khớp và failedParse0. Báo tất cả lần, không chỉ best case. Memory N/A.

| Class/method | n | Analyze ms lần1/2/3 | Min–max ms |
|---:|---:|---|---|
| 10 | 3 | 177.766 / 130.245 / 111.348 | 111.348–177.766 |
| 100 | 3 | 1124.308 / 1013.897 / 485.555 | 485.555–1124.308 |
| 500 | 3 | 5307.041 / 2948.971 / 2453.148 | 2453.148–5307.041 |

Phân tích một máy tăng theo workload tổng hợp; chưa chứng minh horizontal scaling, bộ nhớ tối đa hay source có độ phức tạp tương đương dự án thật. [Dataset](./chapter-5-dataset.md): 5 OSS đóng băng commit/license; BR HUMAN_REVIEW_PENDING và BUILD_NOT_EXECUTED. Parser corpusOct10 đã có kết quả readiness5/5 bên dưới; AST readiness không chứng minh test compile/oracle đúng.

## Bảo mật và khả năng sử dụng

Integration IT-04-PRICE chứng minh missing/bad token401, cross-owner403 và snapshot state không đổi, owner pause được. Không khẳng định mọi SEC master đạt: chưa chứng minh expired/disabled JWT, toàn bộ endpoint/mutation, traversal variants và sentinel/network XML checks theo catalog. SystemOct10 bổ sung các biến thể ở bảng5.2 và link bên dưới; master invariants còn chưa đủ.

UAT: chuẩn bị nhiệm vụ/phiếu cho 2 người tại [chapter-5-uat.md](./chapter-5-uat.md); chưa có ngày tham gia hoặc kết quả. Tỷ lệ hoàn thành, thời gian, trợ giúp, điểm1–5 đều N/A. Dự kiến tổ chức sau hai flow và system evidence ổn định; không dùng Codex thay người tham gia.

## Giới hạn và phần còn thiếu

75 lượt =5 OSS ×3 phương pháp ×5 lần chưa chạy; chưa có manual comparison độc lập, UMR/stability hay coverage theo phương pháp. Corpus source staging chưa là workspace build hoàn chỉnh. Provider có thể thay đổi; mô hình và adapter cần lưu theo run. Kết quả suite thuộc revisions/source-hash riêng, không mặc định tất cả là một commit. Các lần fail ban đầu giữ nguyên. Parser best-effort, scope module, mẫu UAT2 người và phép đo local đều hạn chế khả năng suy rộng.

## System thực và coverage có kiểm soát ngày10/10

HTTP fullflow và refinement chạy qua ứng dụng/DB/storage thật, provider fixture. DownloadedZIP byte/path được đối chiếu assembledfile; JUnit round1 chỉ boundary:1Pass; round2 existing+boundary+gap:3Pass,0Failure/Error. JaCoCo thật từ các lượt này: branch1/2=50%→2/2=100%, line1/1=100% cả hai. Original đầy đủ existing+boundary đã100%; đây là điều phối gap có kiểm soát khi cố ý chỉ chạy subset round1, không tăng coverage so với fullsuite trước đó. Requirement1/1=100% fixture không phải AIaccuracy. [Evidence](./chapter-5-system-results.md).

Browser thực upload/analyze/BRPlanCaseapprove/Unit/trace đã thấyDOM và screenshot. UI uploadcoverage chưa kích hoạt được filechooser quaCua; UI coverage/refinement/report chưa xác minh, không ghiFail chức năng từ hạn chế công cụ. HumanUAT2 người chưa thực hiện.

Security: thiếu/saiJWT401;cross-ownerread403;USERadmin403;DBdisableJWTcũ403;ZIP traversal400 chỉ smoke. Hai harmlessHTTPentity variants report201 nhưng listener0request, xác minh không fetchNetwork ở hai variant. Chưa có fileentity/sentinel,absolute ZIP/no orphan hoặc tất cả adminUI/mastervariants. Security2 test expected400/actual201 giữ nguyên mismatch fixture vì JaCoCo cho phépDOCTYPE hợp lệ; không kết luận vulnerability từ201.

## Corpus API readiness thực ngày10/10

| Dataset | Files parsed/total | Failed | Classes | Methods | Endpoints | Analyze s | n |
|---|---:|---:|---:|---:|---:|---:|---:|
|PiggyMetrics|72/72|0|74|113|11|5,657|1|
|PetClinic REST|83/83|0|81|293|1|6,175|1|
|RealWorld|93/93|0|96|214|19|5,666|1|
|mall|519/519|0|747|11006|246|176,820|1|
|Shopizer|1167/1167|0|1187|3630|322|98,222|1|

Tổng1934/1934 physical productionfiles parsed, failed0; không dùng làm accuracy100%. Mỗi dự án1lượt, thời gian HTTP gồm parser/persistence/response và có workload cùng kỳ; không gọi CPUtime hoặc benchmark scalability có lặp. PetClinic endpoint1 trên source staged có contract/generated code: không suy extraction đầy đủ khi parse đủfile. [Corpus phạm vi/provenance/raw](./chapter-5-corpus-analysis.md).

Golden regression giữ1Fail:30files parsed,62methods thay vì86, thiếu24trivialaccessors bịproduction chủ động loại; classesassertionPass,endpoint/relationNOT_REACHED. Chờ quyết định reference policy, không tự cập nhật golden. FiveOSS vẫn ready_for_paid_runs=false vì humanoracle và build/replaygate; build preflight hiện có bốn COMPILE_PASS và Realworld Blocked như cập nhật bên dưới. Không gộp ASTreadiness với75AIruns.

## Cập nhật verification/build cuối ngày10/10

Fullbackend mới563invocations:560Pass,3Skip,0Failure/Error;125s. Baseline553Pass/556vẫn giữ mốcOct9, không sửa lịch sử. Golden riêng62/86methods1Fail giữ nguyên; tests opt-in trong fullsuiteSkip không thay kết quả golden này. Python21Pass và ba helper self-checkPass kiểm công cụ thu evidence, không phải21systemTC. Frontend175Pass/build dùng evidenceOct9 khi source vẫn sạch. [Verification/hash audit](D:/.KLTN/outputs/chapter-5/final-validation-20261010/verification.md).

Buildpreflight: PiggyMetrics30,145s(target8),PetClinic52,187s(target17),mall86,135s(target17),Shopizer60,469s(target11), đều runtimeTemurinJDK17.0.19+10 và test-compile exit0. Realworld target11khai báo chưa verify vì đúngGradle7.4 không tải được từ hai nguồnofficial doTLSreset. Đây là wallclockcompile/dependency, không latency GreyTest/AI; chưa chạy upstreamtests hoặc validate generatedJUnit5. Materializedsource/resources tại frozencommits giữinventoryhash saubuild. [Buildpreflight](./chapter-5-build-preflight.md).

System metadata.fixture_sha2562018… trỏ private provider fixture trong Chapter5PipelineIntegrationTest; FixtureServer cóhashB844… riêng trong inventory. Verification overlay làm rõ nhãn, giữmetadata gốc. Hash333sourcefiles không đổi trong verification;69XMLs chọn đãsanitise, không dùng cleancommit giả cho worktree đangdirty.

Chương5 hiện có chiến lược đầy đủ và bảng/số liệu thực một phần. Chưa nghiệm thu toàn kế hoạch: UAT2người, humanoracle/manualtestcomparison, Realworldcompile và generatedtestpilot, ba phương pháp/75runs, UMR/stability và một số UI/security/loadvariants chưa hoàn tất. Không suy rộngaccuracy/scalability từ số liệu có sẵn.
