# Protocol thực nghiệm chương 5 — preflight v1

Ngày 09/10/2026. Đây là protocol và công cụ offline đã chạy self-check; chưa phải kết quả thực nghiệm 5–6 dự án. Script đặt trong `backend/scripts/evaluation` để được version control cùng backend. Không bổ sung endpoint hoặc schema vào sản phẩm.

## Phạm vi và dataset đã tìm thấy

| Dữ liệu local | Phân loại và provenance | Dùng cho |
| --- | --- | --- |
| `outputs/approved-br-oracle-2026-10-08` | Synthetic TransferService; source/prompt/output/reference requirement/execution đã lưu, model gpt-5.6-luna theo evidence.md | Pilot collector offline, kiểm chứng equality; không tính là dự án OSS |
| `source-spring/greytest-commerce-benchmark` | Benchmark tổng hợp theo README local, Java17/Spring Boot3.2.5/H2 | Pilot/scaling sau xác minh ground truth |
| `source-spring/springboots4test1*` | Fixture local và biến thể; chưa có provenance OSS được xác nhận | Kiểm thử ứng dụng/scaling; không đếm các biến thể thành các OSS độc lập |
| `source-spring/piggymetrics` | README local tham chiếu `sqshq/PiggyMetrics`; chưa đóng băng commit/license/build/scope | Ứng viên corpus, chưa nghiệm thu dataset |

Không download corpus mới trong preflight này. Chưa chốt đủ 5–6 OSS, chưa có bộ BR/test thủ công được người độc lập review. Trước chạy corpus chính phải ghi URL, license thực, commit, scope, build và người review yêu cầu độc lập. Không dùng output AI làm ground truth.

## Cách tính được công bố trước các lượt thực nghiệm mới

- Requirement Coverage: tập ID requirement độc lập đã đóng băng làm mẫu số; mapping Case → requirement được review sau generation. ID không thuộc ground truth bị từ chối. Mẫu số 0 là N/A. Coverage UI trên BR approved báo riêng.
- UMR: số Case AI gốc bị chỉnh ở các trường nội dung / số Case AI gốc. Snapshot phải lấy **trước sinh Unit Test** vì `UnitTestService` reset `isModified`. Dùng `evaluation_id` bất biến để đối chiếu; thêm/xóa/không approve báo riêng. Chỉ ghi UMR nếu có bằng chứng người thực hiện HITL, `human_hitl_verified=true`; fixture/agent approval chỉ ghi `case_edit_audit`, human UMR N/A. Không suy ra chất lượng từ tỷ lệ edit thấp khi nhiều case bị loại.
- Stability v1: chuẩn hóa Unicode NFKC, casefold, lấy các token Unicode `\w+`, nối bằng một space. Python `SequenceMatcher(..., autojunk=False).ratio()` trên description chuẩn hóa, cùng `test_type` chính xác, ngưỡng **>0.85**. Xây đồ thị ứng viên, matching một-một maximum-cardinality bằng augmenting paths; ưu tiên similarity giảm rồi chỉ số tăng để tái lập. Tỷ lệ mỗi cặp = matched/max(n_left,n_right); hai tập rỗng N/A. Với 5 lượt, tính 10 cặp và báo mỗi cặp, trung bình, n; không thay ngưỡng sau xem output. Similarity không chứng minh tính đúng nghiệp vụ.
- Thời gian: `wall_clock_seconds` đo bằng đồng hồ monotonic từ trigger stage tới terminal status. Lưu từng stage machine, HITL và compilation riêng. Không dùng tổng latency call song song làm thời gian stage. Tổng end-to-end phải đo riêng từ đầu đến cuối, không suy ra bằng cộng các stage khi có overlap. Collector không tự đo stage; missing=N/A.
- Token: đọc từng record `calls.jsonl` của `LlmUsageRecorder` đúng runId, lưu original. Phân biệt PROVIDER/ESTIMATED. `expected_calls` phải đếm cả retry/failure tại runner; recorder không đảm bảo ghi mọi lỗi transport. Thiếu usage là N/A total, vẫn báo known subtotals, không dùng 0 thay dữ liệu thiếu. Không tính tiền nếu chưa có đơn giá xác minh.
- Lưu raw prompt/output, post-HITL Case/Plan/BR, pre-Unit snapshot, generated test, compile/test logs, JaCoCo và failure classification. Không loại lượt Fail/Blocked khỏi bảng.

## Collector offline

