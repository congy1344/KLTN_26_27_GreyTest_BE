# 5.2. Kết quả kiểm thử — Kiểm thử đơn vị và tích hợp

## Phạm vi và môi trường thực hiện

Các bộ kiểm thử tự động được chạy lại ngày **20/10/2026**, múi giờ Asia/Saigon, trên phiên bản backend `498d105` và frontend `6443e07`. Nội dung dưới đây trình bày kết quả kiểm thử đơn vị, thành phần giao diện và tích hợp tương ứng với chiến lược ở mục 5.1.1 và 5.1.2.

Backend sử dụng JUnit, Mockito, Spring Boot Test và MockMvc; frontend sử dụng Vitest và React Testing Library. Môi trường chạy gồm Windows, Temurin JDK 17.0.19, Maven 3.9.9, Node.js 22.13.0 và PostgreSQL 17.6. PostgreSQL kiểm thử chỉ lắng nghe loopback tại cổng 55439. Lượt chạy dùng database mới `chapter5_report_20261020`; riêng bộ pipeline sử dụng database kiểm thử `chapter5_integration` theo cấu hình có sẵn. Database ứng dụng không được sử dụng.

Ở Unit Test, các phụ thuộc cần cô lập được giả lập. Ở 5 test tích hợp pipeline, Controller, Service, Repository, Flyway, PostgreSQL, storage, parser và kiểm tra dữ liệu đầu ra được sử dụng thật; chỉ ranh giới gọi mô hình AI dùng provider fixture. Frontend được kiểm tra ở môi trường component/jsdom, với các API hoặc hook được giả lập theo từng test. Lượt chạy này không gọi mô hình AI bên ngoài.

## Tổng hợp kết quả

| Nhóm thực thi | Tổng lượt | Pass | Fail/Error | Skip |
|---|---:|---:|---:|---:|
| Backend: unit, component/controller và các kiểm tra ngoài 5 lớp IntegrationTest | 552 | 549 | 0 | 3 |
| Backend: 5 lớp IntegrationTest sử dụng PostgreSQL thật | 11 | 11 | 0 | 0 |
| Frontend: 46 tệp kiểm thử unit/component | 175 | 175 | 0 | 0 |
| **Tổng cộng** | **738** | **735** | **0** | **3** |

Hai dòng backend là cách tách **cùng một lần chạy Maven**, không cộng thêm 11 test tích hợp vào 563 lượt backend. Nhóm backend ngoài IntegrationTest có cả kiểm thử lát cắt Controller, kiểm tra khởi tạo context và các kiểm tra fixture offline; không đồng nhất tất cả với Unit Test thuần. Maven kết thúc với `BUILD SUCCESS`, mã thoát 0; Vitest cũng kết thúc với mã thoát 0. Thời gian đo bao quanh lệnh backend khoảng 70,01 giây và frontend khoảng 61,80 giây, chạy đồng thời; đây là thời gian suite, không phải thời gian phản hồi API.

Các bảng sau chọn **47 kịch bản đã thực thi** để trình bày trong báo cáo: 28 backend, 8 frontend và toàn bộ 11 kịch bản thuộc các lớp IntegrationTest. Mỗi hàng có mã riêng và ánh xạ chính xác đến một test method hoặc một assertion result. Các mã `UT-BE-*` và `UT-FE-*` thuộc lượt chạy này, không tự động thay thế trạng thái của các master case rộng hơn trong catalog ngày 09/10.

Kết luận **Pass** được xác định khi kết quả thực tế thỏa toàn bộ assertion của kịch bản. Với dữ liệu không hợp lệ hoặc thao tác trái quyền, việc hệ thống trả đúng exception hoặc mã lỗi mong đợi được tính là Pass.

## 5.2.1. Kết quả kiểm thử đơn vị

### a. Backend

