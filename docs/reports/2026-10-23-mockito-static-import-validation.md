# Chặn lời gọi Mockito thiếu static import

## Nguyên nhân và khác biệt so với hôm trước

Response `log/20261023-090529-459-5-unit-test-response-1.log`, case 2603 và
2604, đã gọi `verifyNoMoreInteractions(doctorRepository)` mà không import hàm.
Lỗi xuất hiện trước bước gộp file. Trong response hôm trước
`log/20261022-145401-043-21-unit-test-response-1.log`, hai case tương ứng chưa
gọi hàm này, nên không có lỗi biên dịch đó.

Validator đã kiểm tra import assertion JUnit nhưng chưa kiểm tra binding của
các hàm Mockito. Chỉ bổ sung prompt không đủ để chặn lỗi cú pháp/import đã biết.

## Sửa tại luồng dùng chung

- Thêm `GeneratedMockitoImportValidator`, dùng AST để kiểm tra các lời gọi
  không có qualifier thuộc nhóm Mockito, ArgumentMatchers và BDDMockito.
- Gắn kiểm tra vào semantic validation trước khi chấp nhận output AI. Lỗi đi
  qua cơ chế semantic correction/retry hiện có; cache cũng được validate lại.
- Dùng cùng kiểm tra trong export gate trước khi tạo ZIP, bao gồm test cũ
  đã lưu. Không tự xóa verification/assertion hay đổi nghiệp vụ để vượt gate.
- Chấp nhận explicit static import, wildcard đúng owner, lời gọi có qualifier
  và helper khai báo/kế thừa. Wildcard thư viện chưa biết và helper chưa phân
  giải được được bỏ qua thận trọng; đây không phải trình biên dịch Java đầy đủ.

## Kiểm chứng

- Test hồi quy trước sửa: 18 test, 10 failure, đúng các trường hợp generation
  và export chưa chặn lời gọi thiếu import.
- Sau sửa: **363 test backend liên quan pass**, gồm 18 test hồi quy mới,
  semantic validator, gateway, export, source mocking và fixture validation.
- Tái dựng 22 UT doctor từ response hôm nay trên bản sao ZIP người dùng:
  runner lỗi biên dịch đúng hai case thiếu import. Chỉ thêm static import vào
  hai test, giữ nguyên assertion: **22 tests, 0 failures, 0 errors, 0 skipped**,
  BUILD SUCCESS và JaCoCo XML tạo mới, parse được.
- Kiểm tra trực tiếp context/output ghi log: generation gate từ chối batch
  thiếu import và chấp nhận batch sau sửa import; export gate có cùng kết quả
  cho từng case 2603/2604. Khi replay phải phục hồi `validationSourceCode` từ
  source snapshot vì trường này có `@JsonIgnore`, không nằm trong context log.
- Hash của cả 12 file Java production trong bản sao giữ nguyên.
- Reviewer chỉ đọc không có phát hiện cần sửa. `git diff --check` đạt.

Bằng chứng tại workspace root:
`.greytest-generation-verification/doctor-mockito-imports/`, gồm
`doctor-red.log`, `doctor-green.log`, `recorded-gates.log`,
`backend-regression.log` và snapshot source. Log test đầu tiên tại
`.greytest-generation-verification/mockito-imports-red.log`; log nhóm kiểm tra
chính tại `.greytest-generation-verification/mockito-imports-green.log`.

## Áp dụng và giới hạn

Rebuild/restart backend để chạy validator mới. Không tự sửa dữ liệu test cũ
trong DB; bản lỗi cần được sửa hoặc sinh lại. Chưa gọi LLM thật để sinh lại
trong lượt kiểm tra này; không khẳng định mọi lỗi biên dịch/runtime đều được
phát hiện bằng kiểm tra tĩnh. Chạy test thực tế bằng runner vẫn cần thiết.
