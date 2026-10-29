# Sửa validator Unit Test qua output AI thực tế của project 496

## Lỗi tìm được

File đính kèm chứa context project 496. Log backend và hai response AI gốc bổ sung cho thấy:

1. Validator từ chối `ch.qos.logback.classic.Logger` vì production import `org.slf4j.Logger`. Hai class thư viện khác FQN được coi nhầm là một class project chỉ do trùng simple name. Response đầu dùng Logback để bắt log là hợp lệ.
2. Validator chỉ loại `assertThrows` khỏi luồng thành công, bỏ sót JUnit 4 try/fail/catch, `@Test(expected=...)` và `assertThrowsExactly`. Test tìm user không tồn tại đúng ra trả Optional.empty và chờ UsernameNotFoundException lại bị ép stub Optional.of.
3. Sau lời nhắc sửa sai từ validator, response thứ hai bỏ sót static import `assertTrue`. Compiler thật phát hiện lỗi này; guard cũ chưa kiểm tra binding assertion.

Đây là lỗi kiểm tra code dùng chung của GreyTest. Không phải thiếu source, không phải yêu cầu đổi production để mock và không cần sửa Test Case đã duyệt.

## Bản sửa

- Registry dùng để ép exact FQN chỉ chứa khai báo/import thuộc namespace project được chứng minh từ context. Import thư viện không tự trở thành khai báo project. Giữ kiểm tra import project sai, wildcard project, class trùng tên giữa module và static import project chưa có bằng chứng.
- Phân biệt assertion exception theo cấu trúc thực: JUnit 4 expected annotation, JUnit assertThrows/assertThrowsExactly và try/fail/catch. Trong try/fail/catch chỉ Act trực tiếp ngay trước JUnit fail được coi là Act chờ ngoại lệ. Lần gọi thành công trước đó vẫn cần stub đúng. Catch có fail vì exception bất ngờ vẫn được kiểm tra như success path.
- Với framework JUnit đã xác định, assertion gọi không có receiver cần binding qua static import hoặc helper được khai báo/kế thừa. Thiếu binding tạo diagnostic cho cơ chế sửa semantic hiện có. Không đoán API của superclass hoặc wildcard thư viện chưa phân giải.
- Không thêm tên `piggymetrics`, `UserServiceImpl`, Logger hay một module cụ thể vào các nhánh production của bản sửa.

Hai cách xác minh là phân tích AST và compiler theo classpath thực. Bản sửa dùng AST cho các quy tắc có bằng chứng, đồng thời dùng compiler/runtime thực để kiểm chứng ca lỗi này. Không dựng thêm compiler sandbox hoặc tự chạy build của source tải lên trong backend.

Một regression cũ gộp cả import project sai lẫn import AMQP sai đã được điều chỉnh: vẫn assert import project sai bị chặn; không còn coi mọi alias thư viện trong source là class project. Kiểm chứng javac riêng bằng jar spring-amqp thật xác nhận FQN đúng compile và package AMQP không tồn tại bị compiler từ chối. Không tuyên bố source import list đủ để biết toàn bộ classpath thư viện.

## Regression và dữ liệu thực

Thêm **12 lượt regression**: ba cặp tên thư viện trùng nhau; bốn kiểu assertion exception; catch cho lỗi bất ngờ; hai case mixed success/exception; hai case binding assertion JUnit 4/5 kèm control cho import, FQN, helper và inheritance. Các lỗi đã được tái hiện RED trước khi sửa.

Full backend: **699 test, 698 pass, 0 failure, 0 error, 1 skip**. Reviewer độc lập không còn actionable finding trong phạm vi bản sửa.

Backend Docker đã build và cập nhật riêng container backend. `/api/ping` qua cổng 8080 và proxy 3000 đều trả 200. Giữ PostgreSQL và các volume hiện có.

Replay dùng repository thật, snapshot thật project 496 và nguyên văn hai response trong `/app/log`:

- Response 1, bốn case 1800–1803: validator chấp nhận và cả bốn test gốc compile/run thành công.
- Response 2: validator từ chối đúng assertion chưa được import, không còn ép đổi Logger hay Optional.empty thành Optional.of.
- Hash project/classes/methods/BR/plan/case/unit test trước và sau giống nhau. Adapter repository chỉ đổi đường dẫn Docker sang bản copy snapshot local trong bộ nhớ; không giả output LLM và không sửa entity lưu trong DB.

Compiler/runtime dùng source production thật và jar theo POM: Java 8 target, Spring Boot 2.0.3, Spring 5.0.7, Mockito 2.15, ByteBuddy 1.7.11, JUnit 4.12, BCrypt/Security 5.0.6, Spring Data 2.0.8, Logback 1.2.3, SLF4J 1.7.25. Bốn test ở response đầu và ba test không lỗi ở response sau giữ nguyên source. Case 1801 ở response sau được sao chép riêng và chỉ thêm `import static org.junit.Assert.assertTrue;`, không thay assertion, test data hoặc expected result.

Đã chạy **8 lượt test thật, tất cả pass: 7 source nguyên bản và 1 bản sửa đúng static import**. Java 8 portable chính thức từ Adoptium được tải vào thư mục kiểm chứng và kiểm tra SHA256; không đổi PATH, Java hệ thống, POM hay thư viện của project. Runtime này cần thiết vì Mockito 2/ByteBuddy cũ không chạy đúng trên Java 17 của backend.

## Log và giới hạn

Các artifact kiểm chứng nằm tại workspace gốc `.greytest-generation-verification/project-496-validation/`:

- `response-1.log`, `response-2.log`: hai output AI nguyên bản.
- `validator-red.log`, `mixed-flow-red.log`, `assertion-import-red.log`, `full-tests.log`.
- `replay.log`: kết quả validator, framework/version và fingerprint dữ liệu.
- `actual-output-compile.log`: javac cho tám item, xác nhận lỗi import và phép sửa tối thiểu.
- `runtime-response-*-case-*.log`: JUnit runtime cho từng test AI thật.
- `java8-runtime-asset.json`: nguồn portable runtime và checksum.
- `docker-build.log`: build backend.

Không gọi thêm LLM hoặc tiêu quota; không thay artifact của project 496. Chưa đo phần trăm JaCoCo hoặc chạy mọi project Java có thể tồn tại. Khả năng compile/run phụ thuộc source đầy đủ, thư viện và runtime phù hợp POM/Gradle. Các guard AST không thay thế compiler hoặc thực thi test.