| Mã test case | Mô tả | Dữ liệu đầu vào | Kết quả mong đợi | Kết quả thực tế | Kết luận |
|---|---|---|---|---|---|
| UT-BE-01 | Tách production, existing test và mã sinh tự động | App.java trong src/main; AppTest.java trong src/test; Generated.java trong target. | Chỉ phân tích 1 file production; đếm riêng 1 existing test; bỏ target. | Có đúng 1 production file App.java, 1 existing test và 0 lỗi parse production. | Pass |
| UT-BE-02 | Phân tích khi có file Java lỗi | Valid.java hợp lệ và Broken.java thiếu dấu đóng. | Phân tích Valid.java; liệt kê Broken.java trong failedParseFiles. | Nhận đúng 1 file hợp lệ; tổng production 2; ghi nhận đúng Broken.java lỗi. | Pass |
| UT-BE-03 | Phân biệt phương thức overload | find(Long) và find(String), có mapping riêng. | Hai method key và endpoint tham chiếu đúng từng overload. | Trích xuất đúng find(Long), find(String) và hai endpoint có method key tương ứng. | Pass |
| UT-BE-04 | Loại accessor đơn giản, giữ logic nghiệp vụ | getName, setName, getNameEndpoint có mapping và getDisplayName dùng trim. | Bỏ getName/setName; giữ endpoint và phương thức xử lý dữ liệu. | Chỉ giữ getNameEndpoint() và getDisplayName(). | Pass |
| UT-BE-05 | Đọc source có cú pháp Java 21 | PatternSwitchSample dùng switch pattern cho String/Integer. | JavaParser phân tích 1 file và nhận diện PatternSwitchSample. | Nhận đúng 1 file và lớp PatternSwitchSample; assertion đạt. | Pass |
| UT-BE-06 | Tính Requirement Coverage | 2 BR approved; chỉ BR-1 được plan có Test Case bao phủ. | Requirement Coverage bằng 50,00%. | Hàm trả về 50,00%. | Pass |
| UT-BE-07 | Xử lý khi không có BR approved | Danh sách BR approved rỗng. | Theo contract hiện tại, hàm trả 100,00%. | Hàm trả 100,00%; không dùng giá trị này để suy ra chất lượng khi mẫu số bằng 0. | Pass |
| UT-BE-08 | Chặn upload coverage sai giai đoạn | Project CASE_APPROVED, chưa sinh Unit Test. | Ném InvalidProjectStatusException và không lưu XML. | Đúng exception; storage không được gọi. | Pass |
| UT-BE-09 | Đọc bộ đếm và chi tiết JaCoCo | XML fixture: LINE missed 4/covered 7; BRANCH missed 1/covered 1; createOrder, cancelOrder. | Tổng 11 dòng/7 đã phủ, 2 nhánh/1 đã phủ; bỏ constructor/lambda. | Có 2 method; createOrder line 40%, branch 50%, missed lines [12,15]; cancelOrder branch 100%. | Pass |
| UT-BE-10 | Từ chối dữ liệu không phải JaCoCo | XML có root pom và chuỗi khong phai xml. | Cả hai đầu vào gây IllegalArgumentException. | Hai assertion bắt đúng IllegalArgumentException. | Pass |
| UT-BE-11 | Lọc truy vết theo service | Project 7 có BR-001 của billing và BR-002 của account; chọn billing-service. | Ma trận chỉ có BR-001. | Ma trận trả đúng BR-001, không chứa BR-002. | Pass |
| UT-BE-12 | Giữ yêu cầu người dùng khi khác source | BR balance >= amount; source balance > amount; phản hồi review SOURCE_MISMATCH. | Giữ BR/anchor; suggestedDescription null; người dùng vẫn approve được. | BR và IF-1 giữ nguyên; không nhận gợi ý đổi BR; approve trả APPROVED. | Pass |
| UT-BE-13 | Chặn sinh Plan khi BR chưa duyệt | BR id 7 ở PENDING_REVIEW. | Từ chối trước khi gọi AI hoặc thay đổi Test Plan. | Ném InvalidProjectStatusException; không tương tác AI/repository Plan. | Pass |
| UT-BE-14 | Lưu Test Plan hợp lệ | BR 7 đã duyệt; plan 99 cũ; AI fixture trả plan Happy path. | Thay plan cũ; plan mới liên kết BR 7, chờ duyệt. | Xóa/flush plan cũ; lưu TP-001 PENDING_REVIEW; project PLAN_PENDING_REVIEW. | Pass |
| UT-BE-15 | Tiếp tục sinh phần Plan còn thiếu | BR 7 đã có plan 99; BR 8 chưa có plan; resume=true. | Chỉ gửi BR 8 cho AI; giữ plan cũ. | AI nhận Set(8); chỉ lưu plan cho BR 8; không gọi deleteAll. | Pass |
| UT-BE-16 | Chặn sinh Case khi Plan chưa duyệt | Plan id 20 ở PENDING_REVIEW. | Từ chối; không gọi AI, không xóa Case cũ. | Ném LlmResponseException; AI không được gọi, không xóa Case; Plan giữ pending. | Pass |
| UT-BE-17 | Bảo toàn Case khi điều kiện BR không hợp lệ | Plan 20 đã duyệt; bước kiểm tra prerequisite báo BR chưa APPROVED. | Dừng trước generation và trước xóa Case. | Ném InvalidProjectStatusException; không generateTestCases, không deleteAll. | Pass |
| UT-BE-18 | Chặn sinh Unit Test khi Case chưa duyệt | Case 31 pending, thuộc Plan 20 đã duyệt. | Từ chối trước gọi AI và trước sửa Unit Test. | Ném LlmResponseException; không tương tác AI/repository Unit Test. | Pass |
| UT-BE-19 | Bảo toàn Unit Test khi oracle preflight thất bại | Case 31 đã duyệt; prerequisite báo BR chưa APPROVED. | Không sinh và không sửa Unit Test. | Ném InvalidProjectStatusException; không generateUnitTests; không tương tác repository Unit Test. | Pass |
| UT-BE-20 | Giữ assertion của Case đã duyệt | balance=100, amount=100; AI fixture trả assertTrue(service.transfer(100,100)). | Giữ nguyên assertion khi lưu kiểm thử. | Source lưu vẫn chứa assertTrue(service.transfer(100, 100)); test này không chạy production code. | Pass |
| UT-BE-21 | Tạo bản nháp cập nhật source cùng project | Project 10 của user 1; update.zip giả lập; storage trả candidate revision 100. | Tạo draft 200, candidate revision 100 và liên kết với project 10. | DTO trạng thái DRAFT, id 200/candidate 100; activeSourceUpdateId=200. | Pass |
| UT-BE-22 | Chặn hai bản nháp source đồng thời | Project 10 đã có active draft 999. | Từ chối tạo bản nháp mới. | Ném IllegalArgumentException với thông báo project đang có bản nháp. | Pass |
| UT-BE-23 | Lập đề xuất từ kết quả diff và impact | Diff fixture: calculateDiscount(int) MODIFIED; 1 BR bị ảnh hưởng. | Trạng thái ANALYZED, 1 method thay đổi, tạo đề xuất method và BR. | DTO có ANALYZED/totalChangedMethods=1; lưu 2 SourceUpdateItem. | Pass |
| UT-BE-24 | Chặn áp dụng khi còn đề xuất chưa duyệt | Draft READY_TO_APPLY; đề xuất UPDATE BR đang PENDING. | Từ chối áp dụng trước khi lưu project hoặc BR. | Ném IllegalArgumentException có PENDING; không gọi save project/BR. | Pass |
| UT-BE-25 | Áp dụng revision source và đề xuất được chấp nhận | Candidate revision 102; đề xuất CREATE BR đã ACCEPTED. | Source hiện hành chuyển sang revision 102, draft APPLIED. | storagePath=/tmp/cand_applied; activeRevision=102; activeUpdate=null; BR được save. | Pass |
| UT-BE-26 | Chỉ yêu cầu AI sinh BR cho method thay đổi | Impact fixture có calculateDiscount(int), methodId 101; update 200 ANALYZED. | AI chỉ nhận method 101; lưu đề xuất và chuyển READY_TO_APPLY. | generateBusinessRules nhận Set(101); có SourceUpdateItem; DTO READY_TO_APPLY. | Pass |
| UT-BE-27 | Kiểm chứng oracle tại điều kiện bằng nhau bằng fixture offline | Cùng test transfer(100,100)→0 trên source >= và source >; thêm trên/dưới ngưỡng. | Test đạt trên source đúng và phát hiện assertion nghiệp vụ ở source lỗi. | Harness đối chiếu hai phiên bản đạt; lỗi ở source > là kết quả phát hiện lỗi mong đợi. | Pass |
| UT-BE-28 | Không tính lỗi thiết lập thành phát hiện lỗi nghiệp vụ | Fixture gọi transfer trong BeforeEach; thân test chỉ assertTrue(true). | Harness loại kết quả phát hiện lỗi xuất phát từ setup. | Bắt đúng AssertionError của harness; không ghi nhận setup fail là business defect. | Pass |

