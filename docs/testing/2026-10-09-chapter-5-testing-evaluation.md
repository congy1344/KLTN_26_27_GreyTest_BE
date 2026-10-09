# Kế hoạch bổ sung kiểm thử và đánh giá cho chương 5 — GreyTest

Ngày lập: 09/10/2026. Trạng thái: **kế hoạch, chưa thực hiện các hạng mục bổ sung**.

## 1. Mục tiêu và phạm vi

Hoàn thiện bằng chứng để viết:

- **5.1:** chiến lược Unit, Integration, System/Functional, kiểm thử quy trình, UAT và đánh giá AI; có người thực hiện, thời điểm, môi trường và dữ liệu.
- **5.2:** kết quả tương ứng với từng kế hoạch ở 5.1, có mã TC, mô tả, đầu vào, kỳ vọng, thực tế và kết luận.
- **5.3:** số liệu về tính đúng đắn, hiệu năng, khả năng mở rộng trong phạm vi đã thử, bảo mật, khả năng sử dụng và hiệu quả AI.

Kiểm thử chính GreyTest khác với loại test GreyTest sinh cho dự án đầu vào. Không bổ sung tính năng sinh Integration/E2E Test, chạy source người dùng trên server, PITest, dashboard thực nghiệm hoặc xuất PDF/Excel vào sản phẩm.

Tận dụng JUnit 5, Mockito, Spring Boot Test/MockMvc, Vitest/React Testing Library, PostgreSQL, chức năng manifest, coverage, traceability và xuất JSON/Markdown hiện có. Thu số liệu bằng file JSON/CSV ngoài ứng dụng; không đổi database schema chỉ để viết báo cáo. CSV là bằng chứng thực nghiệm local, không phải tính năng xuất Excel mới của GreyTest.

## 2. Phase 0 — Khám phá tài liệu và xác nhận hiện trạng

Đã rà soát khi lập kế hoạch:

| Cơ sở | Nội dung cần tuân thủ |
| --- | --- |
| `docs/PROJECT_OVERVIEW.md:72–85` | BR đã duyệt quyết định kỳ vọng; source hỗ trợ kỹ thuật; không sửa oracle để ép test pass |
| `docs/PROJECT_OVERVIEW.md:151–178` | Giới hạn đồ án hai người; thực nghiệm 5–6 dự án; chỉ sinh unit test |
| `docs/THESIS_REQUIREMENTS.md:21–38` | Nghiệm thu ứng dụng, kiểm chứng test local, đối chiếu test thủ công, không bịa kết quả |
| `docs/EVALUATION.md:7–51` | Dataset, 3 phương pháp, 5 lần chạy; 75–90 lượt với 3 cấu hình |
| `docs/EVALUATION.md:53–134` | Các chỉ số và giới hạn diễn giải |
| `docs/EVALUATION.md:136–146` | Bảng số liệu hiện có là kỳ vọng |
| `docs/EVALUATION.md:205–232` | So sánh thủ công, bằng chứng và kiểm chứng oracle nhỏ |
| `README.md:18–45`, `backend/docker-compose.yml` | Backend/frontend là hai Git repo; local PostgreSQL host port 5433 |
| `backend/pom.xml`, `frontend/package.json` | Bộ công cụ kiểm thử thực tế đã cài |
| `outputs/approved-br-oracle-2026-10-08/evidence.md` | Có thử nghiệm AI thật trên fixture; không phải bằng chứng toàn hệ thống |

Hiện trạng quan sát: 68 file test Java backend, 46 file test frontend. 64 XML Surefire đang lưu chứa 547 lượt test, 14 failures, 2 errors và 2 skipped, được cập nhật ở các thời điểm khác nhau. Đây **không phải kết quả chạy mới trên một commit cố định**.

Không dùng `plan/PLAN.md` cũ làm specification duy nhất: tài liệu còn mô tả token HMAC v1, các bước đã triển khai là “Next”, và BR review sinh thêm rule; đối chiếu code và tài liệu phạm vi mới trước khi thực hiện.

### API/công cụ được phép tái sử dụng

