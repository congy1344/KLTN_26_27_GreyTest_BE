# Source update: không bỏ sót method khi ZIP thay đổi thư mục bọc

Ngày kiểm chứng: 23/10/2026.

## Hiện tượng và bằng chứng

Project `hospital-management-demo-main` (811), chọn module patient-service. ZIP người dùng xác nhận là:

```text
C:\Users\KhaiTien\Downloads\IUH\IUH\KLTN\source mẫu\2-10\hospital-management-demo-main\hospital-management-demo-main\backend.zip
```

ZIP này có đủ `PatientService.countActivePatients()` và `PatientService.getPatientsByBloodGroup(String)`. ZIP `source mẫu/2-10/hospital-management-demo-main.zip` cũ là file khác và không chứa hai method; không dùng ZIP cũ để kết luận về lần upload này.

Đối chiếu baseline thực tế bằng thao tác đọc:

```text
Baseline:  hospital-management-demo-main/backend/patient-service
Candidate: backend/patient-service
```

`SourceDiffService.compareSnapshots` nhận đúng hai method ADDED. `ImpactAnalysisService.matchesServiceScope` trước bản sửa so sánh module bằng nhau tuyệt đối nên loại cả hai. FE cũng lọc `servicePath` bằng scope đang chọn, vì vậy chỉ nới điều kiện lọc ở BE mà không chuẩn hóa đường dẫn trả về là chưa đủ.

## Thay đổi

`ImpactAnalysisService.alignModulePaths` chạy trước khi lọc diff theo service được chọn:

- Dùng qualified class đã lưu trong project làm mốc nhận diện module baseline, kể cả class không đổi.
- Khớp module ứng viên và baseline khi đường dẫn bằng nhau hoặc một bên là hậu tố nguyên đoạn của bên kia; hỗ trợ module gốc `.` khi thêm/bỏ thư mục bọc.
- Chỉ dùng alias khi module ứng viên khớp duy nhất một module baseline; không đoán khi mơ hồ.
- Trả `servicePath` theo baseline để FE hiển thị đúng scope đã chọn. Class/method mới cùng module kế thừa alias đã xác lập.
- Method DELETED giữ đường dẫn baseline. Khi không chọn service, giữ nguyên luồng diff không scope.

Không thay schema, logic nghiệp vụ source mẫu hoặc tự áp dụng bản cập nhật vào project đang dùng.

## Kiểm chứng

Test mới được chạy trước khi sửa: 4 test, 2 fail do trả 0 thay vì nhận method mới/class mới. Hai test bổ sung cho module gốc cũng đã fail trước khi thêm xử lý `.`.

Lần chạy cuối:

```powershell
.\mvnw.cmd -q '-Dtest=SourceUpdateScopeRegressionTest,SourceDiffServiceTest,ImpactAnalysisServiceTest,SourceUpdateServiceTest,IncrementalGenerationServiceTest,IncrementalBusinessRuleBatchTest' test
```

Kết quả: **40 tests, 0 failures, 0 errors, 0 skipped**. Trong đó 7 test mới kiểm tra đổi wrapper, class mới, alias mơ hồ, thêm/bỏ wrapper của module gốc, tránh gộp nhiều module về gốc và chuyển class sang doctor-service.

Replay cô lập lấy `PatientService.java` từ baseline thực tế và 57 production Java files từ đúng `backend.zip`: pipeline AST diff → impact theo scope trả đúng:

```text
ADDED countActivePatients()
ADDED getPatientsByBloodGroup(String)
servicePath = hospital-management-demo-main/backend/patient-service
```

Replay mock repository boundary; không chạy upload/apply trên UI, không gọi LLM, không ghi DB. Đây là kiểm chứng phân tích diff của patient-service, không phải đánh giá toàn bộ ảnh hưởng artifact của project. Reviewer chỉ đọc đã kiểm tra downstream incremental/apply và không còn phát hiện cần sửa.

## Dùng bản sửa

Backend hiện chạy bằng Docker cần rebuild/restart để nạp code mới, rồi mở lại cập nhật source và chọn đúng `backend.zip`. Bản sửa chưa được triển khai vào container trong lượt kiểm tra này.

Cách dùng ngay với backend cũ: nén lại ZIP giữ đường dẫn module giống baseline. Nếu đổi tên/thay cấu trúc module thực sự hoặc alias không xác định duy nhất, cần kiểm tra thủ công; bộ lọc không tự suy đoán việc đổi module.