Ở UT-BE-07, kết quả 100% khi không có BR là hành vi hiện tại đã được test xác nhận. Khi đánh giá hiệu quả hệ thống, trường hợp mẫu số bằng 0 cần ghi N/A thay vì coi đây là bằng chứng mọi yêu cầu đã được kiểm thử. UT-BE-09 sử dụng XML fixture có tổng 11 dòng và 2 nhánh; các giá trị này là dữ liệu thực tế trong test hiện có, không dùng bộ đếm minh họa khác để mô tả kết quả.

UT-BE-20 chỉ xác minh việc giữ assertion khi lưu. UT-BE-27 và UT-BE-28 là harness fixture offline có biên dịch/chạy Java, lần lượt kiểm tra phát hiện lỗi nghiệp vụ và loại lỗi thiết lập; chúng không đánh giá khả năng sinh kiểm thử của AI thật.

### b. Frontend

| Mã test case | Mô tả | Dữ liệu đầu vào | Kết quả mong đợi | Kết quả thực tế | Kết luận |
|---|---|---|---|---|---|
| UT-FE-01 | Mở chi tiết lớp và phương thức | UserController.findUser(Long), GET /users/{id}; click mở cây và method. | Hiển thị endpoint và source method sau thao tác. | Hiển thị /users/{id} và User findUser sau click. | Pass |
| UT-FE-02 | Hiển thị Existing Test Context | DTO fixture existingTestFiles=17. | Thông báo phát hiện 17 file và giải thích không tính vào production analysis. | Hiển thị đúng hai thông báo; đây là dữ liệu component giả lập. | Pass |
| UT-FE-03 | Thu gọn và mở kết quả phân tích | Component AnalysisResult; click Thu gọn rồi Mở rộng. | Ẩn phần Classes khi thu gọn; hiện lại khi mở. | Hai trạng thái ẩn/hiện Classes đúng assertion. | Pass |
| UT-FE-04 | Khóa sinh Unit Test khi Case chưa duyệt | Case PENDING_REVIEW; click AI sinh Unit Test. | Nút bị khóa, có hướng dẫn approve; không gọi generation hook. | Nút disabled, hướng dẫn hiển thị; mock generate không được gọi. | Pass |
| UT-FE-05 | Khóa tải ZIP khi chưa có kiểm thử | Danh sách Unit Test rỗng. | Khóa tải file và chuyển Coverage. | Hai nút bị disabled; có hướng dẫn công cụ JaCoCo. | Pass |
| UT-FE-06 | Xử lý thao tác sinh Unit Test | Case đủ điều kiện; click AI sinh Unit Test. | Gọi mutation sinh đúng một lần. | Mock generate được gọi một lần; UI có thông tin Test Case đã approve. API thật được kiểm tra ở IT-02. | Pass |
| UT-FE-07 | Hiển thị kiểm thử thiếu và trùng | 2 Case approved; 2 Unit Test cùng tham chiếu Case 1, Case 2 chưa có test. | Thông báo thiếu 1, trùng/thừa 1 và chặn chuyển Coverage. | Hiển thị đúng Thiếu 1 case / Trùng-thừa 1 test; nút Coverage bị khóa. | Pass |
| UT-FE-08 | Hiển thị kết quả impact cập nhật source | API mock: 3 method thay đổi, 2 thêm, 1 sửa; create(AppointmentRequest). | Hiển thị số thêm mới và method thay đổi. | UI hiển thị +2 và create(AppointmentRequest). | Pass |