- `mvn test` trong `backend`; `npm test` và `npm run build` trong `frontend`.
- API phân tích/manifest theo `docs/WORKFLOW.md`, xác minh lại controller hiện tại trước khi viết request.
- API sinh test trả `202 Accepted` và `GenerationJobAcceptedDto`; kết quả nhận job không đồng nghĩa sinh thành công. Theo dõi `GET /api/projects/{projectId}/generation-progress/{stage}` tới trạng thái kết thúc rồi đọc artifact.
- DTO accepted hiện có `stage/status/message`, không có job ID. Stage: `BUSINESS_RULE`, `TEST_PLAN`, `TEST_CASE`, `UNIT_TEST`; status: `IDLE/QUEUED/RUNNING/COMPLETED/FAILED/PAUSED`. Không viết assertion hoặc collector dựa trên trường chưa tồn tại.
- API Unit Test hiện có `/unit-tests`, `/unit-tests/files`, `/unit-tests/download`, `/unit-tests/generate` trong `UnitTestController.java`; không dùng endpoint `/export` cũ trong kế hoạch trước.
- Dùng parser JaCoCo, traceability và export hiện tại; không giả định có evaluation endpoint chỉ vì đã có entity `ExperimentMetric`.
- Coverage upload: `POST /api/projects/{projectId}/coverage/upload`, multipart `file`, optional `servicePath`, trả 201; GET latest trả 204 khi chưa có report. Traceability: `GET /api/projects/{projectId}/traceability`, optional `servicePath`.

Pattern đã xác minh qua khám phá read-only:

| Nguồn | Tái sử dụng và giới hạn |
| --- | --- |
| `DataLayerIntegrationTest.java:29–38` | `@SpringBootTest`, Postgres/Flyway/JSONB thật; chưa có HTTP |
| `UsageQuotaTransactionIntegrationTest.java:48` | Service/transaction/DB thật |
| `TestCaseGenerationConcurrencyIntegrationTest.java:38` | Setup commit và cleanup cho concurrency; đang mock AIAgentService |
| `ProjectControllerTest.java:32–47` | Multipart/auth/assertion với WebMvcTest; mock service nên không phải toàn chuỗi |
| `GenerationJobAcceptedDto.java:4`, `GenerationProgressController.java:45` | DTO/stage và polling đúng contract |
| `MockLlmClient.java:262–284` | Unit Test mock có package cố định, method rỗng; không đủ chứng minh oracle |
| `LlmClient.java:3–11` | `complete(String)` và `complete(String,LlmRequestOptions)` cho provider fixture/harness |
| `PetClinicGoldenManifestTest.java:28` | Cần `-Dpetclinic.path=<checkout>`; chỉ kiểm tra tập class/method/endpoint, không đủ mọi relation |
| `ApprovedBusinessRuleOracleAcceptanceTest.java:88–104` | Replay/live opt-in; replay đọc working output trong target, cần phục hồi từ evidence nếu đã clean |

Chưa tìm thấy `@AutoConfigureMockMvc`, RANDOM_PORT hoặc HTTP client chạy cùng service/DB thật trong bộ test hiện tại. Bước bổ sung dùng `@SpringBootTest` + `@AutoConfigureMockMvc` theo công cụ đã cài; xác minh compile khi triển khai.

## 3. Tổ chức, lịch thực hiện và sản phẩm bàn giao

Phân công đề xuất, có thể đổi giữa hai thành viên:

- **SV-A:** baseline backend, integration, bộ thu số liệu và thực nghiệm AI.
- **SV-B:** danh mục TC, system test qua UI, UAT, tổng hợp bảng/biểu đồ.
- **Cả hai:** chốt ground truth, review chéo kết quả, phân loại lỗi và duyệt nội dung chương 5.
- **Codex:** hỗ trợ test/script/tài liệu; mọi thay đổi code phải có reviewer read-only theo `AGENTS.md`. Không coi Codex là người dùng tham gia UAT.

Ước lượng sơ bộ: phần kiểm thử ứng dụng 5–7 ngày làm việc; chuẩn bị/chạy/phân tích thực nghiệm AI thêm 5–10 ngày, phụ thuộc dataset, HITL, model và build của dự án. Đây không phải cam kết hoàn tất toàn bộ trong một tuần. Sau pilot phải cập nhật lịch và chi phí. Không tự giảm số dự án hoặc số lần chạy để khớp lịch.

