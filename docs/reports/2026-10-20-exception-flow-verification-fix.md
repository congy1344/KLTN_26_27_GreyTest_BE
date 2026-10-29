# Sửa assertion tương tác trong luồng ngoại lệ

## Vấn đề và thay đổi

Test có thể assert đúng loại exception nhưng sai tương tác: production gọi `repository.findByName(name)` rồi mới kiểm tra kết quả null và ném exception, trong khi test lại dùng `verifyZeroInteractions(repository)` hoặc `verifyNoInteractions(repository)`.

Hai hướng xử lý là bổ sung hướng dẫn vào prompt, hoặc kết hợp hướng dẫn với semantic validator để phản hồi lỗi cụ thể cho lượt sửa của AI. Bản sửa dùng hướng thứ hai, tận dụng cơ chế retry hiện có; không đổi business logic, schema hay expected result của test case.

- Prompt yêu cầu truy vết source trước/sau điểm ném exception, xác nhận lookup đã xảy ra, và không stub dependency sẽ không được gọi.
- Nếu cùng repository đã lookup nhưng không được save/update, dùng `verify(repository).findByName(...)` và `verify(repository, never()).save(...)`; không assert zero cho toàn bộ repository. Dependency riêng chỉ xuất hiện sau điểm throw có thể assert zero, theo đúng phiên bản Mockito.
- Validator yêu cầu positive verification cho lookup chứng minh được từ source. Stubbing, `never()`/`times(0)`, sai đối số hoặc verification không thể chạy sau lệnh ném exception đều không đáp ứng yêu cầu.
- Hỗ trợ JUnit 5 `assertThrows`/`assertThrowsExactly`, JUnit 4 try/fail/catch và verification trong finally. Không ép sửa cấu trúc hợp lệ `@Test(expected=...)` kết hợp finally.
- Validation xảy ra trước lookup vẫn được phép kiểm tra zero interactions. Không suy diễn lookup trong vòng lặp, switch, ternary hoặc sau biểu thức có thể ném exception mà chưa đủ bằng chứng.

## Kiểm chứng

Regression được viết trước khi sửa validator. Nhóm đầu tái hiện 10 lỗi; nhóm biên bổ sung tái hiện thêm 9 lỗi trước khi sửa các vấn đề reviewer tìm thấy.

28 lượt regression mới so với baseline trước task: 26 cho semantic validator và 2 cho luồng AIAgent. Các trường hợp bao gồm findByName/findById, Optional.orElseThrow, exception trước/sau lookup, đúng/sai đối số, vị trí verification không thể chạy, catch/finally hợp lệ, zero trước Act, nullable boxed ID có thể lỗi unboxing trước lookup, và các luồng chưa đủ bằng chứng.

Một regression bám theo method thật `AccountServiceImpl.saveChanges` trong snapshot: assert đúng thông báo của `Assert.notNull`, verify lookup, never save và zero interactions với `statisticsClient`. Package và import trong context lấy đúng từ source; validator vẫn từ chối import không có bằng chứng, không nới lỏng kiểm tra để test đi qua.

Hai test AIAgent dùng LLM mock trả lần đầu assertion sai và lần sau bản sửa đúng. Cả Mockito 2/JUnit 4 và Mockito 5/JUnit 5 đều chuyển diagnostic vào prompt sửa, giữ nguyên ID, nội dung và expected result của test case. Không gọi LLM thật.

Toàn bộ backend: **672 lượt test, 671 passed, 0 failure, 0 error, 1 skipped** (PetClinic optional). Nhóm validator/gateway gồm 191 lượt đều pass trong lần chạy full cuối. Reviewer độc lập không còn actionable finding trong phạm vi bản sửa. Log trong workspace gốc:

- `.greytest-generation-verification/exception-flow-green.log`
- `.greytest-generation-verification/exception-flow-boundary-red.log`
- `.greytest-generation-verification/exception-flow-boundary-green.log`
- `.greytest-generation-verification/exception-flow-gateway-tests.log`
- `.greytest-generation-verification/exception-flow-pre-act-red.log`
- `.greytest-generation-verification/exception-flow-unboxing-red.log`
- `.greytest-generation-verification/exception-flow-full-tests.log`
- `.greytest-generation-verification/exception-flow-docker-build.log`

Backend Docker đã build và cập nhật riêng container backend. API `/api/ping` qua cổng 8080 và proxy 3000 đều trả 200; PostgreSQL và các volume được giữ nguyên.

## Giới hạn và dữ liệu cũ

Semantic guard chỉ bắt buộc lookup đầu tiên trên prefix chứng minh được, với đối số literal hoặc tham số method. Đây không phải bộ phân tích toàn bộ control flow Java. Với nhánh, alias hoặc biểu thức phức tạp không chứng minh được, validator không tự kết luận tương tác; prompt vẫn yêu cầu AI đọc đầy đủ source và báo thiếu bằng chứng khi cần.

Chưa đo phần trăm line/branch coverage JaCoCo và chưa gọi AI thật để sinh lại test của project 248. Các bản ghi/artifact đã lưu được giữ nguyên; test cũ bị lỗi cần được sửa hoặc sinh lại để nhận quy tắc mới.