Dữ liệu ở các test frontend được cấp trực tiếp cho component hoặc trả về từ API/hook mock. Kết quả này xác nhận hành vi giao diện trong điều kiện của test; giao tiếp qua backend và database thật được kiểm tra riêng ở phần tích hợp.

## 5.2.2. Kết quả kiểm thử tích hợp

| Mã test case | Mô tả | Dữ liệu đầu vào | Kết quả mong đợi | Kết quả thực tế | Kết luận |
|---|---|---|---|---|---|
| IT-01 | HTTP upload → analyze → manifest và storage | ZIP PriceService.discount(int) + PriceServiceExistingTest; owner có JWT. | Upload 201/analyze 200; 1 class, 1 method, 1 existing test; storage nằm trong vùng test. | HTTP đạt; manifest đúng sample.PriceService#discount(int):int; 0 parse fail; storage đúng. | Pass |
| IT-02 | Pipeline phê duyệt → Plan → Case → Unit Test | BR: amount=100 thì discount=10; provider fixture; thử sinh Plan trước/sau approve. | Chưa approve: FAILED, 0 Plan; sau approve: các stage hoàn thành, liên kết đúng; generated assertion chạy được. | Đúng guard; BR→Plan→Case→Unit liên kết đúng; expected=10; source test biên dịch/chạy đạt; download trả ZIP 200. | Pass |
| IT-03 | Coverage → history → traceability → export | Hai XML fixture: LINE 1 missed/1 covered, sau đó 0 missed/2 covered. | Round 2 có line 100%, previous 50%; history 2; trace đầy đủ; export JSON/Markdown chứa dữ liệu đã lưu. | Đúng counter/history; 1 trace row có Unit Test, 0 uncovered rule; hai export chứa BR và PriceServiceBoundaryTest. | Pass |
| IT-04 | JWT, quyền sở hữu và pause tác vụ đang chạy | Job TEST_PLAN RUNNING; thiếu token, token sai, user khác và owner. | Thiếu/sai token 401; khác owner 403; từ chối không đổi BR/progress; owner pause được. | Nhận đúng 401/403; snapshot trước/sau không đổi; owner nhận 200 và trạng thái PAUSED. | Pass |
| IT-05 | Tác vụ trùng và lỗi provider | Giữ provider fixture đang xử lý; gửi generation trùng/mutation; sau đó provider báo lỗi. | Yêu cầu trùng/mutation 409; job FAILED; BR approved giữ nguyên; không có Plan lưu dở. | 409 đúng; job FAILED; 0 Plan; BR không đổi; project vẫn BR_APPROVED. | Pass |
| IT-06 | Lưu và truy vấn Project trong PostgreSQL | Project demo-app, source ZIP, status UPLOADED. | Sinh id/createdAt; truy vấn theo status tìm thấy project. | ID và timestamp khác null; truy vấn UPLOADED chứa ID vừa lưu. | Pass |
| IT-07 | Đọc/ghi JSONB qua Hibernate và PostgreSQL | Method createUser; parameters name:String/age:int; throws IllegalArgumentException. | Sau flush/clear, đọc lại giữ đúng parameters và throwsList. | Đọc lại đúng hai tham số và IllegalArgumentException. | Pass |
| IT-08 | Lọc BR theo trạng thái và cờ chỉnh sửa | Hai BR approved: BR-001 modified=false, BR-002 modified=true. | Query approved có 2 BR; query modified chỉ có BR-002. | Có đúng 2 BR approved và chỉ BR-002 ở danh sách modified. | Pass |
| IT-09 | Hai yêu cầu sinh Case đồng thời | Cùng project/Plan approved; request thứ nhất được giữ tại mock AI. | Request thứ hai bị từ chối; sau hoàn thành chỉ có 1 Case của Plan. | Request hai gây GenerationInProgressException; request một hoàn tất; DB có đúng 1 Case. | Pass |
| IT-10 | Ghi quota ở transaction độc lập | consumeLlmCall rồi ném IllegalStateException để rollback transaction ngoài. | Quota reservation và log vẫn được lưu đúng 1 lần. | DB quotaUsed=1 và activity count=1 sau rollback ngoài; không gọi provider thật. | Pass |
| IT-11 | Khóa đồng thời khi đọc admin đang hoạt động | Hai admin fixture; transaction 1 khóa rồi disable admin 2; transaction 2 đọc với khóa. | Transaction 2 chờ khóa, sau commit chỉ thấy admin 1. | Chờ quá 250 ms trước nhả khóa; sau commit có admin 1 và không có admin 2. | Pass |