| Phase | Thời lượng dự kiến | Người chính | Phụ thuộc |
| --- | --- | --- | --- |
| 0. Khám phá tài liệu | Đã thực hiện bước đầu | Codex + cả hai | — |
| 1. Baseline và môi trường tái lập | 0,5–1 ngày, chưa gồm sửa lỗi lớn | SV-A | 0 |
| 2. Kế hoạch, dữ liệu và danh mục TC | 0,5–1 ngày | SV-B + SV-A | 0; song song 1 |
| 3. Unit/Integration bổ sung có trọng tâm | 1–2 ngày | SV-A | 1, 2 |
| 4. System, quy trình và UAT | 1–2 ngày | SV-B + người tham gia | 3 |
| 5. Pilot và thực nghiệm AI | 5–10 ngày | Cả hai | 1, 2; pilot có thể song song 3–4 |
| 6. Hiệu năng, bảo mật và quy mô | 0,5–1 ngày | SV-A + SV-B | Bản ổn định sau 3 |
| 7. Tổng hợp và xác minh chương 5 | 0,5–1 ngày | Cả hai | 4, 5, 6 |

Đường dẫn đầu ra dự kiến, **chưa có kết quả tại các đường dẫn này**:

```text
docs/testing/chapter-5-test-strategy.md
docs/testing/chapter-5-test-cases.csv
docs/testing/chapter-5-uat.md
docs/testing/chapter-5-results.md
docs/testing/chapter-5-evaluation.md
outputs/chapter-5/<run-id>/environment.json
outputs/chapter-5/<run-id>/test-results.csv
outputs/chapter-5/<run-id>/defects.csv
outputs/chapter-5/<run-id>/raw/...
outputs/chapter-5/<run-id>/figures/...
scripts/evaluation/    # Chỉ tạo script thực sự cần dùng khi triển khai
```

## 4. Phase 1 — Chốt baseline, môi trường và xử lý test đang lỗi

**Thực hiện**

1. Ghi commit riêng cho BE/FE, dirty diff nếu có, ngày chạy, OS, CPU/RAM, JDK, Maven, Node/npm, PostgreSQL, browser và cấu hình model không chứa secrets. Chọn Node tương thích dependency hiện tại: jsdom 29.1.1 yêu cầu `^20.19.0 || ^22.13.0 || >=24.0.0`; không mặc định Node 18 theo README cũ.
2. Dùng database, storage và tài khoản riêng cho kiểm thử. Với test DB cần commit để kiểm tra race/transaction, cleanup theo ID dữ liệu tạo bởi lần chạy; không xóa dữ liệu demo hoặc dùng DB production.
3. Chạy `mvn test`, `npm test`, `npm run build`; giữ log, Surefire XML và kết quả frontend ngoài `target` để không mất khi clean. Không ghi đè bằng chứng cũ.
4. Bật dữ liệu/path cần thiết cho test có điều kiện, đặc biệt golden manifest. Ghi lý do nếu skip; skip không được tính là Pass.
5. Phân loại từng failure/error: môi trường, fixture/mock lệch contract, lỗi implementation. Tập trung trước vào các suite đang có lỗi lưu: BusinessRuleService, TestCaseService, TestPlanService, ExportService, AIAgentServiceJavaVersion.
6. Với lỗi cần sửa: đề xuất hai hướng nếu có thay đổi lớn/nghiệp vụ và chờ xác nhận theo AGENTS; sửa nhỏ đúng specification thì thêm regression phù hợp, chạy lại phần bị ảnh hưởng và reviewer. Không đổi expected result chỉ để test xanh.

**Tham chiếu:** README, pom/package, các test hiện có và báo cáo Surefire; dùng CodeGraph để đọc đường gọi trước khi sửa.

**Xác minh:** có một baseline đồng nhất; tất cả test trong phạm vi nghiệm thu đã chạy hoặc có trạng thái Blocked rõ ràng; danh sách lỗi gồm nguyên nhân, ảnh hưởng, người xử lý và bằng chứng retest.

**Rào chắn:** không tuyên bố 547 test là tổng hiện tại; không bỏ test lỗi khỏi mẫu số hoặc lẫn báo cáo nhiều commit.

## 5. Phase 2 — Kế hoạch, dữ liệu và danh mục test case

**Thực hiện**

Sao chép cấu trúc input/kỳ vọng từ test và DTO thật; mỗi TC có preconditions, thao tác, người thực hiện, thời điểm/phase và mã yêu cầu để liên kết từ 5.1 sang 5.2. Ưu tiên bảng nhỏ đủ happy/boundary/error/permission, không viết lại test cho getter hoặc sao chép nguyên danh sách hàng trăm method vào báo cáo.

Chuẩn bị và đóng băng:

