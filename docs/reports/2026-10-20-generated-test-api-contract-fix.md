# Chặn constructor và generic sai trong generator

## Phạm vi và bằng chứng ban đầu

Sửa GreyTest chung cho các source Java, không đưa tên class/module của source mẫu vào production generator.
Source kiểm chứng là `spring-boot-realworld-example-app-master` ở đường dẫn `source mẫu/2-10` đã xác nhận.
Khi chạy ban đầu, source mẫu hiện tại đã compile và chạy được script; các lỗi nêu trong yêu cầu
không còn nằm nguyên trạng ở toàn bộ file hiện tại. Tuy nhiên, hai regression mới chứng minh
validator GreyTest chưa phát hiện constructor sai arity và generic không tương thích: đều đỏ trước khi sửa.

## Thay đổi

- Thêm `GeneratedTestApiValidator`, gọi từ semantic validator trước khi trả/lưu response AI.
- Constructor được đối chiếu với khai báo source và Lombok NoArgs/AllArgs/RequiredArgs/Data.
  Không suy diễn constructor chắc chắn cho Builder, Tolerate hoặc Value có initializer không rõ.
- Generic concrete được kiểm tra ở argument và phép gán kết quả method có signature xác định.
  Không chặn theo suy đoán khi có overload/varargs, kế thừa, wildcard/type variable hoặc scope chưa hỗ trợ.
- Tên type được đối chiếu theo FQN/package/import. Không gán API của class trùng tên từ module khác.
- Lỗi chắc chắn đi qua cơ chế semantic correction đã có, yêu cầu AI sửa API/setup và giữ case ID/expected result.
  Regression gateway kiểm tra response sai constructor bị retry rồi mới chấp nhận response đúng.
- Prompt UT/TC bổ sung constructor chính xác, generic invariant, ID assignment từ constructor/update,
  early return, nested fixtures, thứ tự trim/reverse và tính nhất quán membership IDs.
- Source mẫu: sửa đúng hai fixture `User.update()` bị dùng nhầm để thiết lập ID trong ArticleQueryServiceTest.
  Nhánh authenticated dùng mock ID cố định; nhánh not-found không có stub ID dư. Bổ sung static import Mockito.mock.
- Giữ nguyên strict stubbing và production. Source mẫu đã có `getOrDefault(id, 0)` và test assert 0.
  Script JaCoCo/project binding, TEMP junction, wrapper classpath và CRLF đã sửa từ vòng trước được kiểm chứng lại.

## Kiểm chứng cuối

Toàn bộ backend tests trên Windows:

```text
mvn test
Tests run: 713, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

Kiểm tra validator trực tiếp bằng toàn bộ source và test mẫu:

```text
PRODUCTION_TYPES=95 TEST_FILES=10 API_PROBLEMS=0
```

Chạy nguyên lệnh trong thư mục source mẫu:

```text
./run-greytest-coverage.cmd
> Task :compileJava
> Task :compileTestJava
> Task :test
> Task :jacocoTestReport
BUILD SUCCESSFUL in 8s
7 actionable tasks: 7 executed
[GreyTest] SUCCESS: build\reports\jacoco\test\jacocoTestReport.xml
COVERAGE_EXIT=0
```

- 96 tests / 10 test classes, 0 failures/errors/skips theo XML test results.
- JaCoCo XML tồn tại, parse hợp lệ, 452977 byte ở lần kiểm chứng.
- Junction tạm đã dọn. Không còn pattern lenient, CursorPageParameter<Object>, List<Object> hoặc user.update trong test mẫu.
- Docker backend build thành công và được recreate bằng image mới.
- Reviewer chỉ đọc đã kiểm tra bản cuối: không còn finding actionable.

Log trong workspace gốc:

- `.greytest-generation-verification/source-api-full-backend-tests.log`
- `.greytest-generation-verification/source-api-realworld-smoke.log`
- `.greytest-generation-verification/realworld-api-contract-final.log`

## Giới hạn

Đây là các kiểm tra source có bằng chứng, không thay thế Java compiler/runtime cho mọi kiểu Java hoặc mọi source.
Regression gateway mô phỏng response LLM để kiểm chứng reject/retry; không gọi LLM thật để sinh lại toàn bộ artifact
đã lưu trong DB. Các quy tắc prompt mới áp dụng cho lần sinh tiếp theo. Không thay đổi expected result đã duyệt
hoặc sửa business logic của production để ép test xanh.
