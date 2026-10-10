# Kết quả kiểm thử hệ thống — 10/10/2026

Môi trường: frontend Vite `127.0.0.1:18080`, Spring Boot/JDK17 `127.0.0.1:18081`, PostgreSQL17.6 `55439/chapter5_system`, actor test riêng. Chỉ provider LLM là fixture xác định; controller, service, parser, storage, DB và HTTP thật. Không dùng DB ứng dụng5433. Đây là System Test do agent thực hiện, không thay kết quả UAT của hai người.

Bằng chứng mới: `outputs/chapter-5/system-20261010/run3/{summary.json,http-events.json,unit-tests.zip,unit-tests-round2.zip,replay.log,replay-round2.log}`. Mỗi response ngoài auth có raw bytes và SHA256. ZIP tải về được kiểm tra toàn bộ path và đối chiếu byte với `/unit-tests/files`; chính byte tải về được chạy Maven/JUnit. Không lưu token hoặc register body. Run1 bị sandbox chặn socket, được giữ riêng; không tính vào lượt thành công.

| Mã biến thể | Mô tả / dữ liệu | Mong đợi | Thực tế | Kết luận |
|---|---|---|---|---|
| SYS-IMP-01-mini | ZIP PriceService và một existing test | production1class/1method; existing1 riêng | HTTP201→200; đúng số lượng; UI hiển thị1/1/1 | Pass biến thể |
| SYS-GEN-01-mini | Rule100→10, dưới100→0; source `>=100` | Có thể approve trực tiếp rồi sinh Plan | UI và HTTP approve→Plan pending→approve | Pass biến thể |
| SYS-GEN-04-approve-mini | Plan→Case biên100→10 | Duyệt từng tầng mới chuyển tiếp | UI chuyển Plan/Case/Unit; Case pending rồi approve | Pass biến thể duyệt; chưa thử sửa Case |
| SYS-GEN-05-download-mini | ZIP generated boundary test | Byte tải đúng assembledfile; compile/chạy được | ZIP/path/byte đối chiếu đạt; JUnit round1:1test đạt | Pass biến thể |
| FLOW-01-mini | Upload→analyze→BR→Plan→Case→Unit→download→coverage→trace/export | Pipeline lưu và truy vết đúng | HTTP fullflow đạt; UI upload đến Unit và trace BR001→TP001→TC005→atBoundary | Pass HTTP; UI coverage upload chưa xác minh |
| FLOW-02-controlled | Round1 chỉ chạy downloaded boundary100; refine99; round2 chạy toàn bộ | Gap thật; bổ sung case/test, giữ output cũ; coverage tăng | JaCoCo branch50→100%; round2:3test đạt; case/unit vòng1 giữ nguyên; supplemental99→0 | Pass biến thể controlled |
| SEC-01-header-mini | Không header / token sai |401 |401/401 |Pass biến thể |
| SEC-02-read-mini | Actor khác đọc analysis |403 |403 |Pass biến thể |
| SEC-03-user-mini | USER đọc admin list |403 |403 |Pass biến thể |
| SEC-04-db-disabled-mini | Disable actor mới qua isolatedDB, dùng JWT cũ |403 ACCOUNT_DISABLED |403 |Pass biến thể; chưa thử adminUI |
| SEC-05-rejection-smoke | ZIP `../chapter5-safe-sentinel.txt` inert |400 INVALID_SOURCE |400 |Pass HTTP rejection; chưa xác minh sentinel/path tuyệt đối/master |
| SEC-06-http-sentinel | General entity và parameter entity trỏ HTTP listener local inert | Không fetch listener;201/400 tùy valid report |201 cả hai;0external requests |Pass hai network variants; file entity/master chưa đầy đủ |

FLOW02 là kiểm chứng điều phối coverage gap có kiểm soát. Existing test99 đã có trong source context nhưng cố ý không chạy round1, chỉ chạy boundary100 để tạo gap. Round2 khôi phục nguyên byte existing test (SHA256 `cd876ad7e680699675a7b7b50bd9463895d8add489b46a20004f6d343c3eea1e`) và chạy downloaded boundary+gap tests. Suite existing+boundary đầy đủ đã phủ100% trước refinement; không được trình bày50→100 là cải thiện trên toàn bộ original suite hoặc là độ chính xác AI.

Security1/2 giữ nguyên kết quả thô: trước khi sinh Unit, XML bị409 do trạng thái; sau khi đủ trạng thái XML201 trái kỳ vọng test400 ban đầu. Đây là kỳ vọng fixture chưa đúng: JaCoCo cho phép DOCTYPE hợp lệ, bất biến bảo mật cần đo là không đọc external resource. Security3 đo listener thực:0request, dù report201. Không kết luận201 chứng minh lỗ hổng XXE và không sửa parser sản phẩm.

UI artifacts: `ui-analysis-dom.txt`, `ui-analysis.png`, `ui-unit-dom.txt`, `ui-unit.png`, `ui-trace-dom.txt`, `ui-trace.png` trong cùng thư mục ngày10/10. Actor browser riêng, project18. Coverage file chooser không phát event qua Cua ở cả button và file input; ghi hạn chế công cụ, không tự đánh Fail chức năng. UAT P01/P02 vẫn Pending. Các master case nhiều biến thể trong CSV vẫn phải giữ trạng thái bảo thủ; bảng này không xác nhận tất cả43case Pass.

Lệnh tái chạy: dùng Python bundled, `backend/scripts/evaluation/system_checks.py --self-check`; `--output <thư-mục-mới> --skip-measurements`; và `--output <thư-mục-mới> --extra-security`. Runner từ chối thư mục đã tồn tại, lưu terminal failure type/path đã bỏ secret và attempted request UNKNOWN nếu transport lỗi. Không retry vào thư mục chứng cứ cũ.
