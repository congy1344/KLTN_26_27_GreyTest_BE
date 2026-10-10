# Chapter 5 — kiểm chứng AST trên corpus đóng băng

Phép đo dùng production API của GreyTest qua HTTP `127.0.0.1:18081`, PostgreSQL native `127.0.0.1:55439` với database riêng `chapter5_system`, JDK 17. Server dùng LLM fixture để kiểm thử hệ thống; các request corpus chỉ đăng ký, upload ZIP, phân tích AST và đọc manifest/service, **không gọi provider AI**. Không build hoặc thực thi source của 5 repository bên ngoài.

Script `backend/scripts/evaluation/analyze_corpus.py` xác minh SHA256 của từng ZIP với `datasets.json` trước upload. Mỗi lần chạy tạo tài khoản riêng; password sinh ngẫu nhiên và token chỉ giữ trong RAM. Response đăng ký không lưu. Dữ liệu raw còn lại, HTTP status/thời gian/hash và summary được lưu trong `outputs/chapter-5/corpus-analysis-20261010`. Project tạo bởi phép đo được giữ để reviewer đối chiếu; không reset database hoặc xóa project của phép đo khác.

Kết quả AST thực được tách khỏi số file/physical line lexical của preflight. Production là `src/main/java`; `src/test/java` được đọc như Existing Test Context riêng. Build/generated directories bị loại theo production parser. Mã generated được kiểm vào `src/main/java` vẫn có thể được parser tính, khác mã nằm trong `target/generated-sources`. Không dùng số source file thay cho class/method/endpoint AST.

`source_tree_commit` là commit thật của source đã đóng băng. `checkout_head` chỉ ghi nhận HEAD checkout và không thay thế provenance: riêng PetClinic golden materialize source commit `b3ee2c53e76e9267f03551a7cd36b0983c859c56` dù HEAD ở commit khác. PetClinic golden là regression fixture, không phải dự án service thứ sáu.

## Tái lập

Sau khi phép đo hiệu năng của server kết thúc, chạy Python bundled:

```powershell
& 'C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' backend/scripts/evaluation/analyze_corpus.py --self-check
& 'C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' backend/scripts/evaluation/analyze_corpus.py --output outputs/chapter-5/corpus-analysis-20261010/<fresh-run-directory>
```

Output directory phải mới để không ghi đè bằng chứng cũ. Server giữ parser production; không thay parser để che failed files. HTTP timeout 300 giây mỗi request; chạy sequential tránh làm nhiễu phép đo tải. Kết quả thiếu/failed ghi nguyên trạng.

Lượt `run1` ngày 10/10 không gửi thành công request HTTP vì sandbox chặn socket local (`WinError10013`); ghi nhận `NOT_EXECUTED`, không tính là hệ thống Fail. Lượt `run2` chạy sau khi escalation kết nối local được duyệt. `Pass` của script chỉ nghĩa là upload/analyze/manifest/services có đúng HTTP status, scope service được nhận diện có methods và không failed parse; không có ground truth cho toàn bộ phần tử của năm dự án nên không suy ra extraction precision/recall hoặc độ đầy đủ endpoint.

Script hiện dừng sau lỗi transport/OSError và ghi `server_completion=UNKNOWN`, tránh tạo workload tiếp theo khi request trước có thể vẫn đang chạy. Self-check dùng hai dataset giả và timeout giả xác minh dataset thứ hai không được gửi. Sửa này được thêm trong khi `run2` đang chạy phiên bản cũ; `run2` không có timeout, cả năm request analysis hoàn tất trước 300 giây.

## Kết quả API thực ngày 10/10/2026

