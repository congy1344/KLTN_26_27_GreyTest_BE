# Kiểm tra lỗi UT còn lại sau khi sinh mới — 2026-10-21

## Bằng chứng và nguyên nhân

Đã đọc báo cáo mới, log context/response và truy vấn chỉ đọc các UT liên quan của project 734. Không cập nhật database, source mẫu hoặc gọi LLM trong lượt sửa này.

| Lỗi trong báo cáo | Đối chiếu bản đang lưu |
| --- | --- |
| `getFollowing()` không tồn tại | Các case 2360, 2362, 2364, 2366 hiện đã lưu `isFollowing()`. Lần trả lời đầu của AI có getter sai nhưng các bản sau đã sửa. Chưa có output compiler và cây file phía máy chạy để kết luận file nào còn cũ. |
| `comments.get(20)` sau khi service xóa phần tử | UT 2981 / case 2338 vẫn có lỗi. Context đã chứa `comments.remove(page.getLimit())`; validator chưa kiểm tra việc test dùng lại index của fixture bị thay đổi. |
| `NotAMockException` khi verify encoder | UT 3025 / case 2348 đã dùng `spy(new BCryptPasswordEncoder())`. Lỗi nằm ở bước gộp: các record cùng `UserServiceTest` có setup khác nhau, nhưng hệ thống loại setup trùng signature và giữ setup đầu dùng encoder thật. |
| Assertion following bị đảo theo PREV | Case 2312 đang lưu đúng thứ tự `[a2, a1]`, author2 false và author1 true. Chưa tái hiện lại lỗi này trên bản đang lưu. |
| Mong đợi NPE nhưng không ném | Context và source snapshot của GreyTest vẫn dùng `countMap.get(id)` với giá trị được unbox vào primitive. Nếu source chạy ngoài dùng `getOrDefault(id, 0)`, phải đối chiếu phiên bản; không thể áp đặt kỳ vọng mặc định 0 lên snapshot dùng `get`. |

## Thay đổi

### Kiểm tra fixture bị thay đổi

Thêm `GeneratedFixtureMutationValidator` vào semantic validation hiện có. Với danh sách nhận trực tiếp từ collaborator và bị source xóa phần tử, validator phát hiện cách viết `contains(fixture.get(index))` sau lời gọi service. Phản hồi yêu cầu AI lưu expected element trước lời gọi, rồi dùng tham chiếu đó trong assertion.

Đây là yêu cầu giữ expected fixture ổn định, không phải bộ thực thi tượng trưng toàn bộ Java hay khẳng định mọi index đều vượt giới hạn. Trường hợp trả về bản sao, dùng danh sách đối chiếu độc lập, hoặc reassign khiến không chứng minh được alias thì bỏ qua. Có regression cho cú pháp `this.dependency`, source sao chép list trước khi xóa và test khôi phục snapshot.

Prompt dùng chung bổ sung hướng dẫn giữ snapshot trước mutation và chỉ verify/mock trên mock hoặc spy phù hợp.

### Giữ fixture khi xuất file

`UnitTestFileService` chỉ gộp các record có fixture tương thích. Khác setup/lifecycle, initializer, field chung, helper hoặc extension sẽ tách thành biến thể class/file có tên ổn định, tránh chọn ngầm setup của record đầu. Hai test cùng signature nhưng khác body cũng được giữ riêng. Các kiểm tra xung đột import/nested type và fingerprint hiện có được giữ lại.

Tên class/path mới đi qua DTO hiện có, nên selector Maven/Gradle trong ZIP chọn đúng các biến thể. Không sửa dữ liệu UT đã lưu để đạt kết quả này.

Replay 10 record `UserServiceTest` thực tế tạo 4 file:

- `UserServiceTest_GreyTest_2347`: case 2347, 2349, 2350.
- `UserServiceTest_GreyTest_2348`: case 2348, giữ setup spy.
- `UserServiceTest_GreyTest_2351`: case 2351, 2353, 2354, 2355, 2356.
- `UserServiceTest_GreyTest_2352`: case 2352.

## Kiểm chứng

- Regression từ UT 2338 thất bại trước bản sửa vì validator chấp nhận test sai; bản sửa yêu cầu tham chiếu lưu trước mutation và chấp nhận cách sửa đó.
- Regression xuất file biên dịch và thực thi Java dùng BCrypt thật/spy; trước bản sửa tái hiện `NotAMockException` do gộp setup.
- Có kiểm chứng thực thi cho helper overload, qualified outer `this`, lifecycle thiếu ở một record, initializer và giữ hai test cùng tên khác body.
- Reviewer chỉ đọc đã hoàn tất; không còn phát hiện cần sửa trong phạm vi cuối.
- Nhóm regression liên quan: **410 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Log: `../.greytest-generation-verification/remaining-ut-failures-targeted-final.log`.
- Lượt toàn bộ backend: **875 tests, 0 failures, 20 errors, 1 skipped**. Các integration test lỗi kết nối PostgreSQL `localhost:5433` vì Docker không chạy trong lượt kiểm tra cuối; không ghi nhận lượt này là đạt. Log: `../.greytest-generation-verification/remaining-ut-failures-full-suite.log`.
- `git diff --check` không báo lỗi.

## Áp dụng

Rebuild backend sau bản sửa này. Tải ZIP mới để áp dụng cách gộp fixture; riêng UT 2338 đã lưu cần sinh lại trong phạm vi liên quan để đi qua validator mới. Chức năng tiếp tục sinh chỉ bổ sung phần thiếu, không thay thế test đã lưu.

Chạy trên đúng snapshot source và thư mục test đã kiểm tra. Giải nén ZIP mới lên thư mục cũ không tự loại các file Java từ lượt xuất trước; compiler vẫn có thể biên dịch các file đó dù selector chỉ chọn test mới. Giữ bản sao trước khi chủ động dọn các test GreyTest cũ trên máy chạy.

Chưa rebuild container hoặc thực hiện một lượt sinh bằng LLM thật trong lượt sửa này. Chưa nhận log coverage nguyên bản và source thư mục phía máy chạy; không khẳng định đã tái hiện hoặc loại bỏ toàn bộ năm lỗi trong báo cáo trên môi trường đó.