- Hai user độc lập, một admin, một tài khoản bị vô hiệu hóa; dữ liệu do test tạo riêng.
- ZIP fixture Spring Boot hợp lệ; fixture có existing tests; ZIP lỗi/không có build file; fixture nhiều module; production file không parse được.
- Manifest chuẩn đã review độc lập; BR/Case chuẩn có happy path, boundary, exception và side effect cần thiết.
- JaCoCo XML tạo từ fixture local với số counter biết trước; XML sai định dạng và input XML kiểm tra parser không đọc tài nguyên ngoài.
- Hai implementation chuyển tiền cùng API (`>=` đúng, `>` sai) tái sử dụng kiểm chứng oracle đã lưu.

`source-spring/springboots4test1*` và commerce benchmark dùng cho kiểm thử/pilot. Ground truth tên file/expected count cần được kiểm tra; các biến thể synthetic không tính thành nhiều dự án mã nguồn mở độc lập cho thực nghiệm chính.

### Danh mục tối thiểu đề xuất

| Nhóm/mã | Kịch bản cần có | Dữ liệu/kỳ vọng chính |
| --- | --- | --- |
| UT-01…06 | Parser/extractor, requirement coverage, JaCoCo counter, traceability, giữ oracle, guard trạng thái | Tận dụng test hiện có; chỉ bổ sung khoảng trống có nguy cơ lỗi |
| IT-01 | HTTP upload → lưu project/source → analyze → manifest/DB | ZIP hợp lệ; dữ liệu và owner đúng, không lẫn test vào production counters |
| IT-02 | BR → approve → Plan → approve → Case → approve → Unit Test | DB thật, provider mock; artifact/status/link được lưu đúng |
| IT-03 | Upload JaCoCo → coverage → traceability → JSON/Markdown | Counter khớp fixture, export phản ánh artifact đã lưu |
| IT-04 | Truy cập project của user khác bằng API | Không lộ/sửa artifact; dữ liệu trước/sau không đổi |
| IT-05 | Provider lỗi và yêu cầu sinh trùng lúc | Job báo lỗi/xung đột đúng contract; persistence đúng policy hiện hành, không hỏng quan hệ |
| SYS-IMP-01…04 | ZIP hợp lệ; ZIP lỗi; GitHub public hợp lệ; source nhiều module/parse một phần | UI và API hiển thị đúng source, scope, kết quả hoặc lỗi |
| SYS-GEN-01…06 | BR user nhập; AI sinh BR; AI review; HITL từng bước; sinh Unit Test; thiếu approval | Đúng BR/Case đã duyệt, đủ dữ liệu, không bỏ qua bước duyệt |
| SYS-COV-01…04 | XML hợp lệ; XML lỗi; lịch sử coverage/refinement; export | Coverage và report khớp dữ liệu thực, gap đúng phạm vi |
| FLOW-01 | Sinh mới từ upload đến xuất báo cáo | Chuỗi đầy đủ, xem mục Phase 4 |
| FLOW-02 | Cải thiện existing tests và vòng coverage tiếp theo | Không sửa source đầu vào; giữ artifact trước đó theo contract |
| SEC-01…06 | Thiếu/sai/hết hạn token; cross-owner; USER gọi admin; disabled user; ZIP traversal; XML external entity | Từ chối đúng lớp, không truy cập file ngoài hoặc dữ liệu trái quyền |
| UAT-01…04 | Nhập source; review/duyệt; lấy test về; đọc coverage/truy vết/report | Người tham gia tự hoàn thành và hiểu các trạng thái chính |

**Xác minh:** mỗi yêu cầu chương 5 ánh xạ ít nhất một nhóm TC/metric; kỳ vọng được chốt trước chạy; catalog chưa điền Pass/Fail hoặc số liệu thực tế.

**Rào chắn:** không lấy output AI làm ground truth; với sai số parser cần mô tả hỗ trợ best-effort theo PROJECT_OVERVIEW.

## 6. Phase 3 — Hoàn thiện Unit và Integration Test có trọng tâm

**Thực hiện**

