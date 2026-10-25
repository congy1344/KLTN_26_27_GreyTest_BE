# Sửa luồng tự động sinh test với dependency được tạo nội bộ

## Lỗi thực tế và nguyên nhân

Project 248, TC-1021 (ID 1774), plan 1278, method `UserServiceImpl.create(User)` ở auth-service. Source khởi tạo `private static final BCryptPasswordEncoder encoder` và `private final Logger log`; chỉ repository là dependency được inject. Test Case do AI sinh lại đưa `encoder.encode = "encoded-password"` vào dữ liệu mock, khiến bước Unit Test báo UNSUPPORTED_BEHAVIOR.

Nguyên nhân chính: context Test Plan/Test Case gọi `classes(..., false)`, chỉ truyền method source và bỏ source class, nên AI không thấy cách khởi tạo field. Phía Unit Test đã nhận full source và phát hiện setup không thể inject, nhưng coi việc không mock được là lý do dừng cả scenario.

Hai hướng là sửa production để inject encoder/logger hoặc sửa GreyTest để dựng test theo wiring và hành vi thật. Bản sửa chọn hướng thứ hai, giữ nguyên production và tất cả artifact đã lưu. Không sửa riêng TC-1021 hoặc đưa lại case này về chờ duyệt.

## Thay đổi

- Test Plan/Test Case nhận source class đầy đủ, gồm field, constructor và imports từ snapshot hiện hành. Bản cập nhật source truyền snapshot ứng viên riêng xuyên suốt hai bước này, tránh lấy baseline cho method mới chưa Apply.
- Prompt phân biệt kết quả nghiệp vụ với kỹ thuật mock gợi ý. Dependency tạo nội bộ có thể được dùng thật nếu vẫn kiểm chứng đúng scenario và expected result. Giá trị mock gợi ý không mặc nhiên trở thành literal output bắt buộc.
- Semantic guard tại Test Case đối chiếu `test_data.mocks` với field final có initializer tạo object/factory trong source, giới hạn đúng Service/method của plan. Setup sai được phản hồi để AI sinh lại, tối đa hai lượt. Không gắn cứng với BCrypt, piggymetrics hoặc tên module.
- Source parser hỗ trợ Java 21 và class Service lồng nhau; không lấy field trong comment hay class không liên quan làm bằng chứng.
- Parser giữ case ID và reason của generation_error. Với Unit Test, chỉ cho một lượt xem lại setup nếu reason là UNSUPPORTED_BEHAVIOR, case đúng trong request và source chứng minh mock gợi ý không inject được. MISSING_SOURCE hoặc case khác vẫn dừng. Nếu kỳ vọng thật sự trái source, phải trả lỗi tiếp; không tự đổi expected result, xóa assertion hoặc thay scenario.
- Prompt có bằng chứng POM/Gradle của module chứa source và các cấp cha, gồm dependency, version và exclusion; không lấy cấu hình từ module anh em. Tổng nội dung tối đa 64.000 ký tự và được đánh dấu là source data không đáng tin cậy, không phải chỉ dẫn cho AI.
- Logger nội bộ không cần biến thành mock. Khi phải kiểm tra log, dùng capture phù hợp backend được chứng minh có sẵn và dọn appender trong finally; không tự thêm thư viện hoặc loại bỏ assertion bắt buộc.

## Kiểm chứng

Full backend: **687 test, 686 pass, 0 failure, 0 error, 1 skip** (PetClinic optional). Có 15 lượt regression mới so với baseline trước task; thêm các assertion vào gateway test hiện có. Nhóm test mới đã tái hiện lỗi trước khi sửa. Reviewer độc lập không còn actionable finding trong phạm vi bản sửa.

Backend Docker đã build và cập nhật riêng container backend. `/api/ping` qua cổng 8080 và proxy 3000 đều trả 200; PostgreSQL và các volume được giữ nguyên.

Smoke test dùng repository và context builder thật cho project 248/TC 1774:

- Context chứa đúng `UserServiceImpl`, import BCrypt, field static final và POM auth-service/root.
- Setup mock encoder bị phát hiện ở Test Case; cùng setup được phép xem xét lại ở Unit Test.
- MISSING_SOURCE vẫn terminal.
- Hash dữ liệu project, classes, methods, BR, plan, case và unit test trước/sau giống nhau. Không gọi LLM thật, không tiêu quota, không thay trạng thái APPROVED của TC-1021.

Kiểm chứng behavior riêng dùng chính bốn file production User/UserRepository/UserService/UserServiceImpl từ snapshot. Compile với Java 8 target và các jar đúng POM: Spring 5.0.7, Security/BCrypt 5.0.6, Spring Data 2.0.8, JUnit 4.12, Logback 1.2.3 và SLF4J 1.7.25. Test runtime kiểm tra lookup username, hash có mặt trước save, BCrypt.matches với password gốc và log chứa đúng username; **1 test pass**. Chỉ inject repository @Autowired bằng Spring Test; không chạm encoder/logger final.

Một phiên bản test dùng Mockito 2.15 cũng compile thành công với Java 8 target. Không chạy được Mockito 2/ByteBuddy 1.7.11 trên JDK 17 của máy do API Unsafe cũ, nên kết quả runtime ở trên dùng repository test double Java Proxy. Không thay dependency hoặc POM của project để vượt qua lỗi runtime; không tuyên bố đã chạy output AI trên mọi JDK/Mockito.

## Log

Trong workspace gốc:

- `.greytest-generation-verification/internal-dependency-context-red.log`
- `.greytest-generation-verification/internal-dependency-generation-red.log`
- `.greytest-generation-verification/internal-dependency-generation-green.log`
- `.greytest-generation-verification/internal-dependency-parser-red.log`
- `.greytest-generation-verification/internal-dependency-build-evidence-red.log`
- `.greytest-generation-verification/internal-dependency-full-tests.log`
- `.greytest-generation-verification/internal-dependency-smoke/run.log`
- `.greytest-generation-verification/internal-dependency-runtime/compile.log`
- `.greytest-generation-verification/internal-dependency-runtime/observable-run.log`
- `.greytest-generation-verification/internal-dependency-docker-build.log`

## Giới hạn

Chưa gọi LLM thật để sinh lại TC-1021 và chưa đo phần trăm JaCoCo. Việc phản hồi/sửa output AI được kiểm chứng bằng LLM mock; kiểm chứng source/runtime thật là các phép kiểm tra riêng nêu trên. Guard chỉ kết luận về các khai báo và mock path có bằng chứng, không phân tích mọi biểu thức hay khả năng mock của mọi thư viện Java.

Không thay đổi các lỗi audit nguồn tăng dần nằm ngoài task này, chẳng hạn toàn bộ cơ chế overlay artifact đang chờ Apply. Bản sửa tại đây chỉ bảo đảm truyền đúng source snapshot cho TP/TC và giữ nguyên dữ liệu cũ.
