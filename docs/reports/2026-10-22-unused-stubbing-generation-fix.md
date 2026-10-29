# Sửa stub fixture thừa khi lookup không tìm thấy bản ghi — 2026-10-22

## Nguyên nhân

Log project 808 và kết quả chạy người dùng cung cấp xác nhận hai test lỗi:

- Case 2467: `ArticleQueryServiceTest.findById_WhenArticleNotFound_ReturnsEmpty`.
- Case 2473: `CommentQueryServiceTest.findById_WhenCommentNotFound_ReturnsEmpty`.

Cả hai tạo mock user và stub `user.getId()`, nhưng source lấy article/comment rồi trả `Optional.empty()` ngay khi lookup trả null. Nhánh này không đọc user; MockitoExtension báo `UnnecessaryStubbingException` sau khi test kết thúc. Giữ user khác null, bỏ đúng stub getter thừa là đủ; không thay nghiệp vụ hoặc tắt Strict Stubbing.

Snapshot regression lưu nguyên source và hai UT từ context `20261022-062734-180-10-unit-test.log` và response `20261022-062745-418-11-unit-test-response-1.log`. Chỉ giữ các khai báo và BR/TP/TC liên quan; không giữ các output tham khảo của lượt sinh trước.

## Thay đổi GreyTest

- Semantic validator phát hiện mẫu đã chứng minh được: một Act trực tiếp, source lookup ở câu lệnh đầu rồi null guard trả về, mock collaborator inject qua `@InjectMocks` và constructor Lombok `@AllArgsConstructor`, stub null độc lập ngay trước Act, đối số lookup khớp literal, fixture local không được đọc hoặc truyền sang nơi khác trên đường đi đó.
- Kết quả sai đi qua cơ chế semantic correction/retry hiện có, kèm case ID và tên stub cần bỏ. Không tự sửa UT, source mẫu, BR/TP/TC hoặc database.
- Prompt bổ sung bước đối chiếu từng stub với lời gọi thực sự tiêu thụ nó, kể cả setup dùng chung. Không dùng dummy getter, verification, null input hay `lenient()` để che lỗi setup.
- Các trường hợp helper, nhiều Act, constructor khác, field/method mơ hồ hoặc thiếu bằng chứng được bỏ qua bởi kiểm tra hẹp này; đây không phải bộ phân tích mọi đường đi Java.

## Kiểm chứng

- Regression trước sửa: 10 tests, 3 failures. Hai UT sai được chấp nhận và gateway trả ngay output sai thay vì yêu cầu sửa.
- Regression cuối gồm 17 tests: từ chối đúng hai UT sai, chấp nhận bỏ đúng getter stub, kiểm tra retry trả UT đã sửa, và các trường hợp không đủ bằng chứng như stub ghi đè, conditional, helper, đối số có side effect, tên trùng và getter đã được đọc.
- Nhóm backend liên quan: **273 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Log: `../.greytest-generation-verification/unused-stubs-targeted-final.log`.
- Bản sao source mẫu tại `../.greytest-runtime/unused-stubs-realworld`: phục hồi đúng hai dòng lỗi từ log để tái hiện **72 tests, 2 failures**; bỏ đúng hai dòng trong các method missing-record rồi chạy lại coverage: **72 tests, 0 failures/errors/skipped**, exit 0, JaCoCo XML mới và hợp lệ. Các getter stub cần thiết ở test khác được giữ lại. Source gốc ngoài workspace không bị sửa trong lượt làm việc này.
- Log chạy source: `../.greytest-generation-verification/unused-stubs-realworld-red.log`, `unused-stubs-realworld-green.log`, `unused-stubs-realworld-green.exit.txt`.
- Reviewer chỉ đọc đã hoàn tất, không còn phát hiện cần sửa. `git diff --check` đạt với các file hiện có được sửa trong lượt này.

## Áp dụng và giới hạn

Rebuild backend và sinh lại hai case liên quan để đi qua prompt/validator mới, rồi tải ZIP mới. Chức năng tiếp tục sinh chỉ bổ sung phần thiếu, không thay thế UT đã lưu.

Chưa gọi LLM thật hoặc chạy toàn bộ test backend trong lượt này. Runtime replay kiểm chứng cách sửa fixture trên bản sao source, không phải bằng chứng một lượt sinh LLM mới đã chạy đạt. Kiểm tra tĩnh chỉ chặn mẫu có đủ bằng chứng nêu trên, không bảo đảm loại bỏ mọi `UnnecessaryStubbingException` trong output tương lai.