- Tái sử dụng pattern JUnit/Mockito của service test; bổ sung test khi Phase 1/2 chỉ ra đường lỗi quan trọng chưa được kiểm tra.
- Tái sử dụng `@SpringBootTest`/PostgreSQL thật từ `DataLayerIntegrationTest`; thêm MockMvc cho HTTP đi qua service/repository thật. Mock tại ranh giới LLM/external GitHub, không mock service/repository của flow đang chứng minh.
- Chuẩn bị fixture response LLM hợp lệ theo parser và context thật, có SUT/assertion thực; override `LlmClient` trong test config và giữ AIAgentService/AgentGateway/parser thật. Không dựa vào built-in mock Unit Test rỗng để chứng minh compile hoặc oracle; chưa kết luận validator sẽ từ chối mock khi chưa chạy.
- Xử lý generation bất đồng bộ: assert 202/stage/QUEUED, poll có timeout tới trạng thái kết thúc, sau đó kiểm tra DB/artifact. Không `sleep` cố định rồi mặc định thành công. Không bọc toàn test async bằng transaction rollback: worker khác thread cần fixture đã commit; cleanup sau job kết thúc.
- Mọi ghi nhận rollback/partial batch phải khớp contract đã xác minh; không đặt yêu cầu atomic toàn pipeline nếu code/specification cho phép lưu batch hoàn thành.
- Bổ sung assertion không mất dữ liệu khi từ chối vì thiếu approval, không lẫn project/scope, có liên kết truy vết và không đổi kỳ vọng BR.

**Tham chiếu:** `DataLayerIntegrationTest.java`, các `*ConcurrencyIntegrationTest.java`, `GenerationProgressControllerTest.java`, `AgentGatewayTest.java`, `GenerationContextBuilderTest.java`, validator test và controller thực tế.

**Xác minh:** IT-01…05 tái lập được trên DB kiểm thử; failure path có assertion dữ liệu; bộ test mới không gọi provider thật; test phù hợp và reviewer read-only hoàn tất trước báo xong.

**Rào chắn:** không coi MockMvc + mock service là integration toàn chuỗi; không thêm framework E2E hoặc abstraction test nếu pattern hiện có đã đủ.

## 7. Phase 4 — System/Functional, hai quy trình và UAT

**Thực hiện**

Chạy qua giao diện trên backend/frontend/DB thật đã đóng băng. Functional test dùng provider fixture có phản hồi hợp lệ, triển khai bằng config kiểm thử riêng để tái lập và thử lỗi; built-in mock chỉ chứng minh wiring/trạng thái. Thực hiện thêm live smoke toàn chuỗi trên fixture tổng hợp để xác nhận adapter thật. Ghi rõ provider mode từng case, không suy ra chất lượng AI từ mock. Browser manual đủ cho đợt đầu; chỉ thêm automation khi thao tác lặp thực sự cần.

### Hai chức năng chính

1. **Nhập và phân tích source:** ZIP/GitHub public → project → analyze → cây class/method/endpoint, relations, existing tests, failed files và scope. Đối chiếu manifest với ground truth, kiểm tra invalid input và cross-owner.
2. **Sinh kiểm thử có HITL:** BR → Plan → Case → Unit Test; thử thiếu approval, chỉnh Case và duyệt lại, dữ liệu boundary, timeout/error/progress và tải file. Biên dịch/chạy test tải về tại project local; ghi lỗi kỹ thuật riêng với fail nghiệp vụ.

### FLOW-01 — Sinh mới

Login → upload source → analyze → nhập/AI sinh BR → review/approve BR → sinh/review/approve Plan → sinh/review/approve Case → sinh/download Unit Test → chạy local/JaCoCo → upload XML → kiểm tra coverage/traceability → xuất JSON/Markdown.

Ghi kết quả tại từng bước; kiểm tra dữ liệu sau reload để chứng minh persistence. Thời gian chờ AI và thời gian HITL tách riêng.

### FLOW-02 — Existing tests và bổ sung theo gap

Login → upload source có test → analyze existing context → nhập BR/AI review/approve → sinh và duyệt Plan/Case → sinh test có generation type phù hợp → merge vào bản sao project local → chạy JaCoCo → upload XML vòng 1 → chọn gap refinable có BR hợp lệ → bổ sung test → chạy local/upload vòng 2 → đối chiếu coverage và artifact cũ.

Không bắt buộc coverage tăng trong mọi trường hợp; phải xác minh gap được xử lý hoặc ghi lý do chưa cải thiện. Không sửa input/assertion để ép coverage tăng.

### UAT

- Đề xuất 2–3 người có nền tảng Java/tester; ưu tiên có ít nhất một người không trực tiếp triển khai. Đây là kế hoạch tuyển người, không phải số người đã tham gia.
- SV-B cung cấp nhiệm vụ và dữ liệu, quan sát; người tham gia thực hiện UAT-01…04, ghi khó khăn, thời gian và hỗ trợ nhận được.
- Chốt tiêu chí trước buổi: hoàn thành các nhiệm vụ cốt lõi, nhận biết bước cần duyệt, tìm được output/lỗi; ghi hoàn thành có/không trợ giúp. Phiếu nhận xét ngắn 1–5 và câu hỏi khó khăn chính, không suy rộng từ mẫu nhỏ.
- Nếu chưa có người tham gia, giữ UAT ở trạng thái chưa thực hiện; không thay bằng kết quả unit test hoặc tự nhận Codex đã nghiệm thu.

