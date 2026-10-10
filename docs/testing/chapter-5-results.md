# 5.2 Kết quả kiểm thử GreyTest

Bản tổng hợp bằng chứng ngày 10/10/2026; **chưa phải nghiệm thu toàn bộ chương 5**. Chiến lược 5.1 và 43 TC gốc tại [chapter-5-test-strategy.md](./chapter-5-test-strategy.md), [chapter-5-test-cases.csv](./chapter-5-test-cases.csv). Mã có hậu tố chỉ là biến thể đã chạy, không thay input/kỳ vọng của TC master. Pass chỉ có nghĩa trong phạm vi ghi ở hàng. Not Run/Blocked không phải Fail hoặc Pass.

Baseline: backend 556 invocation = 553 Pass + 3 Skip, 0 Failure/Error; frontend 175 Pass và build đạt. Replay oracle độc lập 3 Pass + 1 Skip. Integration cuối 5 IT + 4 controller = 9 Pass. Các số này thuộc suite/run riêng và không cộng thành số TC hệ thống. 16 lỗi baseline ban đầu vẫn lưu trong defects-initial.csv và được liên kết retest; integration ban đầu 4/9 Fail rồi 1/9 Fail vẫn giữ raw.

Không suy Pass cho toàn bộ UT-01…06 từ full-suite khi fixture/variant master chưa đối chiếu. System/Flow/Security ngày10/10 đã có bằng chứng được review theo biến thể bên dưới; UI coverage/refinement/report vẫn chưa xác minh. UAT chưa có người thực hiện.

CSV audit: [test-results.csv](D:/.KLTN/outputs/chapter-5/report-20261010-v3/test-results.csv).

## Unit

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| UT-01 | Parser production và existing tests | F-ZIP; Java 8–21, src/main/java/src/test/java/target | Chỉ production vào classes/methods/endpoints; existing tests riêng; generated dirs bỏ qua | Full suite chạy đạt nhưng chưa đối chiếu toàn bộ input/kỳ vọng riêng trong catalog. | Not Run |
| UT-02 | Requirement Coverage theo BR approved | 2 BR/1 liên kết; trường hợp denominator=0 | Coverage 50%; code 0 BR trả 100% nhưng metric báo N/A; không suy ra Unit Test đã có | Full suite chạy đạt nhưng chưa đối chiếu toàn bộ input/kỳ vọng riêng trong catalog. | Not Run |
| UT-03 | Parser JaCoCo và counter | LINE missed2 covered8; BRANCH missed1 covered3; <not-report/> | totalLines10/covered8; branches4/3; XML sai IllegalArgumentException | Full suite chạy đạt nhưng chưa đối chiếu toàn bộ input/kỳ vọng riêng trong catalog. | Not Run |
| UT-04 | Liên kết truy vết đúng project | Chuỗi đầy đủ A; B có ID riêng | Matrix chỉ A; ID/link phản ánh parent thật; thiếu từng tầng được ghi riêng | Full suite chạy đạt nhưng chưa đối chiếu toàn bộ input/kỳ vọng riêng trong catalog. | Not Run |
| UT-05 | Giữ oracle sau review/generation | balance100 amount100; BR >=; source > | SOURCE_MISMATCH, suggestion null; không đổi BR/input/expected; replay không chứng minh AI live | Full suite chạy đạt nhưng chưa đối chiếu toàn bộ input/kỳ vọng riêng trong catalog. | Not Run |
| UT-06 | Guard thiếu approve không mất output cũ | BR/Plan/Case có parent pending; unit cũ snapshot | Từ chối generation; không xóa/thay unit cũ; không gọi LLM cho dữ liệu chưa duyệt | Full suite chạy đạt nhưng chưa đối chiếu toàn bộ input/kỳ vọng riêng trong catalog. | Not Run |

