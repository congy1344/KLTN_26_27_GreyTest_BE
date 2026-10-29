# Hướng dẫn sinh assertion BigDecimal theo giá trị và scale

## Đối chiếu lỗi thực tế

Trong billing-service, `InvoiceService.refundInvoice` gán paidAmount bằng
`BigDecimal.ZERO`; helper dựng DTO chuyển tiếp giá trị này. Test ghi trong response
`20261022-125907-673-28-unit-test-response-1.log`, case 2577
`testRefundInvoice_RefundsPaidInvoiceAndEnrichesResponse`, cũng dựng mock-return
`refundedInvoice.setPaidAmount(BigDecimal.ZERO)` nhưng assert bằng
`new BigDecimal("0.00")`.

JUnit so sánh BigDecimal bằng equals, nên giá trị 0 scale 0 khác 0.00 scale 2.
Đổi expectation thành `BigDecimal.ZERO` khớp chính xác với object thực tế trong test.
Case ghi số tiền 0.00 nhưng không yêu cầu kiểm tra `scale() == 2`, làm tròn hay chuỗi
hiển thị hai chữ số. Các yêu cầu scale/format/rounding tường minh vẫn phải được giữ.

## Thay đổi trong GreyTest

Chỉ bổ sung mục `BIGDECIMAL VALUE AND SCALE` vào prompt chung
`src/main/resources/prompts/unit-test.md`, không hard-code tên InvoiceService,
field paidAmount hay project mẫu:

- Trace giá trị và scale qua source assignment, arithmetic, fixture mock trả về và DTO mapping.
- Khi kết quả thực tế là BigDecimal.ZERO, kỳ vọng chính xác bằng BigDecimal.ZERO.
- Chỉ dùng compareTo để so sánh giá trị số khi scale không thuộc contract.
- Giữ assertion về scale/rounding/format khi đã được yêu cầu; không normalize actual,
  đổi production hay đổi mock-return chỉ để test pass.
- Nếu source mâu thuẫn yêu cầu scale tường minh, báo generation_error kèm bằng chứng.

Không thêm validator suy đoán scale qua repository trả object khác và chuỗi helper/builder.
Giải pháp hiện tại tập trung vào hướng dẫn sinh assertion; không phải cơ chế tự động
chứng minh mọi phép tính BigDecimal trước khi export.

## Kiểm chứng

Đã tái dựng 30 UT billing từ 4 response ghi log, giữ nguyên body test, chỉ thêm hậu tố
case ID vào tên class để chạy riêng. Dùng source mẫu sao chép, POM gốc và runner
được xuất bằng UnitTestFileService; manifest vẫn có đủ 12 source files.

Lượt đầu: **30 tests, 1 failure, 0 errors**, đúng case refund, expected 0.00 / actual 0.

Chỉ thay một assertion trên bản sao test:

```java
assertEquals(BigDecimal.ZERO, result.getPaidAmount());
```

Lượt sau: **30 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS; JaCoCo XML tạo
mới, parse được, report billing-service, 18 classes, 9 packages, 103.038 byte.
Không sửa production source mẫu.

Đã chạy bộ hồi quy backend liên quan sau cập nhật prompt: **383 tests, 0 failures,
0 errors, 0 skipped**, BUILD SUCCESS. Nhóm test gồm semantic validator, gateway,
source-value contract, mocking, testability, các source mẫu và UnitTestService.

Đã render prompt qua PromptManager bằng context billing thật có 8 case, xác nhận
hướng dẫn mới được đưa vào prompt và expectedResult đã duyệt vẫn được giữ nguyên.
Đây là kiểm tra artifact prompt, không phải test đánh giá chất lượng output AI.

Reviewer chỉ đọc đã kiểm tra bốn dòng mới, không có phát hiện cần sửa.

Bằng chứng tại workspace root:

- `.greytest-generation-verification/decimal-scale/billing-red.log`
- `.greytest-generation-verification/decimal-scale/billing-green.log`
- `.greytest-generation-verification/decimal-scale/billing-green.exit.txt` (0)
- `.greytest-generation-verification/decimal-scale/targeted-final.log`
- `.greytest-generation-verification/decimal-scale/rendered-billing-prompt.txt`
- `.greytest-generation-verification/decimal-scale/unit-test.before.md`
- `.greytest-generation-verification/decimal-scale/StageBillingRunner.java`

## Giới hạn và áp dụng

Chưa gọi LLM thật để sinh lại output; kết quả 30/30 là chạy lại test ghi log sau
bản sửa assertion một dòng, không chứng minh AI chắc chắn sẽ sinh đúng scale trong
mọi tình huống. Prompt mới áp dụng cho các lượt sinh sau khi rebuild/restart backend.
Không tự sửa lại các test đã lưu hoặc file test đã xuất. Chưa rebuild container.