Runtime đã xác minh: bundled Python trong `C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`. `py -3` không tìm thấy interpreter trên máy. Công cụ chỉ dùng Python standard library.

```powershell
$evaluationPython = 'C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $evaluationPython -m unittest discover -s backend/scripts/evaluation -p 'test_*.py' -v
& $evaluationPython backend/scripts/evaluation/oracle_pilot.py outputs/approved-br-oracle-2026-10-08 outputs/chapter-5/oracle-collector-20261009-v2
& $evaluationPython backend/scripts/evaluation/collect.py <bundle.json> <new-output-directory>
```

`oracle_pilot.py` tạo một bundle thực tế có raw Case chuẩn hóa và approved snapshot từ evidence cũ, rồi gọi collector. Không gọi provider, không compile hoặc chạy lại Java. Kết quả mới chỉ là **import evidence lịch sử**. BE commit trong bundle là môi trường importer, không phải producer lịch sử. Scope chỉ equality dương, không phải toàn bộ chức năng transfer.

`bundle.json` cần metadata: run_id, method, dataset_kind, dataset_id, scope, model, provider_mode, be_commit, fe_commit, status; expected_calls; artifacts (raw_cases, pre_unit_snapshot, requirements bắt buộc; calls/comparison_cases tùy có); covered_requirement_ids; human_hitl_verified; stages; limitations. Case snapshots là array có evaluation_id và các trường snake_case `test_type`, `description`, `preconditions`, `test_data`, `expected_result`, `priority`, `trace_source`, `status`. Requirement array có id và description, provenance. Metadata/artifact phải là dữ liệu public đã lọc secrets trước khi nhập; collector không phải bộ DLP. Không đưa `.env` vào artifacts.

Collector từ chối ghi đè directory đã có; giữ artifact được khai báo với SHA256, results.json và CSV. Lưu `bundle.json` public đã allowlist (metadata, expected_calls, mapping ID, human_hitl_verified, stages, limitations), giữ giá trị dùng tính metric và thay artifact refs sang các bản sao local; SHA256 config nằm trong results.json. Vì vậy có thể replay chỉ từ folder collected, không cần prepared hoặc source gốc. Các trường config ngoài schema bị bỏ; caller vẫn phải lọc secrets trong các trường public/artifact đã khai báo. Nó không tự xác nhận status Pass, provenance hay người review; người thực hiện phải có log chứng minh. `human_hitl_verified` là attestation cần evidence, không phải xác minh tự động.

## Live pilot và baseline tiếp theo

Harness `ApprovedBusinessRuleOracleAcceptanceTest` có live opt-in `-Dgreytest.oracle.live=true`, đọc `.env` nội bộ và giữ source tổng hợp; direct+reviewed cần 7 call khi không lỗi. Dùng model hiện cấu hình, ghi cấu hình public và đo wall-clock thực khi chạy. Harness hiện tạo client với recorder noop nên **không thu usage thật** chỉ bằng set env; phải wiring recorder vào harness hoặc runner trước khi dùng số liệu token. Live mode chọn một equality Case và auto-approve trong fixture: technical oracle test, không có người HITL. Output `target/approved-br-oracle` phải sao lưu ra ngoài target trước lần chạy/clean tiếp theo.

LLM-only / Prompt-based chưa có executable runner được nghiệm thu. Collector `method` chỉ là nhãn metadata, không thực hiện baseline hoặc thay context. Không quảng cáo `--method` là đã có runner. Runner sau phải tái sử dụng `LlmClient` cùng model/options, chỉ source cho LLM-only, source+BR một prompt cho Prompt-based, lưu response/error/usage; compile trên cùng scope và mapping sau generation. Chưa thể thực hiện 75–90 lượt đối chứng hợp lệ khi thiếu corpus, yêu cầu độc lập và người HITL.

## Giới hạn và self-check

7 self-check bao phủ edit/add/remove, ID trùng, token thiếu/estimated, matching một-một với Case trùng và khác type, empty denominator, stage wall-clock khác tổng latency, import fixture thực tế và chống ghi đè; replay folder archived sau xóa prepared; setup failure có frame SUT vẫn bị loại. Importer xác minh failureMethods đúng equality method trong unit-test.response và stack frame đúng method/lambda kèm SUT, ngoài kiểm hash. Fixture import ghi 1 Case chỉnh input trên 8 Case gốc (12.5%), 7 Case không chọn; human UMR/tokens/stability/time N/A. Requirement coverage 1/1 chỉ trong scope equality, không phải độ chính xác AI 100%.