## Integration

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| IT-01 | HTTP upload→analyze→manifest/DB | F-ZIP; Authorization runtime; P trả từ upload | 201 UPLOADED owner A; analyze200; manifest/DB đúng ground truth; reload nhất quán | Có biến thể PRICE thực thi bên dưới; chưa chứng minh mọi input/kỳ vọng master. | Not Run |
| IT-02 | HTTP HITL→artifact persistence | M từ analysis; BR F-ORACLE; fixture LLM có SUT/assertion | 202 stage/status/message; terminal COMPLETED; parent links/status đúng; Unit Test giữ approved oracle | Có biến thể PRICE thực thi bên dưới; chưa chứng minh mọi input/kỳ vọng master. | Not Run |
| IT-03 | HTTP coverage→trace→export | F-XML local có methods; servicePath cùng scope | 201; counter/report/history khớp XML; export và trace khớp DB, không lẫn project/scope | Có biến thể PRICE thực thi bên dưới; chưa chứng minh mọi input/kỳ vọng master. | Not Run |
| IT-04 | Cross-owner đọc và sửa artifact | B token runtime; rule A; body UpdateBusinessRuleRequest hợp lệ | 403 không lộ dữ liệu; không sửa artifact; A vẫn truy cập được | Có biến thể PRICE thực thi bên dưới; chưa chứng minh mọi input/kỳ vọng master. | Not Run |
| IT-05 | LLM lỗi và generation trùng lúc | POST /test-plans/generate?resume=false hai request; F-LLM error | Lần đầu202; trùng409 GENERATION_IN_PROGRESS; job FAILED có lỗi; dữ liệu đã lưu theo batch policy, không orphan | Có biến thể PRICE thực thi bên dưới; chưa chứng minh mọi input/kỳ vọng master. | Not Run |
| IT-01-PRICE | ZIP upload and production/existing analysis | PriceService ZIP, 1 production method, 1 existing test | 201/200; exact class/method manifest; existing test separate | 1 class, 1 method, 1 existing test, 0 parse failures; manifest/storage persisted | Pass |
| IT-02-PRICE | Approved BR to Plan/Case/Unit Test | amount100→10 BR; sourceBranch TERNARY-1; staged approvals | unapproved FAILED/no Plan; approved 202→COMPLETED and valid links; generated assertion runs | Missing approval job FAILED with zero Plan; all 3 approved stages COMPLETED; IDs and oracle preserved; JavaCompiler exit0 and reflected JUnit assertion passed; ZIP download200 | Pass |
| IT-03-PRICE | JaCoCo fixtures, history, traceability and export | Two controlled XML counter fixtures: LINE1/1 then0/2 | 50% then100%, previous50%, history2, complete trace and reports | round2 line100%, previous50%, requirement100% on1BR; history2; non-null Unit link; JSON/Markdown export200 containing original rule and generated test | Pass |
| IT-04-PRICE | Authorization/ownership including active pause | owner and second user; absent/bad token; RUNNING fixture job | missing/bad401, cross-owner403; denied reads/mutation/pause preserve state; owner200/PAUSED | Five cross-owner reads403; approval403; pause401/401/403 unchanged RUNNING snapshot/BR; ownerpause200→PAUSED; worker settled | Pass |
| IT-05-PRICE | Duplicate job and provider failure invariants | Blocked LlmClient fixture then nonretryable failure | duplicate/mutation409; first job FAILED; BR approved/no partial Plan | Duplicate generation409; approvalmutation409; jobFAILED; zeroPlan; approved BR unchanged and projectBR_APPROVED | Pass |

