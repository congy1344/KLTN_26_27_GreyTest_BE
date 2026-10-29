# Sửa hợp đồng source khi sinh Unit Test — 2026-10-21

## Kết quả

Đã sửa bộ sinh và kiểm tra dùng chung của GreyTest; không sửa source mẫu, database hay logic nghiệp vụ của ứng dụng được kiểm thử.

- Kiểm tra getter boolean theo kiểu field, Lombok và cấu hình `lombok.config`; suy luận kiểu qua các chuỗi `Optional.get()` và container generic để phát hiện gọi nhầm `getFollowing()` thay cho `isFollowing()`.
- Kiểm tra một nhóm assertion boolean từ fixture có thể chứng minh bằng source: đối chiếu ID của từng đối tượng, vị trí trong kết quả và tập ID do mock trả về. Trường hợp gán nhầm trạng thái giữa hai author được trả về cơ chế sửa lại kết quả LLM hiện có. Khi tham số mock chưa thể đối chiếu tĩnh, yêu cầu bằng chứng `Mockito.verify` trực tiếp với đúng tham số; không tự suy đoán kết quả.
- Bổ sung supporting resources và cảnh báo vào context Test Plan, Test Case, Unit Test. Bộ thu thập MyBatis lần theo `include` liên namespace, giới hạn trong module và có giới hạn kích thước; cấu hình Lombok được thu thập theo thư mục cha phù hợp.
- Gắn fingerprint SHA-256 của các file Java trong context trước khi gọi LLM. ZIP xuất test có manifest và bước kiểm tra source trước build trên Windows/Unix. Source khác phiên bản hoặc thiếu file sẽ dừng trước khi build; UT cũ chưa có fingerprint hiển thị cảnh báo chưa xác minh.

## Đối chiếu ba lỗi thực tế

Fixture regression lưu lại context và UT thực tế của project 734:

1. Case 2360: getter sai bị từ chối, đổi sang `isFollowing()` được chấp nhận.
2. Case 2312: assertion đảo trạng thái following giữa hai author bị từ chối sau khi bổ sung bằng chứng mock đúng; assertion khớp ID được chấp nhận.
3. Case 2310: snapshot GreyTest đã đọc dùng `countMap.get(id)`, có thể unbox null thành NPE. UT kiểm tra NPE vẫn hợp lệ với snapshot đó. Không áp đặt mặc định `0` lên mọi source; source sử dụng `getOrDefault(id, 0)` phải được tải lên và sinh lại theo đúng phiên bản.

## Kiểm chứng

- Chạy toàn bộ backend bằng `mvnw.cmd test`: **858 tests, 0 failures, 0 errors, 1 skipped; BUILD SUCCESS**.
- Log: `../.greytest-generation-verification/generator-fix-full-suite-final.log`.
- Đã thử script xuất ZIP trên Windows và Unix: source khớp đi tới build giả lập, source thay đổi dừng trước build. Có kiểm tra BOM/line ending, đường dẫn, module không rõ ràng và việc giữ metadata khi gộp nhiều UT.
- `git diff --check` không báo lỗi.
- Reviewer chỉ đọc đã kiểm tra các phần liên quan; không còn phát hiện cần sửa trong phạm vi thay đổi cuối.

## Áp dụng và giới hạn

Rebuild backend rồi sinh lại Unit Test và tải ZIP mới. Chức năng tiếp tục sinh chỉ bổ sung phần thiếu, không thay thế toàn bộ UT cũ. Nếu cần sinh lại toàn bộ, thao tác sinh mới sẽ thay thế UT trong phạm vi đã chọn; nên giữ bản ZIP cũ nếu cần đối chiếu.

Chưa rebuild container, chưa gọi LLM thật hoặc cập nhật dữ liệu dự án để sinh lại UT trong lượt sửa này. Bộ kiểm tra assertion chỉ kết luận với mẫu source có đủ bằng chứng, không thực thi hoặc mô phỏng toàn bộ Java. Fingerprint hiện bao phủ các file Java được đưa vào context, chưa bao phủ mọi file build/config/resource. Helper kiểm tra source đã biên dịch với target Java 8 bằng JDK 17, chưa chạy trên runtime JDK 8.