| Dataset / project ID | Production files / parsed / failed | Classes | Methods | Endpoints | Existing test files | Analyze HTTP duration (s) | Kết luận readiness |
|---|---|---:|---:|---:|---:|---:|---|
| PiggyMetrics / 13 | 72 / 72 / 0 | 74 | 113 | 11 | 25 | 5.657 | Pass |
| PetClinic REST / 14 | 83 / 83 / 0 | 81 | 293 | 1 | 18 | 6.175 | Pass |
| RealWorld / 15 | 93 / 93 / 0 | 96 | 214 | 19 | 23 | 5.666 | Pass |
| mall / 16 | 519 / 519 / 0 | 747 | 11006 | 246 | 4 | 176.820 | Pass |
| Shopizer / 17 | 1167 / 1167 / 0 | 1187 | 3630 | 322 | 37 | 98.222 | Pass |

Cả năm có upload HTTP201, analyze HTTP200, manifest/services HTTP200; scope service đã chọn nhận diện được và có methods. Không gọi provider. Bằng chứng nguyên bản: `run2/{id}-upload.json`, `{id}-analysis.json`, `{id}-manifest.json`, `{id}-services.json`, `summary.json`, `http-events.json`. Inventory SHA-256 toàn bộ artifact ở `artifact-inventory.json`.

Các duration gồm production parsing, lưu PostgreSQL và trả response; mỗi dự án chỉ chạy một lần, máy có workload kiểm thử hệ thống cùng kỳ. Thời gian không tăng đơn điệu theo số file: method count, relation resolution và persistence cũng khác nhau. Không coi đây là benchmark scalability có lặp hay dự đoán SLA. PetClinic REST chỉ trích 1 endpoint trong snapshot đã stage; parse đủ file không chứng minh endpoint extraction đầy đủ (API có phần contract/generated code), cần ground truth riêng để đánh giá precision/recall.

Golden verification chạy đúng test có sẵn, sau khi được cấp Maven mutex:

```powershell
$env:JAVA_HOME = 'C:/Users/ADMIN/tools/jdk-17.0.19+10'
& 'C:/Users/ADMIN/tools/apache-maven-3.9.9/bin/mvn.cmd' -o '-Dtest=PetClinicGoldenManifestTest' '-Dpetclinic.path=D:/.KLTN/outputs/chapter-5/corpus-analysis-20261010/golden-source' test
```

Chạy trong `backend`; không cần PostgreSQL/LLM cho golden test. Archive log và đúng XML Surefire của lượt chạy để phân biệt actual Pass/Fail với skipped mặc định.

Ngày 10/10, folder golden cũ không còn production Java hiện diện. Phép đo materialize 30 Git blob Java trong `src/main/java` từ commit b3ee vào folder bằng chứng mới `golden-source`, giữ SHA-256 từng file trong `golden-source-inventory.json`. Không sửa folder corpus cũ; không build hoặc thực thi project PetClinic.

## Golden regression thực ngày 10/10/2026

`PetClinicGoldenManifestTest` chạy 1 test, 1 failure, 0 errors, 0 skipped; Maven exit 1. Scanner đọc 30 production Java file, parse đủ 30, không failed parse. Assertion classes đạt; assertion methods thất bại: actual 62, expected 86, thiếu 24 getter/setter thuần, không method bất ngờ. Endpoint/relation assertions chưa được chạy vì assertion methods dừng test.

Production `JavaParserHelper.extractMethods` hiện chủ động loại trivial accessors bằng `isTrivialAccessor`; golden JSON vẫn kỳ vọng các accessor này. Chưa thay parser hoặc expected để đổi Fail thành Pass. Hai hướng xử lý là giữ failure và rà soát chính sách ground truth, hoặc cập nhật golden sau khi xác nhận chính sách loại accessor được chấp nhận. Bằng chứng: `golden-maven.log`, `golden-surefire.xml`, `golden-surefire.txt`, `golden-summary.json` và inventory source trong folder `corpus-analysis-20261010`.

## Giới hạn đánh giá

AST readiness không chứng minh oracle đúng, generated tests compile/pass, Requirement Coverage hoặc hiệu quả AI. `ready_for_paid_runs` vẫn false cho đến khi oracle được con người review và build/replay phù hợp được kiểm chứng. Thời gian parser của corpus là một lượt sau fixture workload, không được diễn giải thành benchmark tải có lặp.