Ở IT-02, suite biên dịch và gọi test method từ source Unit Test trả qua API; đồng thời kiểm tra endpoint tải ZIP trả về HTTP 200 với kiểu `application/zip`. Suite này không đối chiếu byte trong ZIP tải xuống với source đã biên dịch. Hai XML trong IT-03 là fixture counter để kiểm tra lưu trữ/tính toán/lịch sử coverage, không phải số đo JaCoCo mới từ việc thực thi generated test.

Sau suite, truy vấn kiểm tra ghi nhận **0 project, 0 user, 0 BR, 0 Test Plan, 0 Test Case và 0 Unit Test** còn lại trên cả hai database kiểm thử. Snapshot HTTP của lượt chạy mới gồm **77 tệp JSON**, giữ các trạng thái và dữ liệu project phục vụ đối chiếu.

## Các trường hợp không thực thi và giới hạn kết luận

Ba test backend bị Skip được ghi riêng, không gán Pass/Fail:

| Test | Lý do Skip |
|---|---|
| `realProviderPreservesApprovedOracleAcrossDirectAndReviewedPaths` | Không bật property `greytest.oracle.live`; không có provider live trong lượt chạy này. |
| `replayFrozenProviderTestsWithoutNetwork` | Không bật property `greytest.oracle.replay`. |
| `matchesFrozenPetClinicManifest` | Điều kiện fixture/checkout cho golden test chưa được đáp ứng trong suite mặc định. |

