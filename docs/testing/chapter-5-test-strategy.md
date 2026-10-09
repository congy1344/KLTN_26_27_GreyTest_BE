# Chiến lược kiểm thử GreyTest — chương 5

Ngày chốt kế hoạch: 09/10/2026. Đây là kế hoạch thực thi, **chưa phải kết quả**. Danh mục ở [chapter-5-test-cases.csv](chapter-5-test-cases.csv); chỉ cập nhật kết quả vào bản sao theo từng run. Nguồn kế hoạch: [kế hoạch triển khai](2026-10-09-chapter-5-testing-evaluation.md). Không dùng bảng kỳ vọng trong EVALUATION làm số liệu.

## Phạm vi và ánh xạ yêu cầu 5.1

Kiểm thử chính GreyTest gồm backend, frontend, PostgreSQL, storage và adapter AI. Unit Test GreyTest sinh cho dự án đầu vào là sản phẩm được kiểm chứng riêng bằng biên dịch/chạy local. Không bổ sung tính năng server chạy source người dùng, sinh Integration/E2E hoặc dashboard thực nghiệm.

| Mã yêu cầu | Nội dung | Danh mục / bằng chứng cần thu |
| --- | --- | --- |
| R-UNIT | Kiểm thử hàm/module riêng | UT-01…06; JUnit/Mockito, Vitest/RTL; log và XML baseline |
| R-INTEGRATION | Tích hợp module | IT-01…05; HTTP → service → PostgreSQL thật, storage thật; fixture ở ranh giới LLM |
| R-IMPORT | Chức năng chính 1: nhập và phân tích source | SYS-IMP-01…04; màn hình, request/response, manifest độc lập |
| R-GENERATION | Chức năng chính 2: sinh kiểm thử có HITL | SYS-GEN-01…06; artifact trước/sau approve, tiến độ, ZIP và kết quả chạy local |
| R-COVERAGE | Coverage, refinement và export | SYS-COV-01…04; XML local, report/history, output JSON/Markdown |
| R-FLOW | Hai quy trình nghiệp vụ | FLOW-01…02; mỗi bước có evidence, reload xác nhận persistence |
| R-UAT | Nghiệm thu bởi người dùng | UAT-01…04 × UAT-P01/P02; phiếu nhiệm vụ và nhận xét thật |
| R-AI | Hiệu quả/chính xác AI | AI-01…03; oracle độc lập, pilot và 5–6 dự án × 3 phương pháp × 5 lần |
| R-PERFORMANCE | Thời gian phản hồi/chịu tải | PERF-01; median, p95, throughput, error rate từ raw data |
| R-SCALE | Quy mô source/job trong phạm vi thử | PERF-02…03; thời gian phân tích theo kích thước, concurrency fixture |
| R-SECURITY | Phân quyền/file upload/XML | SEC-01…06; response và kiểm tra không có tác động trái quyền |
| R-USABILITY | Khả năng sử dụng | UAT-01…04; tỷ lệ hoàn thành, trợ giúp và thời gian, giới hạn mẫu 2 người |

## Tổ chức và thời điểm

SV-A phụ trách Unit/Integration, bảo mật, đo đạc và chuẩn bị dữ liệu. SV-B phụ trách System/Flow, tổ chức UAT và tổng hợp kết quả. Hai người review chéo ground truth và bất đồng. Đây là phân vai đề xuất, ghi tên thực tế khi chạy.

Phase 1 chạy baseline và ghi môi trường. Phase 2 đóng băng dữ liệu/kỳ vọng. Phase 3 chạy UT/IT sau baseline hợp lệ. Phase 4 chạy SYS/FLOW trên hệ thống thật, rồi tổ chức UAT ngay sau khi hai flow đạt điều kiện nghiệm thu. Người dùng đã xác nhận **2 người tham gia UAT**; dùng mã P01/P02, chưa có danh tính/ngày/kết quả thực tế. Phase 5 pilot trước batch AI. Phase 6 đo hiệu năng/bảo mật trên revision ổn định. Không ấn định ngày kết thúc thực nghiệm trước khi biết pilot.

## Môi trường và dữ liệu đóng băng