**Tham chiếu:** `docs/WORKFLOW.md`, `docs/THESIS_REQUIREMENTS.md:21–38`, các panel/page và controller thực tế.

**Xác minh:** ít nhất hai chức năng, hai flow có kết quả và evidence; UAT có người/ngày/môi trường thật; screenshot/log liên kết về mã TC.

**Rào chắn:** không khẳng định System Pass khi chỉ test từng component; dùng project test riêng, không thao tác source gốc.

## 8. Phase 5 — Pilot, thực nghiệm AI và đối chiếu test thủ công

**Thực hiện**

### 5A. Pilot trước khi mở rộng

1. Chọn một fixture nhỏ có requirement độc lập; chạy end-to-end, compile/test/JaCoCo, kiểm tra toàn bộ cách thu số liệu.
2. Viết runner local tối thiểu cho hai baseline nếu chưa có: **LLM-only** chỉ source, không BR/static analysis; **Prompt-based** source + BR trong một prompt, không HITL/pipeline. GreyTest chạy pipeline hiện tại. Enum `MethodUsed` không phải runner.
3. Chốt output baseline cần sinh, cách mapping kết quả về BR chuẩn để so sánh, phạm vi source chung và giới hạn context. Mapping đánh giá sau chạy không được biến thành context BR cho LLM-only.
4. Chốt một cấu hình GreyTest chính cho bảng 3 phương pháp; kiểm chứng cả hai mode BR bằng testcase/định tính. Nếu cần so sánh hai mode như hai cấu hình độc lập, công bố 4 cấu hình và lịch 100–120 lượt, không gộp kết quả.
5. Ghi prompt/model/version/options, input/output, retry, lỗi, thời gian và token; ước lượng chi phí/lịch từ pilot trước khi mở rộng. Không tự gọi provider hoặc thực hiện toàn bộ batch trả phí chỉ từ yêu cầu lập kế hoạch.

### 5B. Dataset và các lần chạy

- Chốt 5–6 dự án Spring Boot mã nguồn mở nhỏ/vừa/lớn; ghi URL, license, commit, build recipe, LOC/classes/methods/endpoints và phạm vi service được chọn.
- Ground truth BR và bộ test thủ công trên ít nhất một dự án được review độc lập trước xem output AI; lưu cả người review và xử lý bất đồng.
- Với mỗi project/method chạy 5 lần: **75–90 lượt**, không phải 75–90 LLM calls. Mỗi lượt pipeline có thể có nhiều call/batch/retry.
- Cùng phạm vi và model/options, project/data mới mỗi lượt, kiểm soát cache/reuse để không biến 5 lượt thành đọc lại cùng output. Ghi khác biệt input có chủ đích giữa baseline và GreyTest.
- Dự án lớn chỉ đánh giá module đã công bố; không gọi số liệu trên một service là coverage toàn repository.
- Lưu raw trước HITL và artifact sau HITL; phân tích hai giai đoạn riêng. Baseline không có HITL chính thức; mọi sửa kỹ thuật để compile phải lưu riêng, không tính đầu ra đã sửa là chất lượng raw.

### 5C. Thu metric và giới hạn diễn giải

| Metric | Cách thu/đối chiếu |
| --- | --- |
| Requirement Coverage | BR chuẩn có Case tương ứng / BR chuẩn trong scope; dùng denominator chung cho so sánh. Coverage UI theo BR đã duyệt báo riêng nếu tập BR khác ground truth |
| Line/Branch Coverage | Compile/chạy local; lấy XML JaCoCo cùng production scope; đo trước/sau test mới nếu có existing tests |
| Generation time | Đồng hồ wall-clock từng stage và tổng thời gian máy xử lý; tách HITL, compilation/JaCoCo; không cộng latency các call song song thay cho thời gian thực |
| UMR | Chụp danh sách raw Case và lịch sử chỉnh trước sinh Unit Test; đếm Case AI bị sửa / Case AI sinh; thêm/xóa/reject báo riêng |
| Token usage | Calls log gồm retry/failure có usage; thiếu usage ghi N/A, không ghi 0; chi phí tiền chỉ tính khi có đơn giá thật của provider |
| Stability | Theo EVALUATION: cùng test type và description similarity >0,85; chốt chuẩn hóa văn bản, thuật toán và matching một-một/formula trước pilot, lưu version; không thay ngưỡng sau xem kết quả |
| Traceability | Kiểm tra chuỗi BR → Plan → Case → Unit Test đầy đủ; không coi chuỗi hợp lệ là bằng chứng assertion đúng |
| Độ đúng/hiệu quả AI | Review output với yêu cầu độc lập; đếm đúng/sai/thiếu/trùng theo rubric đã chốt; báo compile failure, setup/mock failure, business failure và số lượt thành công riêng |