Golden PetClinic từng có kết quả riêng 62/86 phương thức ở đợt trước; việc Skip lần này không xác nhận vấn đề đó đã được giải quyết. Các test Source Update trong bảng là kiểm thử dịch vụ và component với dependency mock. Lượt chạy này chưa cung cấp một quy trình HTTP hoàn chỉnh sửa source local → cập nhật source → AI sinh tăng dần → áp dụng → chạy test. UAT hai người và đánh giá độ chính xác AI thật thuộc các phần thực nghiệm riêng.

## Bằng chứng và khả năng đối chiếu

- [CSV sáu cột — 47 kịch bản](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/test-results.csv).
- [Ánh xạ từng mã test case đến test method và báo cáo gốc](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/test-case-mapping.json).
- [Chỉ mục 738 lượt test được phát hiện, có cả 3 Skip](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/invocations.csv).
- [Môi trường, revision và thống kê](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/metadata.json).
- [Log Maven](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/backend/maven.log) và [JSON Vitest](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/frontend/vitest.json).
- [Kiểm tra dọn dữ liệu database mới](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/backend/cleanup-main.json) và [database pipeline](D:/.KLTN/outputs/chapter-5/unit-integration-20261020/backend/cleanup-pipeline.json).

XML Surefire được sao chép vào `backend/xml` trong thư mục bằng chứng. Các property có tên chứa password/secret/token/key được thay bằng nhãn redaction; mã test, trạng thái, thời gian và assertion không đổi. File `backend/xml-archive.json` lưu hash trước/sau và số property đã ẩn. Snapshot mới do suite ghi vào một thư mục run riêng bên dưới đường dẫn lịch sử `integration-20261009`; bản sao của đúng lượt ngày 20/10 được lưu tại `backend/http-snapshots`, không ghi đè kết quả cũ.