- E-UNIT: JDK/Maven từ backend, Node tương thích package hiện tại, Vitest/RTL; mock DB/service chỉ ở kiểm thử đơn vị. Không tính WebMvcTest mock service thành IT toàn chuỗi.
- E-IT: SpringBootTest + MockMvc, PostgreSQL kiểm thử riêng/Flyway, storage riêng; service/repository thật, chỉ thay LLM và GitHub external bằng fixture. Fixture async phải commit và cleanup theo ID sau job kết thúc.
- E-SYS: browser + frontend/backend/PostgreSQL/storage thật, tài khoản test; provider fixture cho tái lập và provider live cho smoke được ghi riêng. Built-in mock chỉ chứng minh wiring/trạng thái.
- E-AI: model/options/prompt/input frozen, provider thật; local project copy với JDK/build riêng. Không dùng fixture/mock làm chứng cứ chất lượng AI.
- E-PERF: E-SYS ổn định, cấu hình CPU/RAM/OS/DB/browser/network được lưu. Chỉ tải generation với provider fixture.
- User A/B độc lập, Admin T và Disabled D: tạo riêng cho run, không dùng dữ liệu demo; credentials không ghi vào evidence.
- F-ZIP: `source-spring/springboots4test1.zip` là ứng viên source nhỏ; trước chạy kiểm tra nội dung/hashes và lập ground truth class/method/endpoint/relation từ source độc lập, không lấy manifest xuất ra làm kỳ vọng duy nhất.
- F-EXIST: bản sao source có JUnit/Mockito test thật; ghi file/hash và mapping expected, không tự giả định ZIP hiện có chứa test.
- F-MULTI: fixture 2 module trong thư mục run; có hai service cùng tên nhưng khác package/signature và test tách production. F-PARTIAL thêm một Java production không parse được vào bản sao, giữ ít nhất một file hợp lệ.
- F-INVALID: ZIP chứa text và không có build file; ZIP có entry `../chapter5-sentinel.txt`; XML `<not-report/>` và XML có syntax lỗi. Chỉ dùng sentinel do test tạo.
- F-XML: JaCoCo XML của fixture đã chạy local. Đóng băng counter trước upload; khi test parser đơn vị dùng `<report name="chapter5"><counter type="LINE" missed="2" covered="8"/><counter type="BRANCH" missed="1" covered="3"/></report>`: lines 10/8, branches 4/3, tương ứng 80%/75%. XML đơn giản này không có method và không đủ cho refinement; flow phải dùng XML local có package/class/method.
- F-ORACLE: source chuyển tiền không phí, amount dương, balance không âm; `balance >= amount` trả `balance - amount`. Hai bản cùng API dùng >= và >. Amount=100/balance=100 → 0; 99/100 → 1; 101/100 → từ chối theo API fixture. Tái dùng source/evidence trong `outputs/approved-br-oracle-2026-10-08`, lưu hash khi copy. BR/Case ground truth được review độc lập trước xem output AI.
- F-LLM: response JSON hợp lệ cho từng stage, giữ package/API/test có SUT và assertion thật; response timeout/malformed riêng. Khác built-in mock có method rỗng. Ghi provider_mode fixture/mock/live chính xác.
- F-OSS: Phase 5 chốt 5–6 dự án OSS độc lập (URL/license/commit/build/scope). Các synthetic variants không tính thành nhiều dự án OSS. Manual reference trên ít nhất 1 dự án, 3 phương pháp × 5 lần = 75–90 lượt.

Các F-* là specification dữ liệu cần chuẩn bị, không phải xác nhận đã tạo đủ fixture. Mỗi fixture cần manifest file path, SHA-256, author/reviewer, ngày đóng băng và expected counters/identities; chưa có thì TC phụ thuộc giữ Not Run/Blocked khi bắt đầu chạy.

## Contract thao tác phải dùng khi chạy

Tất cả request bảo vệ gửi Authorization Bearer bằng token runtime, không ghi header/token vào log. P là projectId trả về; M là methodId thật lấy từ analysis trong đúng project/scope, không hard-code ID. servicePath lấy từ GET /api/projects/P/services, URL-encode giá trị; không tự chọn scope khác giữa các bước.

| Thao tác | Request theo controller hiện tại |
| --- | --- |
| Login | POST /api/auth/login; JSON `{"email":"<user test>","password":"<runtime>"}` |
| Upload/clone | POST /api/projects/upload multipart `file`; POST /api/projects/github JSON `{"url":"<public repo URL>"}`; thành công 201 |
| Analyze/manifest | POST /api/projects/P/analyze; GET /analysis; GET /analysis/manifest; POST /analysis/manifest/validate với AnalysisManifestInput ground truth |
| BR user | POST /api/projects/P/business-rules?servicePath=S; JSON `{"methodId":M,"description":"<BR chuẩn>","sourceBranchId":null}`; 201 |
| BR generate/review/approve | POST /api/projects/P/business-rules/generate; /business-rules/review; /business-rules/approve; optional servicePath. Review synchronous; không poll như generation |
| Plan | POST /api/projects/P/test-plans/generate?resume=false; POST /test-plans/approve; GET /test-plans; optional servicePath |
| Case | POST /api/projects/P/test-cases/generate?resume=false; optional planId/servicePath. POST /test-cases/approve; GET /test-cases |
| Sửa Case | PUT /api/test-cases/C; JSON `{"testType":"BOUNDARY","description":"Số dư bằng số tiền chuyển","preconditions":"amount dương; balance không âm","testData":{"balance":100,"amount":100},"expectedResult":"Thành công, số dư còn 0","priority":"HIGH","traceSource":"<traceSource hợp lệ đang lưu>"}`; review/approve lại |
| Unit Test | POST /api/projects/P/unit-tests/generate?resume=false; GET /unit-tests; GET /unit-tests/files; GET /unit-tests/download; optional servicePath |
| Coverage | POST /api/projects/P/coverage/upload multipart `file` và optional `servicePath`; 201. GET /coverage (204 khi chưa có); GET /coverage/history; POST /coverage/refine synchronous |
| Trace/export | GET /api/projects/P/traceability?servicePath=S; GET /api/projects/P/export?format=json hoặc markdown; optional servicePath |

