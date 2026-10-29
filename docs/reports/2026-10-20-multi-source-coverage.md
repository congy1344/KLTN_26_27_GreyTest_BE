# Kiểm chứng GreyTest trên nhiều source — 2026-10-20

## Kết quả

Đã kiểm chứng 6 source / 12 module bằng script coverage xuất từ UnitTestFileService hiện tại.
11 module chạy thành công, tổng 343 test, không failure/error/skipped; mỗi module có JaCoCo XML mới và hợp lệ.
HMS chưa đạt vì bộ test cũ gọi method và enum không tồn tại trong source hiện tại.

| Source / module | Kết quả | Exit | Tests | Line coverage | Branch coverage |
| --- | --- | ---: | ---: | ---: | ---: |
| RealWorld / Gradle | PASS | 0 | 96 | 15.46% | 9.44% |
| Hospital Management System / HMS / Gradle | FAIL compile | 1 | chưa chạy | — | — |
| Piggymetrics / notification-service / Maven | PASS | 0 | 35 | 76.09% | 72.73% |
| Piggymetrics / auth-service / Maven | PASS | 0 | 4 | 25.68% | N/A |
| Piggymetrics / account-service / Maven | PASS | 0 | 6 | 42.86% | 0.00% |
| Hospital demo / patient-service / Maven | PASS | 0 | 36 | 57.43% | 3.43% |
| Hospital demo / doctor-service / Maven | PASS | 0 | 32 | 61.69% | 0.91% |
| Hospital demo / appointment-service / Maven | PASS | 0 | 46 | 58.41% | 3.10% |
| Ecommerce / order-service / Maven | PASS | 0 | 14 | 58.44% | 16.67% |
| e-shop / payment-service / Maven | PASS | 0 | 10 | 50.00% | 4.08% |
| e-shop / order-service / Maven | PASS | 0 | 49 | 29.79% | 8.16% |
| e-shop / inventory-service / Maven | PASS | 0 | 15 | 76.34% | 3.92% |

Line/branch lấy từ counter cấp report của XML từng module, không phải chỉ phần Service được chọn.
N/A nghĩa là không có branch counter để chia; không được hiểu là 100%.
Các tỷ lệ branch thấp cho thấy test hiện có còn thiếu nhánh thực thi. PASS chỉ xác nhận compile/test/report của lần chạy,
không chứng minh code không còn bug hoặc test có đầy đủ mọi scenario.

## Cách kiểm chứng

1. Kiểm kê build, source và selector trong script hiện có. Chuẩn bị trên Java 17.0.18 với Maven cục bộ và Gradle wrapper của từng source.
2. Dùng createCoverageArchive với chính các test class đã chọn của source, giữ nguyên package/import/selector.
3. Chỉ thay hai script coverage/init từ archive mới; sao lưu bản script cũ. Không sửa business logic production,
   không thêm production class giả, không xóa/disable test để ép PASS.
4. Chạy `run-greytest-coverage.cmd` tại từng module, ghi exit code thật từ CMD vào sidecar,
   lưu stdout/stderr riêng, đặt timeout và kiểm tra XML có thời gian mới của lần chạy.
5. Kiểm tra test count thực sự chạy, failure/error/skipped và counter JaCoCo. Exit 0 cùng report cũ hoặc chỉ có test skipped không được ghi PASS.

Runtime runner là script kiểm chứng cục bộ trong `.greytest-generation-verification`, không bổ sung chức năng chạy source trên server GreyTest.

## Regression có thể chạy lại trong repository

`src/test/java/com/greytest/service/agent/MultiSourceGenerationRegressionTest.java` có 19 case:

- 6 bộ khai báo source thực tế: constructor hợp lệ, overload bị bịa, DTO Builder/generic.
- 6 case đóng gói coverage: giữ đúng package/import/selector, CMD CRLF, không tự tạo MockMaker.
- 4 build thực tế: Java 8/11/17, JUnit 4/5; Mockito 2 của Spring Boot 2.0.x.
- 3 lỗi HMS: method không tồn tại, enum constant không tồn tại, sai package enum.

Các `.java.txt` trong `src/test/resources/fixtures/multi-source` là snapshot đọc bằng parser,
không compile thành production class. Origin được ghi trong README cạnh fixture.

Chạy toàn bộ backend sau khi thêm regression:

```text
mvn test
Tests run: 732, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

Reviewer chỉ đọc đã kiểm tra test, fixture, matrix và harness: không có finding actionable.

## HMS còn chưa đạt

`src/test/java/com/example/HMS/service/AppointmentServiceTest.java` chứa các lời gọi `completeAppointment(...)`,
trong khi AppointmentService hiện tại không khai báo method đó. Test còn dùng `AppointmentStatus.CONFIRMED`,
`APPOINTMENT_PENDING` và FQN `com.example.HMS.entity.AppointmentStatus`; enum thực nằm ở `com.example.HMS.enums`
và không có hai constant nêu trên.

Chưa tự thay method/scenario, xóa các test này hoặc thêm method giả. Cần đồng bộ BR/TP/TC của các scenario cũ
với source hiện tại rồi sinh lại các test bị ảnh hưởng. Regression mới xác nhận generator hiện có từ chối các API sai này.

## Evidence trên máy

Workspace gốc: `C:/Users/KhaiTien/Downloads/IUH/IUH/KLTN/Code/code`.

- Manifest: `.greytest-generation-verification/multi-source/manifest.json`.
- Mỗi module: `multi-source/<module-id>/result.json`, `coverage.exit.txt`, `coverage.stdout.log`, `coverage.stderr.log`.
- Report XML đầy đủ có đường dẫn trong result.json.
- Backup script gốc: `<module-id>/run-greytest-coverage.cmd.before` và `greytest-jacoco.init.gradle.before`.
- Full backend log: `.greytest-generation-verification/multi-source-full-backend-tests.log`.

## Giới hạn

Matrix chạy lại các test hiện có đã được chọn, không thực hiện một chu kỳ sinh mới BR/TP/TC/UT qua LLM cho cả 6 source.
Không ghi đè artifact DB. Đây là bằng chứng mở rộng phạm vi regression và khả năng chạy test/report trên nhiều source,
không phải cam kết test AI sinh ra sẽ đúng tuyệt đối với mọi project.