Lưu ý code hiện tại:

- `LlmUsageRecorder` ghi `calls.jsonl`/`summary.json` khi bật `LLM_USAGE_ENABLED`; cấu hình `LLM_USAGE_OUTPUT_DIR`, `LLM_USAGE_RUN_ID`, `LLM_USAGE_METHOD`. Tách backend process/run directory để singleton không cộng dồn các lượt khác nhau. Log không thay cho đo wall-clock.
- `UnitTestService.java:405` reset `TestCase.isModified` sau generation. Không tính UMR từ DB cuối pipeline; dùng snapshot/diff trước bước này. Không cần đổi business logic để đo.
- `ExperimentMetric` hiện mới có entity/repository; ưu tiên collector local ra CSV/JSON, chưa thêm endpoint/migration/dashboard.
- Requirement Coverage UI tính theo BR approved có Plan gắn ít nhất một Case; không yêu cầu Case approved hoặc Unit Test. Khi không có BR, code trả 100%; collector ghi mẫu số 0/N/A, không diễn giải thành bao phủ hoàn hảo. `TraceabilityMatrix.uncovered` chỉ phát hiện thiếu Plan, không thay cho kiểm tra đủ chuỗi khi tính Traceability Score.
- Tái sử dụng oracle evidence 08/10 như ví dụ định tính; không tính thành thực nghiệm 5–6 dự án hoặc “AI chính xác 100%”.

**Tham chiếu:** EVALUATION, THESIS_REQUIREMENTS, `LlmUsageRecorder`, `TestCaseService`, `UnitTestService`, `GenerationContextBuilder`, oracle acceptance test.

**Xác minh:** pilot tái lập; bộ dữ liệu đủ provenance; mỗi lượt có input/config/raw/result/evidence; giữ lượt thất bại trong bảng; báo số lượt thực hiện/thiếu; bảng trung bình và độ phân tán cùng n, không dùng số kỳ vọng.

**Rào chắn:** không dùng synthetic variants thay dataset OSS; không gửi secrets; không sửa BR/assertion theo code sai; không tự chốt thuật toán stability hoặc rubric sau khi biết kết quả.

## 9. Phase 6 — Hiệu năng, quy mô, bảo mật và khả năng sử dụng

**Thực hiện**

- Đo trên cấu hình máy ghi trong evidence; chốt workload và tiêu chí trước chạy. Các mức dưới là cấu hình thử đề xuất, không phải kết quả/cam kết production.
- API đọc project/analysis/traceability: concurrency **1, 3, 5, 10**, warm-up rồi ít nhất 100 request mỗi mức; lưu elapsed/error để tính median, p95, throughput và error rate. Giữ payload/data cố định, đo client trên cùng máy thì nêu giới hạn.
- Phân tích source: fixture nhỏ/vừa/lớn cùng điều kiện, ít nhất 3 lượt mỗi kích thước; thời gian, memory nếu đo được, lượng class/method, failed parse và độ đúng manifest. Synthetic dataset dùng riêng cho scaling, không thay corpus AI.
- Generation: mock-provider cho tải job/state/DB và contention; đo live-provider ở pilot/thực nghiệm AI riêng. Không gọi đồng loạt provider thật để thử tải web. Thời gian nhận 202 và thời gian hoàn thành job là hai số đo khác nhau.
- Bảo mật: chạy SEC-01…06 ở UI/API, kiểm tra dữ liệu không đổi khi bị từ chối; dùng input ZIP/XML tổng hợp và tài khoản test.
- Khả năng sử dụng: dùng dữ liệu UAT Phase 4; báo người dùng, tỷ lệ nhiệm vụ hoàn thành, thời gian, hỗ trợ và lỗi hiểu trạng thái.
- Ngưỡng nghiệm thu hiệu năng phải được chốt từ mục tiêu demo trước khi đo; nếu chưa có SLA, báo kết quả/điểm bắt đầu suy giảm thay vì tự đặt ngưỡng để tuyên bố đạt.

