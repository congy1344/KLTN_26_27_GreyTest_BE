# Bổ sung mục 5.2 — Chạy lại ba test bị Skip ngày 20/10/2026

## Phạm vi sửa và căn cứ cập nhật golden

Người dùng đồng ý giữ chính sách loại getter/setter đơn giản và sửa dữ liệu chuẩn để nhất quán với phạm vi phân tích. Đã kiểm tra từng thân của 24 phương thức trên snapshot PetClinic đã khóa: tất cả chỉ đọc field hoặc gán tham số vào field, không có annotation và không có throws theo chính sách parser. Hash của 30 file production khớp inventory đã lưu.

`petclinic-analysis.json` được nâng manifestVersion từ 1.0 lên 1.1, bỏ đúng 24 accessor: 86 phương thức còn 62. Các mảng classes, endpoints, serviceRepositoryRelations và source commit giữ nguyên. Không đổi production parser hoặc thay assertion để bỏ qua sai lệch. Golden vẫn so sánh danh sách phần tử chính xác; lượt này xác nhận 25 lớp, 62 phương thức, 17 endpoint và 0 relation theo fixture. Kết quả Fail 62/86 của lượt cũ được giữ nguyên trong bằng chứng lịch sử.

## Kết quả retest theo bảng sáu cột

| Mã test case | Mô tả | Dữ liệu đầu vào | Kết quả mong đợi | Kết quả thực tế | Kết luận |
|---|---|---|---|---|---|
| RT-GOLDEN-01 | Đối chiếu manifest PetClinic theo chính sách bỏ accessor đơn giản | 30 file production tại commit b3ee2c53e76e9267f03551a7cd36b0983c859c56; golden 1.1. | Khớp chính xác 25 lớp, 62 phương thức, 17 endpoint và danh sách relation của fixture. | JUnit 1/1 đạt; đã đi qua toàn bộ assertion classes/methods/endpoints/relations; 0 Fail/Error/Skip. | Pass |
| RT-REPLAY-01 | Chạy lại hai Unit Test từ phản hồi AI đã lưu, không gọi provider | Bản sao nguyên byte direct/reviewed unit-test.response.json từ pilot run-1945; bật greytest.oracle.replay=true. | Cùng assertion chạy đạt trên source đúng và phát hiện lỗi nghiệp vụ trên source sai; không tính setup failure. | JUnit 1/1 đạt trên cả hai đường; response gốc không đổi; 0 provider call, 0 Fail/Error/Skip của harness. | Pass |
| RT-LIVE-01 | Sinh và thực thi kiểm thử với provider AI thật trên hai đường trực tiếp và sau review | Model gpt-5.6-luna, temperature 0, max output 6000/call; BR balance >= amount; input balance=100, amount=100; source >= và >. | AI review SOURCE_MISMATCH, suggestion null; giữ expected=0; test đạt trên source đúng và phát hiện lỗi nguồn sai. | JUnit 1/1 đạt; direct và reviewed đều correct 1 Pass, faulty 1 lỗi nghiệp vụ đúng kỳ vọng; 7 calls, 0 retry, 35.987 provider tokens; 0 Fail/Error/Skip của harness. | Pass |

## Kiểm thử hồi quy và điều kiện chạy

Chạy thêm `JavaParserHelperTest`: **16/16 Pass**, không Fail/Error/Skip, bao gồm phân biệt accessor với phương thức nghiệp vụ, tách production/existing tests, overload, cú pháp Java 8/21 và xử lý file lỗi. Tổng ba lượt Maven là **19 invocation Pass**: golden 1, parser regression 16, replay 1 và live 1. Ba lệnh Maven đều exit 0.

| Lượt chạy | Bắt đầu (Asia/Saigon) | Thời gian bao quanh lệnh Maven | Kết quả |
|---|---|---:|---|
| Golden + parser regression | 2026-10-20T17:37:14.6688391+07:00 | 6.11 s | Pass |
| Replay offline | 2026-10-20T17:37:20.8572003+07:00 | 8.20 s | Pass |
| Provider live | 2026-10-20T17:37:29.0782489+07:00 | 68.71 s | Pass |

JDK dùng Temurin 17.0.19, Maven 3.9.9, backend parent commit `498d105` cùng bản sửa golden chưa commit. Không cần PostgreSQL hoặc frontend cho ba lượt này.

Lượt live sử dụng OpenAI-compatible provider ở `api.vietapi.tech`, model `gpt-5.6-luna`, 7 attempt thành công và không retry. Tổng token provider là **35.987**; không cộng cached/reasoning thêm vào tổng. Input/output riêng được adapter ghi 0 dù total dương, nên không dùng các số 0 đó như breakdown đáng tin cậy. Cả hai đường dùng input equality 100/100, expected số dư 0 và cùng byte kiểm thử khi đối chiếu hai implementation. Source lỗi `>` ném IllegalArgumentException tại TransferService.transfer; đây là lỗi nghiệp vụ mong đợi mà kiểm thử phát hiện, nên kết luận harness là Pass.

## Review và liên hệ với kết quả suite trước đó

Reviewer độc lập chỉ đọc code/dữ liệu và không phát hiện vấn đề ở golden JSON và README. Reviewer xác minh đủ 24 accessor, tính duy nhất của 62 phương thức, các mảng còn lại không đổi và assertions exact-match vẫn được giữ.

Suite ngày 20/10 trước đó có backend 560 Pass/3 Skip và frontend 175 Pass. Ba test trước đây Skip nay đã có kết quả thực thi riêng như bảng trên. Không cộng cơ học 19 vào 735 để công bố số test độc lập, vì 16 parser test đã xuất hiện trong suite trước. Chưa chạy lại toàn bộ suite sau sửa golden. Khi chạy `mvn test` mặc định, các test opt-in vẫn có thể Skip nếu không truyền cờ live/replay hoặc petclinic.path; bản sửa golden không thay cơ chế opt-in.

## Bằng chứng

- [Bảng CSV sáu cột](D:/.KLTN/outputs/chapter-5/skipped-tests-20261020/test-results.csv).
- [Thống kê và xác minh](D:/.KLTN/outputs/chapter-5/skipped-tests-20261020/verification.json).
- [Ba lệnh chạy, thời điểm và exit code](D:/.KLTN/outputs/chapter-5/skipped-tests-20261020/execution.json).
- [Rà soát 24 accessor từ source](D:/.KLTN/outputs/chapter-5/skipped-tests-20261020/golden/accessor-review.json).
- [Metric live, usage và kết quả hai implementation](D:/.KLTN/outputs/chapter-5/skipped-tests-20261020/live/verified-metrics.json).
- [Kết luận reviewer](D:/.KLTN/outputs/chapter-5/skipped-tests-20261020/reviewer.md).

Các thư mục `golden`, `replay`, `live` lưu XML Surefire của đúng từng lượt và log Maven. Golden có bản JSON trước/sau. Replay dùng bản sao phản hồi cũ trong thư mục mới; live dùng thư mục provider mới. XML đã ẩn các property nhạy cảm. Các prompt/source trong pilot là dữ liệu tổng hợp, không chứa project người dùng. Lượt này không thay thế UAT hoặc thực nghiệm AI trên nhiều dự án.