## System

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| SYS-IMP-01 | Upload ZIP và xem analysis | multipart file=F-ZIP; không thêm field name giả | 201 UPLOADED; analyze200; classes/methods/endpoints/relations đúng ground truth; test counters riêng | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-IMP-02 | Upload ZIP không hợp lệ | file=no-build.zip; file=broken.zip | Từ chối INVALID_SOURCE/400; UI thông báo lỗi; không project/source mồ côi | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-IMP-03 | Clone GitHub public | POST /api/projects/github {url:<public URL>} | 201 UPLOADED owner A; analyze/manifest đúng checkout actual; ghi commit clone vì API không nhận commit | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-IMP-04 | Multi-module và parse một phần | 2 service cùng tên khác package; một file syntax sai | Identity không nhập nhầm; valid files vẫn analyze; failed files liệt kê; test không tăng production counters | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-GEN-01 | User nhập BR và approve trực tiếp | POST business-rules {methodId:M,description:<BR chuẩn>,sourceBranchId:null} | BR PENDING_REVIEW rồi APPROVED; sinh Plan cho phép; không bắt buộc AI review | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-GEN-02 | AI sinh BR cần review người dùng | POST business-rules/generate?servicePath=S | 202; terminal COMPLETED; BR pending trước approve, M đúng scope; AI output chưa phải ground truth | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-GEN-03 | AI review BR khác source | POST business-rules/review; balance100 amount100 phân biệt | Fixture trả SOURCE_MISMATCH, suggested_description=null; BR không tự đổi; approve vẫn được; live deviation ghi Fail | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-GEN-04 | Review/approve từng tầng và sửa Case | PUT case đủ testType/description/preconditions/testData/expectedResult/priority/traceSource theo strategy | Plan/Case pending trước approve; sửa lưu input100/100 expected0; parent đúng; chưa approve không chuyển sai bước | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-GEN-05 | Download Unit Test và chạy local | GET /unit-tests/download; source >= và >; cùng test hash | ZIP200 đúng package/API; assertion/input giữ oracle; bản >= đạt, > fail nghiệp vụ; compile/setup error không tính phát hiện lỗi | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-GEN-06 | Thiếu approval và lỗi job hiển thị | POST /unit-tests/generate?resume=false; pending Case; F-LLM error | Không sinh theo pending; 202 có thể đi tới FAILED; UI báo lỗi, không coi202 là thành công; output cũ không mất do gate | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-COV-01 | Upload XML coverage hợp lệ | POST coverage/upload multipart file=jacoco.xml; scope S | 201; totals/percent/method gaps khớp XML và scope; report sau reload không đổi | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-COV-02 | XML sai và chưa có report | GET coverage; file=<not-report/>; file XML syntax sai | Không report204; upload sai400; UI lỗi; không thay report hợp lệ trước đó | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-COV-03 | Refinement và history nhiều vòng | POST /coverage/refine?servicePath=S; XML hai vòng | Chỉ gap scope hợp lệ; Case bổ sung approved, unit SUPPLEMENT_EXISTING_TEST; giữ artifact cũ; history đúng từng XML, không bắt buộc tăng | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SYS-COV-04 | Export JSON/Markdown và trace | GET export?format=json; format=markdown; optional scope S | 200 content/file format đúng; BR/Plan/Case/Unit/coverage/link khớp snapshot, không đưa dữ liệu project B | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |

## Workflow

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| FLOW-01 | Sinh mới từ login tới report | Login email/password runtime; upload file; M/S thực; oracle >=; XML local | Toàn chuỗi đúng contract và persistence; approved oracle giữ; local result phân loại; report truy vết đầy đủ | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| FLOW-02 | Existing tests và vòng coverage bổ sung | Source có test; scoped approved BR; gap refinable; hai XML local | Existing context tách production; artifact cũ/source upload giữ; supplemental đúng oracle; history đúng, lý do nếu coverage chưa tăng | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |

## Security

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| SEC-01 | Thiếu/sai/hết hạn token | Không header; token sai signature; expired đúng signature test | 401; không đọc/sửa dữ liệu hoặc pause job trái phép; UI xóa phiên hiện tại phù hợp | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SEC-02 | Cross-owner project và artifact IDs | ID project/rule/case của A; token B; body hợp lệ | 403 tất cả; không lộ output/không sửa state; token A vẫn truy cập | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SEC-03 | USER gọi API admin | Authorization token A rồi Admin T | USER403 không dữ liệu user list; ADMIN thành công đúng quyền | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SEC-04 | Disabled user vẫn giữ JWT cũ | JWT D còn exp nhưng DB status disabled | 403 ACCOUNT_DISABLED; không đọc/sửa project; quyền/trạng thái lấy DB mỗi request | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SEC-05 | ZIP path traversal | F-INVALID traversal ZIP; file multipart | 400 INVALID_SOURCE; không ghi ngoài extract root, không project mồ côi; sentinel hash không đổi | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |
| SEC-06 | XML external entity/DTD | JaCoCo report với SYSTEM URL local và entity file sentinel; không dùng file thật nhạy cảm | Không fetch network/đọc sentinel/đưa sentinel vào output; DOCTYPE JaCoCo thường vẫn parse khi counter hợp lệ; malformed400 | Chờ bằng chứng system mới đã review; không dùng assertion HTTP đơn lẻ thay toàn bộ UI/variants. | Not Run |

