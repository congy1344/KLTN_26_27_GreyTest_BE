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

Req. Cov cần file BR chuẩn dạng `{"rules": [...]}` hoặc một list; không có thì để trống. Pilot một lượt không phải kết quả chương 5.
