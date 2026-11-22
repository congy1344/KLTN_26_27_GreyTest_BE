# Trình chạy thực nghiệm GreyTest vs LLM-only

Thiết kế: [spec](../../docs/superpowers/specs/2026-11-16-greytest-vs-llm-only-experiment-design.md). Kế hoạch: [plan](../../docs/superpowers/plans/2026-11-16-greytest-vs-llm-only-experiment.md).

## Điều kiện tiên quyết

- Backend GreyTest chạy ở `http://localhost:8080` với cùng `backend/.env` (`LLM_PROVIDER=openai`, cùng `LLM_MODEL`).
- Hạn mức gọi AI của tài khoản thực nghiệm lấy từ bảng `tier_package` (gói FREE mặc định 100 lượt/tháng), **không** lấy từ `GREYTEST_DEFAULT_MONTHLY_LLM_QUOTA`. Pilot 16/11 dừng ở Test Case sau ~100 đơn vị quota, nên trong thời gian thực nghiệm đặt FREE = 100000 (admin trang Gói, hoặc `update tier_package set quota_limit=100000 where code='FREE'`), ghi vào báo cáo và trả về 100 khi xong.
- JDK 17 ở `C:/Users/ADMIN/tools/jdk-17.0.19+10` (đổi bằng `--java-home`), Maven 3.9.9 trong PATH.
- Python 3.12: `$PY = C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`.

## Lệnh (chạy từ `D:/.KLTN`)

```text
$PY backend/scripts/experiment/experiment.py generate --project source-test/course-registration --method llm-only --out <root>/course-registration/llm-only/run-01
$PY backend/scripts/experiment/experiment.py measure  --project source-test/course-registration --run <root>/course-registration/llm-only/run-01
$PY backend/scripts/experiment/experiment.py stats    --runs <root> --out <root>-stats [--ground-truth rules.json]
```

`--method greytest` cho GreyTest. `generate` từ chối thư mục đã tồn tại; `measure` từ chối nếu đã có `sandbox/`.

Mặc định `generate` và `matrix` chạy mỗi stage BR → TP → TC → UT một lần (`--max-resume 0`).
Backend mặc định `GREYTEST_LLM_BATCH_AUTO_RESUME_ROUNDS=0`; khi dùng backend đã cấu hình khác,
phải đặt lại giá trị này và khởi động lại trước khi đo. UT còn thiếu sẽ được ghi `PARTIAL`, không tự mở lại cả stage.
`--max-resume N` chỉ dùng khi chủ động đo thêm lượt tiếp tục sinh; dừng ngay khi một lượt không tăng số item.
Tham số này điều khiển runner, độc lập với cấu hình auto-resume của backend.
Khi đổi chính sách retry, dùng thư mục `--root` mới cho `matrix`: matrix giữ các lượt đã xong,
nên tiếp tục thư mục cũ có thể trộn chính sách cũ và mới. Nếu cần tiếp tục lượt cũ, truyền lại
`--max-resume` và dùng đúng cấu hình backend của lượt đó; không gộp kết quả khác chính sách.

Một lượt pipeline vẫn gồm nhiều request theo batch và các lần phục hồi output/transport có giới hạn.
Sửa lỗi kỹ thuật sau khi chạy UT tối đa 2 vòng, dừng sớm khi số lỗi kỹ thuật không giảm;
assertion lệch kỳ vọng đã duyệt được báo riêng. Case có `generation_error` xác định bị chặn không gọi AI lại
trong cùng job; job mới đánh giá lại. Lỗi output/provider tạm thời không bị ghi nhớ như case bị chặn.
`ai_wait_seconds` tiếp tục đo hợp các khoảng chờ AI, không tính thời gian Maven/JUnit.

Với JHipster có bước frontend nhưng corpus chỉ gồm Java, đặt
`GREYTEST_SANDBOX_MAVEN_ARGS=-Dskip.npm=true -Dskip.installnodenpm=true` cho backend.
Cờ này được truyền cho cả compile sớm và các lượt chạy test trong sandbox, mặc định để trống.
Phép đo chung cũng dùng cùng cờ bỏ npm; nếu POM đã cấu hình JaCoCo (JHipster 0.8.15),
dùng cấu hình đo native của lượt JHipster cũ, tránh chèn thêm một JaCoCo agent khác phiên bản.
Nếu corpus Java không có `sonar-project.properties` nhưng POM chỉ dùng properties-maven-plugin
để đọc tệp Sonar này, thêm `-Dprop.skipLoadProperties=true` vào cờ sandbox. Cờ này bỏ đọc
cấu hình phân tích Sonar, không sửa Java, test hoặc oracle; không dùng khi plugin nạp các thuộc tính cần cho build.

Unit test của runner: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`

## Một thư mục lượt chạy

| File | Nội dung |
|---|---|
| `config.json`, `source-manifest.json`, `service-scope.json` | Tham số, hash từng file gửi đi, phạm vi đo |
| `prompt.txt`, `response.json`, `attempts.json`, `extract-errors.json` | LLM-only |
| `analysis.json`, `<STAGE>-attempt-N.json`, `<stage>.json`, `greytest-unit-tests.zip` | GreyTest |
| `tests/` | Test do AI sinh, nguyên văn |
| `generation.json` | Trạng thái, request, token, thời gian sinh |
| `maven-round-N.log`, `compile-failures.json`, `excluded-compile-fail/`, `measure.json` | Kết quả đo |
| `req-mapping.csv` (tự tạo khi chấm) | `br_id,test` cho Req. Cov |

## Thời gian chờ AI

`ai_wait_seconds` đo thời gian thực có ít nhất một request AI đang chờ phản hồi. Hai request chạy song song từ cùng một thời điểm và mất 8 giây và 12 giây sẽ cho kết quả 12 giây. Khi sandbox tự xác minh và gọi AI sửa Unit Test, đồng hồ chỉ chạy trong các khoảng chờ AI. Thời gian chạy Maven/JUnit, validate, duyệt, polling và chờ giữa các retry nằm ngoài chỉ số nếu trong khoảng đó không có request AI đang chờ. `measure` chạy các test đã sinh để đo kết quả và coverage.

Runner xuất `ai_wait_seconds` trong `generation.json`, `runs.csv`, `summary.csv` và `compare.csv`. Với GreyTest, hãy khởi động backend từ mã mới để REST API trả `runMetrics.llmWaitMillis`. Dữ liệu cũ hoặc lượt không lấy được đủ số đo sẽ để trống. `generation_seconds` vẫn lưu thời gian đo theo cách cũ để đối chiếu kết quả đã công bố.

Chạy và tổng hợp vào thư mục mới nếu đã có kết quả `ai_wait_seconds` từ phiên bản cộng thời gian từng request. Việc chạy lại `stats` trên kết quả cũ vẫn dùng các số đo đã lưu.

Req. Cov cần file BR chuẩn dạng `{"rules": [...]}` hoặc một list; không có thì để trống. Pilot một lượt không phải kết quả chương 5.
