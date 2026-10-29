# Sửa bộ xuất coverage Gradle và quy tắc fixture

## Phạm vi

Sửa hệ thống GreyTest; dùng `spring-boot-realworld-example-app-master` làm source kiểm chứng.
Source mẫu ở `C:\Users\KhaiTien\Downloads\IUH\IUH\KLTN\source mẫu\2-10\spring-boot-realworld-example-app-master\spring-boot-realworld-example-app-master`.

Khi kiểm tra ban đầu, bản mẫu đã có explicit project trong init script, wrapper classpath hợp lệ,
fixture ProfileData/stub enrichment và `countMap.getOrDefault(id, 0)` cùng assertion 0.
Các thay đổi production/test này không được tạo lại trong lần sửa GreyTest này.

## Nguyên nhân và thay đổi

- `UnitTestFileService` vẫn xuất init script dùng delegate ngầm trong `allprojects`.
  Đã dùng `project.plugins`, `project.apply`, `project.tasks`, `project.layout` rõ ràng.
- Script Windows cũ dùng `%SystemDrive%\tmp` và có thể tiếp tục build khi không tạo được junction.
  Đã dùng `%TEMP%\greytest-junctions`, kiểm tra đường dẫn ASCII, báo lỗi và thoát khi tạo junction thất bại.
- Dùng biến môi trường cho đường dẫn truyền vào PowerShell, giữ PATH Maven trước khi chuyển qua junction.
- Truyền exit code của lần chạy con, dọn junction khi thành công hoặc build thất bại.
- Script `.cmd` phải dùng CRLF. Với 10 selector thật, bản LF có thể build thành công nhưng
  `GOTO` không tìm thấy label. Đổi riêng sang CRLF đã tái hiện/khắc phục lỗi đó.
- Prompt TP/TC/UT bổ sung yêu cầu truy vết helper, fixture lồng nhau, các nhánh enrichment
  và phân biệt primitive với nullable. Không tự gán default 0 nếu source không quy định;
  khi source mâu thuẫn với expected result đã duyệt, báo lỗi thay vì thay yêu cầu.

## Kiểm chứng

Regression đã quan sát đỏ trước khi sửa: explicit project init script, TEMP không dùng được,
và newline CMD. Sau sửa, chạy trên Windows:

```text
mvn -Dtest=UnitTestFileServiceTest,AgentGatewayTest,GeneratedUnitTestSemanticValidatorTest,GenerationContextBuilderTest test
Tests run: 266, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Dùng `UnitTestFileService.createCoverageArchive` hiện tại với đúng 10 test class đã chọn của source mẫu,
sau đó cài hai script từ archive trực tiếp vào source mẫu. Script gốc đã được sao lưu tại
`.greytest-generation-verification/gradle-sample-before` trong workspace gốc.

Chạy trong thư mục source mẫu, không thêm Gradle flags:

```text
.\run-greytest-coverage.cmd
> Task :compileJava
> Task :compileTestJava
> Task :test
> Task :jacocoTestReport
BUILD SUCCESSFUL in 7s
7 actionable tasks: 7 executed
[GreyTest] SUCCESS: build\reports\jacoco\test\jacocoTestReport.xml
COVERAGE_EXIT=0
```

- 96 test, 10 class, 0 failure, 0 error, 0 skipped theo XML test result.
- JaCoCo XML tồn tại, parse hợp lệ, 452972 byte ở lần chạy kiểm chứng.
- Junction tạm đã được dọn sau lần chạy.
- Log đầy đủ: `.greytest-generation-verification/realworld-coverage-final.log`.
- Backend Docker build thành công và được recreate từ image mới.
- Reviewer chỉ đọc: không có finding actionable trong bản sửa cuối.

## Giới hạn bằng chứng

Lần kiểm chứng chạy các test hiện có trong source mẫu và bộ test GreyTest; không gọi lại LLM để
sinh lại toàn bộ artifact cũ. Quy tắc prompt mới hỗ trợ các lần sinh tiếp theo, không phải cam kết
mọi test do AI sinh trên mọi source đều tự động compile hoặc chạy thành công.