## UAT

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| UAT-01 | Tự nhập source và đọc phân tích | F-ZIP hoặc F-EXIST frozen; account runtime riêng | Người dùng tự hoàn thành, phân biệt production/existing; hoàn thành có trợ giúp ghi riêng; không giả định kết quả | Chờ 2 người thực hiện và phiếu ghi nhận thực tế; không ký thay người dùng. | Blocked |
| UAT-02 | Review BR/Plan/Case đúng kỳ vọng | F-ORACLE; input equality100/100 expected0 | Biết bước cần duyệt; không sửa oracle để khớp source sai; hiểu progress/error; ghi trợ giúp và khó khăn | Chờ 2 người thực hiện và phiếu ghi nhận thực tế; không ký thay người dùng. | Blocked |
| UAT-03 | Lấy test về và chạy local | Unit ZIP; local source >= và >; cùng assertion/input | Tìm được output và chạy lệnh; giải thích business fail khác compile/setup fail; observer ghi task success | Chờ 2 người thực hiện và phiếu ghi nhận thực tế; không ký thay người dùng. | Blocked |
| UAT-04 | Đọc coverage/truy vết và xuất report | XML local frozen; report với một gap xác định trước | Tìm được coverage/gap/link/report; nhận xét1–5 có lý do; completion/time/help thật | Chờ 2 người thực hiện và phiếu ghi nhận thực tế; không ký thay người dùng. | Blocked |

## Evaluation

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| AI-01 | Oracle source đúng/sai qua hai mode BR | amount100 balance100; raw + approved + test hash; model/options thật | Cùng test pass >= và business fail >; mismatch giữ BR; raw sai/out-of-domain báo riêng; không suy rộng accuracy | Có pilot equality tự động bên dưới; chưa có HITL con người theo master. | Not Run |
| AI-02 | Pilot và đối chiếu manual độc lập | Source/BR/context đúng khác biệt phương pháp; raw vs HITL snapshots | Raw metric tái tính; compiler/setup/business failures riêng; manual đối chiếu BR/data/expected/trace; pilot chốt batch | Chờ human review oracle, build/replay corpus, baseline runner và 75 lượt độc lập. | Blocked |
| AI-03 | Batch 5–6 dự án, 5 lần mỗi phương pháp | 75–90 lượt; shared scope/model/options; token call logs; UMR snapshot trước unit reset | Req/line/branch/time/token/UMR/stability/trace có denominator/NA/raw; báo thiếu lượt; không đưa synthetic vào corpus | Chờ human review oracle, build/replay corpus, baseline runner và 75 lượt độc lập. | Blocked |
| PERF-01 | API read latency theo tải | Cùng payload/project; timing/error từng request, client/máy/network ghi rõ | Tái tính median/p95/throughput/error rate; báo lỗi và giới hạn; chỉ kết luận đạt nếu threshold chốt trước | Có số liệu sơ bộ Oct9 bên dưới; chưa nghiệm thu tất cả workload master. | Not Run |
| PERF-02 | Analysis theo quy mô source | Scope/file hashes, LOC/classes/methods; synthetic scaling tách corpus AI | Số liệu time/count/parse đúng scope, memoryN/A nếu chưa đo; không suy horizontal scaling | Có số liệu sơ bộ Oct9 bên dưới; chưa nghiệm thu tất cả workload master. | Not Run |
| PERF-03 | Generation job contention với provider fixture | 202 latency và completion wall-clock tách; fixture provider mode | Không job/orphan trái state; đo failed/conflict/queued; không suy thời gian AI thật từ mock load | Có số liệu sơ bộ Oct9 bên dưới; chưa nghiệm thu tất cả workload master. | Not Run |

## Bằng chứng bổ sung đã thực thi

| Mã biến thể | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| AI-01-EQ-LIVE | Oracle equality qua direct/reviewed; harness tự động | TransferService, balance100 amount100, BR >=; source >= và > | Giữ cùng test mỗi đường; >= đạt; > lỗi nghiệp vụ; BR review không đổi oracle | Hai cặp đều đúng kỳ vọng; review SOURCE_MISMATCH, suggestion null; 2 case selected/11 raw; 3 ngoài domain | Pass trong fixture |

