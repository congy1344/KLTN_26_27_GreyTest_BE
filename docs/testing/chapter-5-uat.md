# Phiếu nghiệm thu người dùng GreyTest — chương 5

Trạng thái: **chưa thực hiện**. Người dùng đã xác nhận **2 người tham gia**; danh tính, ngày và kết quả chờ ghi bởi người thực hiện. Tổ chức ngay sau khi FLOW-01 và FLOW-02 có kết quả phù hợp, backend/frontend/DB ổn định và dữ liệu UAT đóng băng. Nếu flow còn lỗi chặn nhiệm vụ, ghi Blocked và sửa/retest trước buổi UAT.

## Chuẩn bị và người tham gia

SV-B tổ chức/quan sát; SV-A hỗ trợ môi trường ngoài phiên. Mời người có nền tảng Java/testing, ưu tiên ít nhất một người không trực tiếp triển khai. Codex không thay người tham gia. Mỗi người dùng account/project riêng. Observer cung cấp mô tả nhiệm vụ và dữ liệu, không hướng dẫn thao tác trước khi bắt đầu tính thời gian. Nếu có trợ giúp, ghi đúng lời hướng dẫn và thời điểm.

| Mã | Danh tính hoặc mã ẩn danh thực tế | Nền tảng Java/testing | Có tham gia phát triển GreyTest? | Ngày/giờ Asia/Saigon | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| UAT-P01 | Chờ ghi | Chờ ghi | Chờ ghi | Chờ ghi | Not Run |
| UAT-P02 | Chờ ghi | Chờ ghi | Chờ ghi | Chờ ghi | Not Run |

Môi trường mỗi phiên: run_id, BE/FE commit, browser/version, OS/CPU/RAM, URL test, PostgreSQL/version, provider_mode/model, fixture hash, ngôn ngữ hiển thị. Không ghi password/JWT/key. Đề xuất phiên 45–60 phút/người, có thể tách thời gian chờ provider/build. Đây là lịch dự kiến, không phải số đo.

## Bốn nhiệm vụ

| Mã TC | Lời giao nhiệm vụ | Dữ liệu/precondition | Tiêu chí nghiệm thu chốt trước buổi |
| --- | --- | --- | --- |
| UAT-01 | Đăng nhập, nhập dự án, phân tích và tìm một service method; cho biết project có test sẵn hay không | F-ZIP/F-EXIST đã review; account riêng | Upload/analyze thành công; tìm method đúng, hiểu production và existing tests |
| UAT-02 | Nhập yêu cầu chuyển tiền đã cung cấp; review/duyệt BR, Plan, Case, đảm bảo trường hợp số dư bằng số tiền chuyển trả 0 | UAT-01; F-ORACLE balance=100/amount=100; fixture response hợp lệ | Nhận biết các bước cần approve; không sửa oracle theo source sai; Case input/expected đúng và được duyệt |
| UAT-03 | Sinh và tải test về; đặt test vào bản sao dự án và chạy; giải thích một kết quả lỗi | UAT-02; toolchain/local project đã chuẩn bị; source >= và > | Tìm đúng ZIP/file; test compile/chạy; phân biệt business fail với compile/setup fail; giữ cùng assertion/input khi đối chiếu |
| UAT-04 | Upload XML, tìm coverage gap và BR liên quan; tải báo cáo để gửi người review | UAT-03 hoặc project TEST_GENERATED chuẩn bị; XML có gap known | Upload đúng; đọc được line/branch/gap/link; tìm export JSON/Markdown và hiểu phạm vi report |

Chi tiết request/preconditions/expected nằm trong [catalog](chapter-5-test-cases.csv) và [strategy](chapter-5-test-strategy.md). Chuẩn bị project phục hồi cho UAT-04 nếu UAT-03 bị chặn; ghi precondition thay thế để không che failure trước đó.

## Phiếu quan sát — sao chép cho từng người

Participant: ___; run_id: ___; observer: ___; ngày/giờ: ___; environment/evidence: ___.

| TC | Giờ bắt đầu/kết thúc | Thời gian thao tác / chờ AI / build | Kết quả thực tế | Hoàn thành độc lập / có trợ giúp / chưa hoàn thành | Trợ giúp và khó khăn | Pass/Fail/Blocked/Not Run | Evidence/defect |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-01 | | | | | | Not Run | |
| UAT-02 | | | | | | Not Run | |
| UAT-03 | | | | | | Not Run | |
| UAT-04 | | | | | | Not Run | |

Pass nếu hoàn thành nhiệm vụ và tiêu chí đúng; phân loại độc lập/có trợ giúp riêng để không đánh đồng usability. Fail nếu đã thử nhưng không đạt; Blocked nếu môi trường/phụ thuộc cản trở; chưa bắt đầu Not Run. Observer không chấm Pass dựa vào lời nói thay vì thao tác. Khi sửa/retest, giữ kết quả lần đầu và tạo record lần sau.

## Phiếu nhận xét sau phiên

Thang 1–5: 1 rất khó/không rõ, 3 trung bình, 5 dễ/rõ. Không gọi đây là SUS hay thang đo đã được chuẩn hóa.

| Nội dung | Điểm 1–5 thực tế | Lý do/ví dụ |
| --- | --- | --- |
| Tìm được hành động nhập/phân tích | | |
| Hiểu luồng BR → Plan → Case → Unit Test và các bước duyệt | | |
| Hiểu trạng thái tiến độ/lỗi và hành động tiếp theo | | |
| Tìm test tải về, coverage, truy vết và báo cáo | | |
| Mức phù hợp với nhiệm vụ developer/tester | | |

Khó khăn lớn nhất: ___.
Thao tác cần cải thiện ưu tiên: ___.
Có muốn dùng cho dự án khác trong phạm vi Spring Boot? Có/Không/Chưa rõ; lý do: ___.
Ý kiến người tham gia xác nhận đúng nội dung ghi: ___.

## Tổng hợp để viết 5.2/5.3

Có 8 lượt nhiệm vụ dự kiến (2 người × 4 nhiệm vụ), chưa có lượt thực tế. Tổng hợp số thực hiện, độc lập, có trợ giúp, Fail, Blocked và Not Run; không đổi mẫu số thành chỉ số nhiệm vụ thành công. Báo tỷ lệ hoàn thành chung và độc lập riêng, tử/mẫu rõ; thời gian thao tác tách AI/build; thiếu thời gian ghi N/A. Ghi cụ thể khó khăn và defect thực tế; chỉ chốt nhận xét sau cả hai phiếu.

Mẫu 2 người chỉ cung cấp phản hồi nghiệm thu trong phạm vi thử, không đại diện tất cả developer/tester. Không lấy Unit Test/Vitest Pass hoặc thao tác Codex làm bằng chứng UAT. Không điền tên/ngày/điểm hay kết quả giả.

