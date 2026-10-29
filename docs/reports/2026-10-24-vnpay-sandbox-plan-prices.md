# Điều chỉnh giá gói thử nghiệm VNPay

## Nguyên nhân và thay đổi đã duyệt

Giá Plus 1.000đ và Pro 2.000đ thấp hơn mức thanh toán tối thiểu 5.000đ được công bố tại FAQ VNPay: https://sandbox.vnpayment.vn/apis/docs/faqs/.
Người dùng đã chọn phương án Plus 10.000đ, Pro 20.000đ.

Đã cập nhật số tiền tạo giao dịch trong PaymentController, giá hiển thị và các nút gia hạn/nâng cấp trên ProfilePage, cùng thẻ gói/tổng tiền/nút thanh toán trong UpgradeToProModal. Quy đổi VNPay giữ nguyên: 10.000đ gửi vnp_Amount=1000000, 20.000đ gửi vnp_Amount=2000000. Các giao dịch đã lưu giữ nguyên số tiền lịch sử.

## Kiểm chứng

- Trước khi sửa: hai kiểm thử tạo giao dịch backend và ba kiểm thử giao diện thất bại đúng vì giá cũ.
- Sau khi sửa: PaymentControllerTest và VnpayServiceTest qua 10 kiểm thử; ba suite frontend ProfilePage, ProfileCancellation, UpgradeToProModal qua 13 kiểm thử.
- Frontend TypeScript/Vite build thành công; còn cảnh báo kích thước bundle đã có trước. jsdom không hỗ trợ chuyển sang trang VNPay, nên kiểm thử giao diện chỉ xác nhận giá và request tạo thanh toán.
- Reviewer chỉ đọc không phát hiện lỗi trong phạm vi đổi giá. Hai repository qua git diff --check.
- Đã build lại và khởi động backend Docker cục bộ. Log xác nhận ứng dụng khởi động; /v3/api-docs trả 200 và có API tạo thanh toán. Hai module giao diện đang phục vụ tại localhost:3000 trả 200, chứa giá mới.

Chưa thực hiện giao dịch thanh toán trên NCB sandbox. Khi thử lại cần tạo giao dịch mới; đường dẫn thanh toán đã tạo trước đó vẫn mang giá cũ.