**Tham chiếu:** controller auth/admin/project, storage/parser tests, quota/concurrency integration tests, Phase 4 UAT.

**Xác minh:** có raw data tái tính được, biểu đồ tải–latency/error và quy mô–analysis time; mỗi nhận xét có scope/môi trường cụ thể.

**Rào chắn:** không suy ra horizontal scaling từ test một máy; không coi batch concurrency là kết quả chịu tải; không tuyên bố kiểm toán bảo mật toàn diện từ vài TC.

## 10. Phase 7 — Bảng kết quả, biểu đồ và xác minh cuối

**Thực hiện**

Lập bảng kết quả theo từng nhóm kế hoạch, không lẫn test GreyTest với test sinh cho sample project:

```text
test_case_id, requirement_id, level, description, preconditions,
steps, input_data, expected_result, actual_result, status,
executed_by, executed_at, run_id, be_commit, fe_commit,
provider_mode, evidence_path, defect_id
```

- Bảng trình bày trong 5.2 giữ ít nhất sáu cột yêu cầu của môn; các thông tin bổ sung để phụ lục/evidence.
- Trước chạy: `Not Run`; lỗi môi trường/phụ thuộc: `Blocked`; chỉ sau đối chiếu thực tế mới `Pass`/`Fail`. Summary tách cả bốn trạng thái và skipped.
- Với generated test cố ý phát hiện source sai: ghi test local Fail đúng nghiệp vụ; TC đánh giá khả năng phát hiện lỗi của GreyTest có thể Pass nếu kỳ vọng chính là phát hiện lỗi đó. Không đánh đồng hai kết luận.
- Ghi defect/retest và lý do chưa sửa; không xóa lần Fail trước khi retest.
- Biểu đồ tối thiểu: coverage theo phương pháp; thời gian sinh/token; latency/error theo tải; nếu đủ dữ liệu thì UMR/stability. Biểu đồ chỉ lấy từ CSV/JSON thật.
- Mỗi hình/bảng có n, scope, cách tính, môi trường và phạm vi trước/sau HITL; tỷ lệ có tử/mẫu, N/A và failure không bị loại âm thầm.
- Viết phần hạn chế: dataset chọn module, mẫu UAT nhỏ, provider thay đổi, parser best-effort, chênh lệch context, sai oracle, cache, số lượt thiếu và lỗi kỹ thuật.

**Tham chiếu:** catalog Phase 2, output Phase 1/3/4/5/6, EVALUATION và yêu cầu chương 5 người dùng cung cấp.

**Xác minh cuối**

- [ ] 5.1 có đủ Unit/Integration/System/quy trình/UAT/AI, người thực hiện, dữ liệu và thời điểm.
- [ ] 5.2 có kết quả tương ứng từng kế hoạch, actual result và evidence; không điền Pass trước chạy.
- [ ] Ít nhất hai chức năng chính và hai quy trình đã chạy toàn chuỗi.
- [ ] 5.3 mỗi tiêu chí có số liệu thật hoặc ghi rõ chưa đánh giá/giới hạn.
- [ ] Thực nghiệm đủ phạm vi hiện hành; nếu chưa đủ, công bố thiếu và chưa kết luận tổng quát.
- [ ] Có đối chiếu với test thủ công trên ít nhất một dự án.
- [ ] Baseline/retest tương ứng cùng revision; toàn bộ thay đổi code đã được kiểm tra và reviewer.
- [ ] Evidence ngoài `target`, không chứa token/API key/password/Authorization header.
- [ ] Mọi bảng/biểu đồ truy ngược được về raw data; không dùng bảng kỳ vọng trong EVALUATION.

## 11. Thứ tự bắt đầu sau khi duyệt kế hoạch

1. Phase 1: tạo baseline mới và phân loại test lỗi; Phase 2 lập catalog song song.
2. Hoàn tất ba integration flow chính và hai system flow trước khi viết kết quả 5.2.
3. Chạy một pilot AI, chốt corpus/model/rubric/chi phí rồi mới mở rộng thực nghiệm.
4. Thu hiệu năng/UAT và viết bảng kết quả cuối từ evidence.

Kế hoạch này chưa triển khai code, chưa chạy lại test, chưa tuyển người UAT và chưa gọi thêm provider thật.
