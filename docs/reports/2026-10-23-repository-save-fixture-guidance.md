# Fixture trả về từ repository không giữ dữ liệu request

## Bằng chứng

Response `log/20261023-084402-211-27-unit-test-response-2.log` đã chứa lỗi ở case
2640 và 2644, trước khi gộp hoặc xuất file. Hai test tạo `savedPatient` chỉ có ID,
createdAt và updatedAt rồi dùng `thenReturn(savedPatient)`, nhưng lại assert tên
theo request. Mockito trả đúng object fixture đó; không sao chép các trường từ
entity được truyền vào `save()`.

Source ZIP người dùng cung cấp gọi `new PatientResponseDto(savedPatient)`;
constructor DTO đọc trực tiếp các getter của object này. Context
`log/20261023-084318-157-23-unit-test.log` đã có đủ service, helper ánh xạ,
request/response DTO, entity và repository. Test Case đã duyệt mô tả mock-return
bằng ID/timestamp nhưng expectedResult vẫn yêu cầu thông tin khớp request;
fixture phải giữ các trường đó để nhất quán với expectedResult.

Chưa có log tương ứng hôm trước để chứng minh nguyên nhân khác biệt giữa hai lần.
Bằng chứng hiện tại xác định lỗi trong output AI, không phải tại bước xuất ZIP.

## Thay đổi

- `prompts/test-case.md`: yêu cầu mô tả rõ dữ liệu entity mà `save()` trả về và
  tính nhất quán với DTO mong đợi.
- `prompts/unit-test.md`: phân biệt request, entity gửi lưu và object mock trả về;
  không giả định persistence callback hoặc sao chép tự động. Ưu tiên `thenAnswer`
  giữ entity đầu vào, chỉ mô phỏng ID/timestamp do persistence sinh. Nếu phải trả
  entity khác, dựng đủ fixture và kiểm tra entity gửi lưu bằng ArgumentCaptor khi
  mục tiêu là kiểm chứng ánh xạ request.
- Giữ nguyên null-email scenario và expectedResult đã duyệt; không sửa assertion
  thành null hoặc bỏ assertion để che fixture thiếu dữ liệu.

Không thêm bộ suy diễn dữ liệu Java tổng quát. Không tự sửa test đã lưu trong DB.

## Kiểm chứng

Tái dựng 31 UT patient từ các response hôm nay, đổi hậu tố class theo case ID để
chạy riêng. Xuất runner bằng `UnitTestFileService` và chạy
`run-greytest-coverage.cmd` trên bản sao source ZIP:

- Trước sửa fixture: **31 tests, 2 failures, 0 errors**, đúng hai assertion
  expected Tran/Vo, actual null.
- Chỉ bổ sung firstName, lastName và phone vào hai fixture, giữ nguyên assertions:
  **31 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS.
- XML JaCoCo mới parse được: `patient-service`, 9 classes. SHA-256 của cả 12 file
  Java production trong bản sao vẫn giữ nguyên.
- **100 test backend liên quan pass**: prompt rendering, gateway, validation gate,
  source mocking, multi-source/export và UnitTestService.
- Render prompt thật bằng context có 8 Test Case đã duyệt; hướng dẫn mới có trong
  prompt, context giữ nguyên. Reviewer chỉ đọc không có phát hiện cần sửa.

Bằng chứng tại workspace root: `.greytest-generation-verification/patient-save-fixtures/`,
gồm `patient-red.log`, `patient-green.log`, `backend-regression.log`,
`rendered-patient-prompt.txt`, snapshot source và báo cáo coverage.

## Giới hạn

Chưa gọi LLM thật để sinh lại output. Kết quả replay kiểm chứng cách sửa fixture,
không chứng minh prompt bảo đảm mọi lần sinh sau đều đúng. Prompt mới áp dụng sau
khi rebuild/restart backend; test cũ cần được sửa hoặc sinh lại riêng.
