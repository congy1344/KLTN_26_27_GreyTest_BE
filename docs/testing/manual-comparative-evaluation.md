# Thực nghiệm: GreyTest chạy trên giao diện, hai baseline chạy riêng

## Phạm vi so sánh

Chạy cùng source đã đóng băng, cùng danh sách phương thức và cùng Business Rule đã được người thực hiện rà soát, phê duyệt. Ba cách đều sinh tuần tự Test Plan → Test Case → Unit Test. Cố định provider/model, tham số sinh, chính sách thử lại và phạm vi đánh giá trước mỗi đợt chạy; lưu cấu hình và đầu ra từng bước.

- **LLM-only với BR dùng chung:** source và BR dưới dạng văn bản, chỉ dẫn ngắn cho từng bước; không dùng kết quả phân tích AST, công cụ hoặc bộ kiểm tra của GreyTest. Cách này khác baseline LLM-only chỉ nhận source ở pilot cũ và phải được ghi đúng tên trong báo cáo.
- **Prompt-based:** cùng source và BR, prompt có cấu trúc và hướng dẫn thiết kế kiểm thử cho từng bước; không dùng agent hoặc bộ kiểm tra của GreyTest.
- **GreyTest:** thực hiện các bước sinh trên giao diện, giữ cơ chế phân tích và kiểm tra của hệ thống.

BR do GreyTest sinh là đầu vào dùng chung của thực nghiệm này. Người thực hiện phải đối chiếu BR với yêu cầu nghiệp vụ trước khi duyệt; BR suy ra từ source chưa đủ để chứng minh source đúng. Không dùng các số liệu pilot một lần gọi cũ để thay cho kết quả ba bước của thực nghiệm mới.

## Thao tác trên giao diện

1. Cập nhật backend và frontend có tính năng này, khởi động lại backend và tải lại trang. Số liệu không thể phục hồi cho lượt sinh trước khi cập nhật.
2. Nhập source, phân tích và chọn phạm vi service cần đánh giá. Lưu ZIP source gốc hoặc URL GitHub kèm commit SHA; giữ nguyên source trong cả đợt chạy.
3. Sinh BR, rà soát nội dung và phê duyệt. Nút **Tải BR (JSON)** đã được gỡ khỏi giao diện; danh sách BR (mã, mô tả, trạng thái duyệt) lấy từ mục `businessRules` của Report JSON ở bước cuối. Chỉ các BR đã duyệt được dùng làm đầu vào so sánh. Report JSON không có chữ ký phương thức, nên ghi lại class/method của từng BR từ tab Business Rules (BR được nhóm theo `Class.method`).
4. Sinh và duyệt Test Plan; sinh và duyệt Test Case; sinh Unit Test. Sau mỗi bước sinh, ghi lại mục **Số liệu lượt chạy** trong Log AI: ID lượt chạy, stage, tổng thời gian, token đầu vào/đầu ra/tổng, số phản hồi và thông báo token ước tính nếu có.
5. Tải Unit Test ZIP gốc. Giữ bản tải xuống trước khi tự sửa. Nếu có chỉnh sửa Plan/Case/Unit Test, lưu bản trước và sau, ghi rõ nội dung và thời điểm sửa; kết quả sau sửa phải được trình bày riêng.
6. Gửi bộ đầu vào bàn giao bên dưới để chạy hai baseline và thực thi kiểm thử của cả ba phương pháp.

Nếu hệ thống yêu cầu BR cho mọi quyết định trong phạm vi đã chọn, một BR riêng lẻ không thay thế được bộ BR cần duyệt. Nút tải không thay đổi điều kiện phê duyệt hiện tại.

## Ý nghĩa số liệu trên Log AI

