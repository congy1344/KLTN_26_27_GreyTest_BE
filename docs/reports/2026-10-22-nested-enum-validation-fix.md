# Sửa validator báo nhầm enum lồng nhau

## Lỗi và phạm vi

Batch Unit Test của project 811 (`hospital-management-demo-main`), case 2546–2553,
import `com.hospital.appointment.model.Appointment` và tham chiếu
`Appointment.AppointmentStatus`. Đây là cú pháp hợp lệ của enum public nằm trong class
`Appointment`, nhưng GreyTest yêu cầu FQN đầy đủ và từ chối batch trước khi lưu.

Đầu vào tái hiện là context `20261022-110439-829-72-unit-test.log` và response
`20261022-110635-605-77-unit-test-response-1.log`. Bản sao cố định nằm tại
`src/test/resources/fixtures/generated-contract/hospital-nested-enums.json`.
Test phục hồi trường nội bộ `validationSourceCode` từ `sourceCode` vì trường này
không được serialize vào log.

## Thay đổi

- `GeneratedUnitTestSemanticValidator` phân giải tên enum theo FQN, import tường minh
  của enum hoặc class ngoài, và package thực tế trong compilation unit.
- Áp dụng cùng cách phân giải cho khai báo kiểu, truy cập hằng và `valueOf`.
- Tra declaration bằng FQN, tránh lấy nhầm hằng khi nhiều enum trùng tên trong cùng file.
- Vẫn từ chối class ngoài sai, thiếu import và hằng không tồn tại. Quy định import
  riêng của GreyTest vẫn được áp dụng. Không đổi prompt, retry, schema hay source nghiệp vụ mẫu.

## Kiểm chứng

`GeneratedNestedEnumRegressionTest` có 14 lượt test, gồm batch thật không chỉnh mã,
gateway trả nguyên 8 test ngay lần gọi LLM đầu tiên, các cách tham chiếu hợp lệ,
import sai/thiếu, hằng không tồn tại và enum trùng tên ở hai file hoặc trong cùng file.

Đã quan sát test tái hiện thất bại trước sửa; test trùng tên trong cùng file cũng
thất bại trước khi đổi tra cứu declaration sang FQN.

Lệnh hồi quy chạy tại thư mục backend:

```powershell
.\mvnw.cmd -o '-Dtest=GeneratedNestedEnumRegressionTest,GeneratedUnitTestSemanticValidatorTest,AgentGatewayTest,GeneratedUnusedStubRegressionTest,GeneratedAccessorContractTest,GeneratedBooleanAssertionValidatorTest,GeneratedFixtureMutationRegressionTest,RealworldGeneratedContractRegressionTest,SourceValueContractTest,MultiSourceGenerationRegressionTest,SourceMockingGenerationTest,TestabilityGenerationGatewayTest,GenerationValidationGateTest,UnitTestServiceTest' test
```

Kết quả: **383 tests, 0 failures, 0 errors, 0 skipped**, `BUILD SUCCESS`.

Ngoài test backend, đã sao chép `appointment-service` vào
`.greytest-runtime/hospital-nested-enums/appointment-service`, đưa nguyên 8 body test
từ response vào các class có hậu tố ID để không trùng tên, rồi dùng POM gốc chạy
Maven với `-Dtest=AppointmentServiceTest_GreyTest_*`. Kết quả: **8 tests, 0 failures,
0 errors, 0 skipped**, `BUILD SUCCESS`. Lượt offline ban đầu thiếu WebFlux 3.1.5;
lượt online tải dependency và chạy thành công. Source mẫu gốc không bị sửa.

Bằng chứng tại workspace root:

- `.greytest-generation-verification/nested-enums/red.log`
- `.greytest-generation-verification/nested-enums/duplicate-name-red.log`
- `.greytest-generation-verification/nested-enums/targeted-final.log`
- `.greytest-generation-verification/nested-enums/hospital-tests-online.log`
- `.greytest-generation-verification/nested-enums/hospital-tests-online.exit.txt` (0)

Reviewer chỉ đọc đã kiểm tra delta; phát hiện enum trùng tên trong cùng file đã được
sửa và bổ sung regression. Review cuối không còn phát hiện cần sửa.

## Áp dụng và giới hạn

Rebuild backend để container dùng validator mới, rồi tiếp tục sinh UT:

```powershell
# Chạy tại KLTN_26_27_GreyTest_BE
docker compose up -d --build backend
```

Chưa rebuild hoặc thay đổi container đang chạy. Gateway được kiểm tra bằng response
đã ghi log, không gọi LLM thật để sinh một batch mới. Kết quả chạy source mẫu chỉ
chứng minh 8 test đã nêu; không phải kiểm chứng toàn bộ microservice trong mẫu.