[Evidence pilot](D:/.KLTN/outputs/chapter-5/live-oracle-20261009/evidence.md); không phải human HITL, UMR, accuracy toàn domain hoặc batch OSS. Generated test trên source sai Fail là kết quả nghiệp vụ mong đợi; TC đánh giá phát hiện lỗi Pass. Hai hash khác giữa hai đường, cùng hash trong mỗi cặp đúng/sai.

## System/Workflow/Security — biến thể Oct10 đã review

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| SYS-IMP-01-mini | ZIP PriceService và một existing test | PriceService deterministic fixture; details in system results | production1class/1method; existing1 riêng | HTTP201→200; đúng số lượng; UI hiển thị1/1/1; Pass biến thể | Pass |
| SYS-GEN-01-mini | Rule100→10, dưới100→0; source `>=100` | PriceService deterministic fixture; details in system results | Có thể approve trực tiếp rồi sinh Plan | UI và HTTP approve→Plan pending→approve; Pass biến thể | Pass |
| SYS-GEN-04-approve-mini | Plan→Case biên100→10 | PriceService deterministic fixture; details in system results | Duyệt từng tầng mới chuyển tiếp | UI chuyển Plan/Case/Unit; Case pending rồi approve; Pass biến thể duyệt; chưa thử sửa Case | Pass |
| SYS-GEN-05-download-mini | ZIP generated boundary test | PriceService deterministic fixture; details in system results | Byte tải đúng assembledfile; compile/chạy được | ZIP/path/byte đối chiếu đạt; JUnit round1:1test đạt; Pass biến thể | Pass |
| FLOW-01-mini | Upload→analyze→BR→Plan→Case→Unit→download→coverage→trace/export | PriceService deterministic fixture; details in system results | Pipeline lưu và truy vết đúng | HTTP fullflow đạt; UI upload đến Unit và trace BR001→TP001→TC005→atBoundary; Pass HTTP; UI coverage upload chưa xác minh | Pass |
| FLOW-02-controlled | Round1 chỉ chạy downloaded boundary100; refine99; round2 chạy toàn bộ | PriceService deterministic fixture; details in system results | Gap thật; bổ sung case/test, giữ output cũ; coverage tăng | JaCoCo branch50→100%; round2:3test đạt; case/unit vòng1 giữ nguyên; supplemental99→0; Pass biến thể controlled | Pass |
| SEC-01-header-mini | Không header / token sai | Không header / token sai | 401 | 401/401; Pass biến thể | Pass |
| SEC-02-read-mini | Actor khác đọc analysis | Actor khác đọc analysis | 403 | 403; Pass biến thể | Pass |
| SEC-03-user-mini | USER đọc admin list | USER đọc admin list | 403 | 403; Pass biến thể | Pass |
| SEC-04-db-disabled-mini | Disable actor mới qua isolatedDB, dùng JWT cũ | Disable actor mới qua isolatedDB, dùng JWT cũ | 403 ACCOUNT_DISABLED | 403; Pass biến thể; chưa thử adminUI | Pass |
| SEC-05-rejection-smoke | ZIP `../chapter5-safe-sentinel.txt` inert | ZIP `../chapter5-safe-sentinel.txt` inert | 400 INVALID_SOURCE | 400; Pass HTTP rejection; chưa xác minh sentinel/path tuyệt đối/master | Pass |
| SEC-06-http-sentinel | General entity và parameter entity trỏ HTTP listener local inert | General entity và parameter entity trỏ HTTP listener local inert | Không fetch listener;201/400 tùy valid report | 201 cả hai;0external requests; Pass hai network variants; file entity/master chưa đầy đủ | Pass |

