# Runner baseline một lần gọi — chương 5

Ngày 09/10/2026. `scripts/evaluation/baseline_runner.py` chuẩn bị hoặc thực hiện đúng một Chat Completions request cho hai baseline. Không thêm API/schema vào GreyTest. Python standard library; không SDK mới. Request sao theo `OpenAiLlmClient.requestBody`: một user message, model, temperature, max_tokens và response_format json_object khi cấu hình bật. Reader lấy choices[0].message.content hoặc output_text. URL/model/options hiện cấu hình chỉ được đọc khi có `--live`; không đổi model hoặc tự tăng token limit.

| Method CLI | Context gửi provider | Quy trình |
| --- | --- | --- |
| `llm-only` | Tên file + nguyên văn production Java trong whitelist | Một prompt sinh Case và code JUnit5/Mockito đồng thời; không BR, AST, existing test, HITL, retry hay repair |
| `prompt-based` | Cùng source whitelist + id/description của BR độc lập đã duyệt | Một prompt sinh Case và code đồng thời; không pipeline GreyTest, HITL, retry hay repair |

BR/ground truth dùng đánh giá LLM-only sau sinh phải độc lập; runner không đưa oracle này vào prompt. BR Prompt-based phải cùng file với evaluation oracle khi chạy thật. GreyTest đối chứng dùng server/pipeline production hiện có; runner này không hiện thực lại pipeline.

## Source snapshot

Chuẩn bị JSON public sau khi chọn service và các dependency production cần thiết. Không quét/gửi toàn repository. `files` là whitelist cụ thể đã chốt trước các phương pháp/lần chạy; mỗi file có path tương đối và SHA256 khớp inventory của dataset. Bắt buộc có analysis_scope; không có path trùng. Từ chối `.env`, `.git`, hidden directory, test source, path tuyệt đối, traversal, đường dẫn thoát checkout. Đối chiếu HEAD, Git blob tại commit, inventory, scope hash và hash snapshot trước tạo output/gọi provider.

```json
{
  "dataset_id": "realworld",
  "commit": "<exact frozen commit from datasets.json>",
  "analysis_scope": "src/main/java/io/spring/application/TagsQueryService.java",
  "files": [
    {"path": "src/main/java/io/spring/application/TagsQueryService.java", "sha256": "<inventory sha256>"}
  ],
  "build": {"status": "PASS", "evidence_file": "build.log", "evidence_sha256": "<hash of actual frozen build log>"}
}
```

Đây là mẫu schema, không phải xác nhận build PASS; thiếu dependency vẫn có thể dry-run nhưng chưa đủ để compile. `build` chỉ bắt buộc khi live. SHA256 trong inventory/snapshot là hash raw bytes materialized; comparison RAW_BYTES hoặc CRLF_TO_LF_NORMALIZED chỉ áp dụng lúc đối chiếu Git blob. Raw source text được gửi nguyên văn. Hash source-snapshot tính trên bytes JSON gốc (gồm build khi có); thay file snapshot phải cập nhật attestation oracle. Prompt artifact chứa đủ source đã gửi để kiểm toán.

## Approved independent oracle

`--approved-br` chỉ cho Prompt-based. File JSON cần:

- `dataset_id`, `commit`, `analysis_scope`, `source_snapshot_sha256` khớp source snapshot.
- `human_review_verified: true`, `reviewer`, `reviewed_at`, `independent_source` có dữ liệu thực.
- `review_evidence_file` tương đối với oracle và `review_evidence_sha256` khớp biên bản review thực.
- `rules`: array không rỗng, mỗi item có id duy nhất và description. Chỉ hai trường này được đưa vào prompt.

Không đặt `human_review_verified=true` thay người review. Đây là attestation được người thực hiện chịu trách nhiệm; hash kiểm toàn vẹn/binding, không tự chứng minh người đó đã review. BR draft trong tài liệu dataset không đáp ứng gate.

Live cả hai baseline cần `--evaluation-oracle`, có cùng approval/binding kể trên. Prompt-based dùng chính file approved-br làm evaluation-oracle để tránh hai bộ kỳ vọng khác nhau. Chạy thật còn cần dataset `ready_for_paid_runs=true`, `build_status=PASS`, `oracle_status=HUMAN_REVIEW_VERIFIED` và build evidence hash trong snapshot. Các dataset đang preflight chưa đạt gate; không gọi paid batch bằng cách sửa nhãn khi chưa có bằng chứng.

## Lệnh

Từ `D:/.KLTN`, dùng bundled Python. Output bắt buộc là directory mới:

```powershell
$evaluationPython = 'C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $evaluationPython -m unittest discover -s backend/scripts/evaluation -p test_baseline_runner.py -v
& $evaluationPython backend/scripts/evaluation/baseline_runner.py --dataset realworld --method llm-only --source-snapshot <public-snapshot.json> --output <new-dry-run-directory>
& $evaluationPython backend/scripts/evaluation/baseline_runner.py --dataset realworld --method prompt-based --source-snapshot <public-snapshot.json> --approved-br <approved-oracle.json> --output <another-new-dry-run-directory>
# Chỉ sau build/review gates: thêm --live --evaluation-oracle <approved-oracle.json>.
```

Default dry-run không đọc `.env` hay gọi network, ghi model/options N/A và actual_calls=0. Không dùng dry-run làm số liệu thực nghiệm. Live chỉ hỗ trợ provider=openai tương thích HTTP của backend; HTTPS endpoint không credential/query/fragment; không theo redirect mang Authorization. Env process override backend/.env theo các key allowlist. Artifact không chứa key, header Authorization hoặc endpoint plaintext. `.env` không sao lưu vào output.

## Evidence và giới hạn

`run.json` lưu method, dataset/commit/scope, source/prompt/options/response/oracle hashes, configured model, model provider trả về nếu có, finish_reason, actual_calls/expected_calls, monotonic wall clock và usage. `prompt.txt`, source snapshot public, `response.raw` và generated.json (khi schema hợp lệ) được lưu; raw HTTP/error/malformed output không bị loại. Nếu provider echo key đang cấu hình, runner thay key bằng [REDACTED] và ghi response_redacted=true; hash response là hash bytes sau redaction. Không ghi request headers hoặc exception text. Lượt transport error giữ raw rỗng + TRANSPORT_ERROR; HTTP error giữ body + HTTP_ERROR; JSON/content/schema sai giữ MALFORMED_OUTPUT. Missing counter ghi null (N/A), không 0; có một counter hợp lệ thì source=PROVIDER, các counter thiếu vẫn null. Không estimated tokens.

GENERATED_UNVALIDATED không phải Pass: runner không compile, chạy Java, tính coverage, mapping requirement hoặc nghiệm thu assertion. Generated JSON phải được kiểm tra/chạy trên cùng frozen scope trong môi trường kiểm soát, lưu compile/test/JaCoCo riêng rồi đưa vào collector. Không chạy generated code tự động. CLI exit 1 với lỗi provider/output, exit 2 preflight, exit 0 dry-run hoặc generation chưa được kiểm chứng. Directory có sẵn luôn bị từ chối, không overwrite.

14 offline self-check kiểm BR exclusion/inclusion, approval/binding, source guard/hash/CRLF, gate trước đọc config, fresh directory, call count=1, raw success/error/malformed retention, secret echo redaction, partial/missing tokens N/A và generation chưa Pass. Fixture approval/build trong test được đánh dấu SELF_CHECK, không phải kết quả người dùng hay corpus. Không paid call được thực hiện khi phát triển runner.