- Mỗi ID là **một lần yêu cầu sinh của một stage**, không phải tổng lịch sử của project. Bấm sinh/tiếp tục ở lượt mới tạo ID và bộ đếm mới. Khi phải tiếp tục nhiều lượt để hoàn tất stage, lưu và cộng các lượt liên quan, kể cả lượt thất bại.
- Thời gian dùng đồng hồ đơn điệu tại backend, từ lúc nhận lượt sinh ở 0% (kể cả chờ hàng đợi) đến khi công bố COMPLETED ở 100%, gồm dựng ngữ cảnh, các batch, chờ provider/thử lại, kiểm tra và lưu kết quả. Thời gian dừng và giữ nguyên tại 100%, không gồm thời gian người dùng xem/duyệt. Không cộng thời gian từng batch chạy song song để thay cho thời gian này. Lượt lỗi/tạm dừng/hoàn tất một phần chốt thời gian tại trạng thái tương ứng và không được trình bày là đã hoàn tất 100%.
- Token cộng usage của các phản hồi được client ghi nhận, kể cả phản hồi cần sinh lại sau kiểm tra. Tổng token lấy từ provider, không cộng lại reasoning/cached token vào tổng. Nếu provider thiếu usage, hiển thị rõ số phản hồi sử dụng ước tính; request lỗi không trả usage có thể thiếu token nên không diễn giải tổng này là số token thanh toán đầy đủ.
- **Phản hồi AI đã ghi nhận** không đồng nghĩa số lần thử request: lỗi mạng/HTTP không có phản hồi usage không nằm trong bộ đếm này. Không dùng cột này làm số lần retry.
- Không có usage thì hiển thị dấu `—`. AI giả lập không có số liệu token provider.
- Sinh BR là chi phí chuẩn bị đầu vào chung, báo cáo riêng. Thời gian/token so sánh chính tính các bước Plan, Case và Unit Test theo cùng quy ước cho ba cách. Thao tác AI review đồng bộ không nằm trong số liệu sinh; nếu sử dụng cần ghi và đánh giá riêng.
- Snapshot backend lưu ngắn hạn 15 phút sau khi kết thúc; frontend giữ snapshot trong phiên trình duyệt. Chụp/lưu số liệu ngay sau từng lượt, tránh ghi đè bởi lượt mới hoặc mất khi đóng phiên.

## Bộ đầu vào bàn giao

- Tệp Report JSON `greytest-report-<id>.json` (mục `businessRules`) kèm danh sách class/method của từng BR đã duyệt.
- ZIP source gốc hoặc GitHub URL **kèm commit SHA** và module/build profile đã chọn. Không gửi `.env` hoặc khóa API.
- Phạm vi class/phương thức: đối chiếu danh sách trong BR với chữ ký trong source. Ba cách phải bao phủ cùng phạm vi.
- ZIP Unit Test GreyTest gốc; bản sửa nếu có và nhật ký sửa. Có thể gửi thêm Test Plan/Test Case để đối chiếu truy vết và nội dung.
- Provider/model và tham số đã dùng; số liệu GreyTest được người thực hiện bổ sung sau khi chạy.

## Thực hiện sau khi nhận dữ liệu

1. Đóng băng và băm SHA-256 source, BR và test ZIP; xác minh các BR đã duyệt và ánh xạ phương thức. Chốt cùng phạm vi trước khi gọi AI.
2. Chạy hai baseline theo ba bước đã định nghĩa, lưu prompt, phản hồi gốc, thời gian thực, usage và mọi lần thử lại. Không tự lấy Plan/Case của GreyTest làm đầu vào baseline; mỗi baseline dùng sản phẩm bước trước của chính nó.
3. Với mỗi phương pháp, đưa **chỉ test được đánh giá** vào bản sao source cùng commit, giữ nguyên assertion của bản gốc và biên dịch/thực thi. Ghi compile error, test failure và thiết lập không tương thích thành các kết quả riêng. Mọi sửa chữa phải lưu lại, không thay test âm thầm rồi ghi bản gốc là đạt.
4. Xuất Surefire/JUnit results và JaCoCo XML/HTML nếu build thực thi được. Đọc bộ đếm dòng/nhánh cho cùng phạm vi production class của cả ba cách; chỉ số toàn project nếu báo cáo phải đặt riêng. Không cộng kiểm thử có sẵn vào coverage AI.
5. Tổng hợp dữ liệu thật theo từng phương pháp/lần chạy. Để trống thời gian/token GreyTest chờ người thực hiện bổ sung; chỉ số không đo được ghi N/A. Mutation cần lượt chạy PIT riêng; Stability cần nhiều lượt lặp; UMR cần bằng chứng sửa thủ công. Test pass và coverage cao không tự chứng minh đúng nghiệp vụ.

| Phương pháp | Stage | Run ID | Trạng thái | Thời gian (s) | Input tokens | Output tokens | Total tokens | Nguồn usage | Ghi chú |
|---|---|---|---|---|---|---|---|---|---|
| GreyTest | TEST_PLAN | Người thực hiện điền | Chưa chạy | | | | | | |
| GreyTest | TEST_CASE | Người thực hiện điền | Chưa chạy | | | | | | |
| GreyTest | UNIT_TEST | Người thực hiện điền | Chưa chạy | | | | | | |

Hiện tài liệu này là quy trình bàn giao; chưa có lượt thực nghiệm mới từ BR/Unit Test do người thực hiện gửi.