Nguồn [system results](./chapter-5-system-results.md), [metadata](D:/.KLTN/outputs/chapter-5/system-20261010/metadata.json). Download ZIP được đối chiếu byte/path rồi chạy chính test tải về. Round1 boundary subset1 test đạt; round2 fullsuite3 test đạt, 0failure/error. Branch50→100 thuộc subset có kiểm soát: original existing+boundary suite đã100% trước refinement. UI chứng minh upload/analyze/approveBR/Plan/Case/Unit và trace; file chooser coverage bị giới hạn công cụ nên UI coverage/refinement/report chưa xác minh. Không tuyên bố master FLOW hoặc mọi SEC Pass.

Security2 giữ kỳ vọng fixture400, actual201: đây là mismatch kỳ vọng DOCTYPE; bảo mật cần đo việc đọc external resource, không xem201 là XXE vulnerability. Security3 hai harmless HTTP entity variants:201 và0listener requests. ZIP traversal chỉ400 rejection smoke, chưa chứng minh sentinel/absolute-path/no orphan của master.

## Corpus AST và golden regression

5 frozenOSS: upload201, analysis/manifest/services200, scope service có methods; tổng1934 productionfiles parsed1934, failed0. Pass là readiness, không phải extraction precision/recall hoặc AI75runs. [Bảng AST](./chapter-5-corpus-analysis.md).

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| UT-GOLDEN-PETCLINIC-20261010 | Golden regression không đổi reference |30Javafiles commitb3ee |86methods và các assertion tiếp theo |62methods, thiếu24accessors; endpoint/relation chưa tới;1Fail0Error0Skip |Fail |

Golden giữ nguyên failure và chờ quyết định chính sách reference/parser; không đổi expected để ép đạt. Các master trong bảng trước vẫn giữ trạng thái bảo thủ khi toàn bộ kỳ vọng chưa được chứng minh; trạng thái CSVv2 có thêm biến thể, không sửa CSV49hàngv1.

## Kiểm chứng cuối và build preflight

Lượt backend offline mới ngày10/10: **563 invocation =560 Pass +3 Skip**,0Failure/Error,125s. Python21Pass gồm14baseline-runner +7collector; ba helper self-check đạt (system lần đầu bị quyền Temp và chỉ helper đó chạy lại với temp riêng, log lỗi giữ nguyên). Frontend sạch, tái sử dụng175Pass/build Oct9; không gọi provider/UAT trong verification. [Verification](D:/.KLTN/outputs/chapter-5/final-validation-20261010/verification.md) giữ333sourcefiles inventory,69XML sanitised và patch tracked changes. Golden riêng1Fail không bị che bởi golden opt-in Skip trong fullsuite.

| Mã TC | Mô tả | Đầu vào | Kỳ vọng | Thực tế | Kết luận |
|---|---|---|---|---|---|
| BUILD-PREFLIGHT-piggymetrics | Selected source/existing tests compile | Frozen commit; runtimeJDK17,target8 | test-compile exit0 |30,145s exit0; chưa chạy tests |Pass preflight |
| BUILD-PREFLIGHT-petclinic-rest | Production/test source compile | Frozen3.4.0,JDK17,target17 |test-compile exit0 |52,187s exit0,OpenAPI/MapStruct theoPOM |Pass preflight |
| BUILD-PREFLIGHT-mall | Selected reactor source compile | Frozencommit,JDK17,target17 |test-compile exit0 |86,135s exit0 |Pass preflight |
| BUILD-PREFLIGHT-shopizer | sm-core reactor compile | Frozencommit,JDK17,target11 |test-compile exit0 |60,469s exit0 |Pass preflight |
| BUILD-PREFLIGHT-realworld | ExactGradle7.4 compile tasks | Frozencommit,target11declared,JDK17 |Verifieddistribution rồicompile |Hai nguồnofficial TLSfailed; chưa compile |Blocked |

[Build logs/targets/provenance](./chapter-5-build-preflight.md). Bốn COMPILE_PASS không chứng minh upstreamtests đã chạy, generatedJUnit5 được verify, oracle đúng hoặc AIbatch thực hiện. FiveOSS đều ready_for_paid_runs=false; humanoracle/manualcomparison và pilot/baselinemethods/75runs còn thiếu. Toàn kế hoạch chưa hoàn thành dù bảng5.1,5.2,5.3 đã có dữ liệu dùng được trong phạm vi hiện tại. CSVv1/v2 giữ nguyên;v3 bổ sung năm preflightvariants.
