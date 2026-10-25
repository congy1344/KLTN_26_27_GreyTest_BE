# Người dùng hủy gói về Free

Người dùng Plus/Pro có nút **Hủy gói** cạnh thông tin tài khoản trên trang hồ sơ. Hộp xác nhận giải thích việc chuyển ngay về Free và giữ số lượt đã dùng. Thành công cập nhật đồng thời cache tài khoản/quota; lỗi giữ gói hiện tại và hiển thị thông báo. Trong lúc gửi yêu cầu, chặn gửi lặp và đóng xác nhận.

`POST /api/user/cancel-subscription` lấy tài khoản từ token, không nhận userId/tier từ client. `UserService.cancelSubscription` lưu tier Free, quota và audit `USER_CANCEL_TIER` trong cùng transaction. Hủy lại khi đã Free không đặt lại quota hoặc ghi thêm thay đổi.

`UsageQuotaService.downgradeToFree` giữ usage của chu kỳ hiện tại, chuyển hạn mức về cấu hình Free và mốc đầu tháng. Điều này tránh lần đọc kế tiếp vô tình đặt usage về 0 vì mốc tuần cũ. Nếu usage vượt hạn mức Free, remaining bằng 0; tháng tiếp theo vẫn reset theo luồng hiện có.

Callback VNPay đã SUCCESS chỉ trả lại biên nhận đã lưu, không nâng cấp lại gói đã hủy. Callback có transaction và khóa dòng đơn hàng để các callback đồng thời không cùng cấp lại gói; lưu kết quả thanh toán và nâng cấp cùng transaction. Không đổi schema hoặc gọi cổng thanh toán thật.

## Kiểm chứng

- Tái hiện trước sửa: thiếu nút hủy; hạ gói làm mất usage ở lần đọc tiếp theo; thành công thanh toán → hủy → mở lại callback khôi phục Pro ngoài ý muốn.
- **59 test backend pass**, gồm controller xác thực, hai gói trả về Free, quota, hủy lặp, lưu database/audit bằng tài khoản test có dọn dẹp, callback replay và hồi quy quản trị/concurrency.
- **18 test frontend pass**, gồm xác nhận/giữ gói, lỗi mạng, pending, cập nhật cache và request tài khoản cũ trả về muộn.
- TypeScript/Vite build thành công; còn cảnh báo bundle trên 500 kB. `git diff --check` pass cho hai repository.
- Reviewer chỉ đọc kiểm tra lại sau sửa callback, không còn phát hiện cần sửa.
- Vite localhost:3000 phục vụ module giao diện mới. Chưa kiểm tra thao tác trên tài khoản người dùng qua browser; chưa rebuild/restart backend đang chạy.

```powershell
# Backend
.\mvnw.cmd '-Dtest=UserServiceTest,UserControllerTest,UsageQuotaServiceTest,UsageQuotaTransactionIntegrationTest,AdminServiceTest,PaymentControllerTest,LlmBatchTierConcurrencyTest' test

# Frontend
npm.cmd test -- src/features/auth/pages/ProfileCancellation.test.tsx src/features/auth/pages/ProfilePage.test.tsx src/shared/components/ConfirmDialog.test.tsx src/shared/components/UpgradeToProModal.test.tsx src/features/admin/utils/activity-presentation.test.ts
npm.cmd run build
```