Generation nhận **202 với stage/status/message**, không có jobId. Stage BUSINESS_RULE/TEST_PLAN/TEST_CASE/UNIT_TEST. Poll GET /api/projects/P/generation-progress/STAGE mỗi 250 ms tối đa 30 s cho IT fixture; SYS fixture mỗi 1 s tối đa 120 s; live mỗi 2 s tối đa 15 phút là timeout của buổi thử, không phải SLA sản phẩm. Chốt timeout trước chạy trong run metadata. Trạng thái hợp lệ IDLE/QUEUED/RUNNING/COMPLETED/FAILED/PAUSED. Chỉ COMPLETED mới đọc artifact để xác nhận thành công; FAILED phải lưu lỗi; PAUSED ghi dừng và thử resume có chủ đích; IDLE đơn lẻ không tính thành công. Timeout lưu snapshots và ghi Fail nếu vượt tiêu chí đã chốt, Blocked nếu nguyên nhân môi trường được chứng minh. Không sleep cố định rồi mặc định Pass. Gate thiếu approval có thể nhận 202 rồi job FAILED: kiểm tra terminal và dữ liệu, không mặc định HTTP 409 ở submit.

## Hai quy trình hoàn chỉnh

FLOW-01: login → upload ZIP mới → analyze/đối chiếu manifest → nhập hoặc AI sinh BR → review tùy chọn/approve BR → generate Plan/poll/review/approve → generate Case/poll/chỉnh boundary/approve → generate Unit Test/poll/download → compile/test/JaCoCo tại bản sao local → upload XML → kiểm tra coverage/traceability → export JSON/Markdown → reload xác nhận dữ liệu. Ghi thời gian AI và thời gian HITL riêng; local failing assertion đúng oracle là phát hiện lỗi nghiệp vụ, không tự đổi expected.

FLOW-02: login → upload F-EXIST → analyze xác nhận existing context và production counters → nhập/review/approve BR → generate/approve Plan/Case → generate Unit Test với loại NEW_TEST/IMPROVE_EXISTING_TEST/SUPPLEMENT_EXISTING_TEST phù hợp → merge bản sao local → test/JaCoCo/upload vòng 1 → chọn gap refinable có BR approved → POST coverage/refine (nút xác nhận HITL) → kiểm tra Case bổ sung approved và Unit Test bổ sung → download/merge/test/JaCoCo/upload vòng 2 → history/export/traceability → so sánh artifact cũ và source gốc. Không bắt buộc coverage tăng; không đổi oracle hoặc tạo dữ liệu vô nghĩa để đạt nhánh.

## Ghi kết quả và tiêu chí hoàn thành

CSV master giữ Not Run, actual_result rỗng. Copy vào `outputs/chapter-5/<run-id>/test-results.csv`, điền revision BE/FE, environment, provider_mode, người/ngày thực tế, raw evidence và defect/retest. CSV có sáu cột bắt buộc (ID, description, input_data, expected_result, actual_result, status) và các cột audit. Chỉ Pass/Fail sau đối chiếu, Blocked nếu chưa thể chạy; không quy skipped thành Pass. Mỗi scenario có expected chốt trước chạy; biến thể ghi subcase trong log, chỉ Pass nhóm khi mọi biến thể đủ.

System đủ khi ít nhất hai chức năng chính và hai flow chạy qua UI/API/DB thật có bằng chứng. UAT đủ khi hai người thật hoàn thành/không hoàn thành nhiệm vụ được ghi, kể cả trợ giúp. AI đủ phạm vi khi có corpus và đủ lượt hoặc công bố số thiếu; raw và sau HITL tách riêng; giữ failed runs trong mẫu số. R-USABILITY dùng phiếu UAT, R-PERFORMANCE/R-SCALE báo raw median/p95/errors và giới hạn một máy. Không đặt ngưỡng SLA sau khi biết số liệu.

## Nguồn contract đã đọc

Controller Project/Analysis/BusinessRule/TestPlan/TestCase/UnitTest/Coverage/Traceability/Export/GenerationProgress/Auth; DTO CreateBusinessRuleRequest, UpdateTestCaseRequest, LoginRequest, GenerationJobAcceptedDto; GlobalExceptionHandler; JacocoXmlParser; CoverageServiceTest; JavaParserHelperTest, ApprovedBusinessRuleOracleAcceptanceTest và tài liệu docs/{PROJECT_OVERVIEW,ARCHITECTURE,TECH_STACK,WORKFLOW,DATA_MODEL,AI_AGENT,EVALUATION,CODING_RULES}. CodeGraph đã dùng trước tra code; khi index không trả controller dùng file hiện tại để chốt request. Contract nguồn code thắng ví dụ cũ; không giả định mỗi entry của tài liệu là API đã tồn tại.

Evidence oracle 08/10/2026 là bằng chứng lịch sử riêng ở `outputs/approved-br-oracle-2026-10-08/evidence.md`. Không điền lại Pass cho catalog mới từ bằng chứng này; chưa chứng minh browser/HTTP/DB hoặc AI chính xác tổng quát.

