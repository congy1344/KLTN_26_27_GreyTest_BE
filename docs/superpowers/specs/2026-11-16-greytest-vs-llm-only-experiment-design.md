# Thiết kế trình chạy thực nghiệm GreyTest vs LLM-only

Ngày: 16/11/2026. Trạng thái: đã duyệt 16/11/2026. Thay cho `backend/scripts/evaluation/baseline_runner.py` và các script trong `outputs/chapter-5/newbee-mall-run-20261106/`; không sửa, không import các file cũ.

## 1. Mục đích

Chương 5 cần số liệu so sánh GreyTest với cách dùng LLM thông thường. Pilot ngày 06/11 trên newbee-mall có 5 điểm lệch làm số liệu khó bảo vệ: token cap khác nhau, prompt LLM-only ghi sai Java, retry thêm giữa chừng, GreyTest bị người can thiệp (resume, nâng quota, gán owner) và đơn vị sinh khác nhau. Trình chạy mới khóa các điểm đó bằng code, ghi mọi tham số vào evidence, để hội đồng tái tính được từng ô.

## 2. Mục tiêu, không làm, phản mục tiêu

**Mục tiêu**
- Chạy một lượt `(project, method, repeat)` hoàn toàn tự động, không người can thiệp.
- Đo hai phương pháp bằng **cùng một đoạn code đo**, trên bản sao source sạch.
- Xuất CSV theo lượt và bảng thống kê theo project × method.

**Không làm**
- Prompt-based baseline (đã loại theo quyết định của người dùng).
- Mutation/PIT (EVALUATION.md đã loại).
- Endpoint, schema DB hay tính năng sản phẩm mới chỉ để phục vụ thực nghiệm.
- Kiểm định thống kê suy luận (p-value) ở giai đoạn pilot.

**Phản mục tiêu** (chạy được vẫn coi là thất bại nếu xảy ra)
- LLM-only nhận bất kỳ thông tin nào ngoài source và lời yêu cầu.
- Sửa test do AI sinh, chạy lại để lấy kết quả đẹp hơn, hoặc bỏ lượt lỗi.
- Ghi coverage 0% thay cho N/A khi không compile; ghi API key/token vào evidence.

## 3. Hai phương pháp

| | LLM-only | GreyTest |
|---|---|---|
| Đầu vào cho AI | Toàn dự án: `pom.xml`, `src/main/java/**`, file cấu hình text trong `src/main/resources` (`.yml .yaml .properties .sql .xml`) + lời yêu cầu viết unit test cho các lớp `@Service` | Cùng bộ file đó (zip không có `src/test`), upload qua API |
| Phạm vi sinh | Các lớp service; prompt không liệt kê tên lớp | Các lớp bộ phân tích GreyTest xếp loại SERVICE |
| Business Rule | Không có | Chế độ AI tự sinh, tự duyệt (không dùng BR chuẩn) |
| Cách sinh | **Một request duy nhất**, chỉ yêu cầu Unit Test | Pipeline sẵn có: phân tích tĩnh → BR → Plan → Case → Unit Test |
| Sửa lỗi nội dung | Không. Output sai/cắt cụt được chấm nguyên trạng | Các cơ chế sửa có sẵn trong sản phẩm (schema repair, sandbox verify) được giữ, vì là một phần của phương pháp |
| Người can thiệp | Không | Không. Runner tự gọi approve và "Tiếp tục sinh" như người bấm nút; số lần resume được ghi |

Lý do chỉ yêu cầu LLM-only sinh Unit Test: mô phỏng cách dev dùng LLM thật; toàn bộ output budget dồn cho test nên là điều kiện tốt nhất của baseline. Plan/Case là phần GreyTest làm thêm, trình bày như lợi thế chức năng.

### Các điều kiện giữ giống nhau
- Cùng provider, URL, model, temperature, đọc từ `backend/.env` (`LLM_OPENAI_URL`, `LLM_MODEL`, `LLM_TEMPERATURE`). Runner kiểm tra backend đang chạy với cùng model (ghi model provider trả về ở cả hai bên).
- Retry **chỉ** khi lỗi truyền tải chưa nhận được output (connection reset, timeout trước byte đầu, HTTP 429/5xx), tối đa 4 attempt, giống `PROVIDER_MAX_ATTEMPTS` của GreyTest. Mọi attempt được ghi.
- Phiên bản Java lấy từ `pom.xml` (`java.version`, `maven.compiler.release/source`) và ghi vào prompt; không ghi cứng.
- Không gửi test có sẵn cho bên nào: upload và prompt đều loại `src/test`, `target`, `.git`.
- Cùng máy, cùng JDK, cùng Maven, cùng lệnh đo; không chạy hai lượt song song.
- Thứ tự chạy các lượt được xáo trộn bằng seed cố định và lưu vào `order.json` (từ main run trở đi).

### Điểm lệch được chấp nhận và công bố
- **Token cap mỗi request khác nhau:** LLM-only một request cần output lớn (`--max-output`, mặc định 64000, ghi vào evidence); GreyTest dùng cap theo bước có sẵn (4096–12288). Ép LLM-only xuống mức của GreyTest sẽ cắt cụt chắc chắn.
- **Số request và token khác nhau** là bản chất phương pháp, được báo cáo như chi phí, không ép bằng nhau.
- Dự án lớn có thể vượt output của một request: ghi `TRUNCATED` (finish_reason = length), vẫn trích các file test hoàn chỉnh và chấm.

## 4. Kiến trúc

Một package nhỏ, chỉ thư viện chuẩn Python, ở `backend/scripts/experiment/`:

```
backend/scripts/experiment/
  experiment.py        # CLI: generate | measure | stats
  test_experiment.py   # unittest cho parse output, JaCoCo/Surefire, thống kê
  README.md            # lệnh chạy, điều kiện tiên quyết
```

`experiment.py` một file là đủ cho quy mô này; chỉ tách thêm khi vượt ~600 dòng.

### 4.1 `generate --project <dir> --method llm-only|greytest --out <run-dir>`
Chung cho cả hai:
1. Tạo `run-dir` (phải chưa tồn tại). Lập `source-manifest.json`: đường dẫn + SHA-256 từng file gửi đi, Java version, kích thước.
2. Ghi `config.json`: model, URL (không key), temperature, max output, timeout, retry policy, commit/hash runner, thời điểm bắt đầu.

LLM-only:
3. Dựng prompt từ template cố định trong code (lưu cả `prompt.txt`): nội dung mọi file trong bộ đầu vào ở mục 3, mỗi file có header đường dẫn; yêu cầu viết JUnit 5 + Mockito unit test cho **tất cả lớp có `@Service`**, trả mỗi file trong một khối có đường dẫn `src/test/java/...`. Không liệt kê tên lớp, không có ví dụ test, không có BR. Bỏ tài nguyên web tĩnh (html, js, css, ảnh, font).
4. Gọi provider một lần (OpenAI-compatible chat completions), dựng body giống `OpenAiLlmClient.buildRequest`: với `gpt-6-luna` dùng `max_completion_tokens` + `reasoning_effort: none`, model khác dùng `max_tokens`. Không bật JSON mode (dev bình thường nhận code dạng text). Không dùng prompt cache. Lưu `response.json` thô, usage (kể cả cached tokens nếu có), finish_reason, wall time.
5. Trích file test vào `tests/` bằng parser khối code. Không sửa nội dung; file không trích được ghi vào `extract-errors.json`.

GreyTest:
3. Kiểm tra `GET` health backend; đăng ký tài khoản mới cho lượt (mật khẩu ngẫu nhiên, không lưu).
4. Upload zip, `analyze`, rồi lần lượt BR → Plan → Case → Unit Test: `generate?resume=true`, poll `generation-progress`, approve khi xong. Resume tối đa `--max-resume` (mặc định 3) khi bước dừng chưa xong mà số item tăng; dừng khi 2 lần liên tiếp không tăng.
5. Lưu output từng bước (JSON), `runMetrics` từng attempt, rồi tải zip Unit Test vào `tests/`. Thời gian tải ghi riêng, không tính vào thời gian sinh.

Kết quả chung: `generation.json` gồm `status` (`OK | TRUNCATED | PARTIAL | FAILED`), số request, retry, resume, input/output/total token, wall time sinh, số file và số test method trích được.

### 4.2 `measure --project <dir> --run <run-dir>`
1. Copy `project` vào `run-dir/sandbox/` (bỏ `target`, `src/test`), chép `tests/` vào `src/test/java`.
2. Chạy **một** lệnh cho cả hai phương pháp:
   `mvn -B -ntp -Dmaven.test.failure.ignore=true org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report`
3. Nếu test-compile lỗi: lấy danh sách file lỗi từ log `[ERROR] /path/X.java`, chuyển các file đó sang `excluded-compile-fail/`, ghi file + dòng lỗi đầu tiên vào `compile-failures.json`, rồi chạy lại. Lặp tới khi compile qua hoặc không còn file test (không giới hạn 5 vòng: javac chỉ báo một phần lỗi mỗi lần, mỗi vòng loại ít nhất một file nên luôn dừng; sửa sau review 16/11). Áp dụng như nhau cho hai bên; không sửa nội dung file. Số file bị loại và lý do là chỉ số được thống kê (người dùng chốt 16/11).
4. Đọc `target/site/jacoco/jacoco.xml` (counter LINE, BRANCH ở cấp report và theo lớp) và `target/surefire-reports/*.xml` (tests, failures, errors, skipped).
5. Ghi `measure.json`: file compile được / tổng, test chạy / pass / fail / error, line và branch covered/missed, log Maven, thời gian đo.

**Phạm vi đo (service scope)** được chốt một lần cho mỗi dự án, độc lập với output AI: các lớp implementation có annotation `@Service` trong `src/main/java` (không tính interface), lưu vào `service-scope.json`. Line/Branch **chính** cộng counter của đúng các lớp này từ `jacoco.xml` (mẫu số gồm cả lớp không được test chạm tới); Line/Branch toàn dự án ghi làm chỉ số phụ. Ở lượt GreyTest, runner đối chiếu `service-scope.json` với danh sách SERVICE của bộ phân tích và ghi chênh lệch vào `generation.json`. Không còn file nào compile được thì Line/Branch = N/A kèm `COMPILE_ERROR`.

### 4.3 `stats --runs <root> --out <dir>`
- `runs.csv`: một dòng mỗi lượt, gộp `generation.json` + `measure.json`.
- `summary.csv`: theo project × method: n, mean, sd, median, min, max cho các chỉ số ở mục 5; sd để trống khi n < 2.
- Stability theo cặp lượt (khi n ≥ 2) và Req. Cov (khi có file mapping) như mục 5.

## 5. Chỉ số

| Chỉ số | Công thức | Ghi chú |
|---|---|---|
| Compile rate | file test compile được / file test trích được | Cùng cách loại file ở 4.2 bước 3 |
| Pass rate | test pass / test thực thi | Kèm số fail, error |
| Line Cov | covered LINE / (covered + missed) trên service scope | JaCoCo; toàn dự án báo phụ |
| Branch Cov | covered BRANCH / (covered + missed) trên service scope | Mẫu số 0: N/A; toàn dự án báo phụ |
| Service được test | lớp trong service scope có ≥1 test class nhắm tới / tổng lớp scope | Phát hiện LLM-only bỏ sót service |
| Req. Cov | BR chuẩn có ≥1 test method kiểm tra / tổng BR chuẩn | Chấm thủ công vào `req-mapping.csv` (`br_id,test_class#method`), cùng rubric cho hai bên. Không có BR chuẩn: N/A |
| Stability | Ghép một-một test method giữa hai lượt theo khóa `lớp được test + tên method chuẩn hóa`, similarity `difflib` > 0,85; mỗi cặp = matched / max(n_i, n_j); trung bình mọi cặp | Ổn định cấu trúc, không phải đúng nghiệp vụ |
| Token | input / output / total, cộng mọi attempt kể cả retry; cached input báo riêng | GreyTest dùng prompt cache với `gpt-6-luna`, LLM-only một request nên không có. Thiếu usage: N/A, không ước lượng |
| Thời gian sinh | wall clock từ lúc gửi request đầu (LLM-only) / bắt đầu analyze (GreyTest) đến khi có output cuối | Không tính tải file, đo coverage |
| Số request, retry, resume | đếm từ log | |
| Traceability, Plan/Case, UMR | chỉ GreyTest | N/A cho LLM-only, trình bày định tính |

## 6. Pilot

- Dự án: `source-test/course-registration` (Spring Boot 3.2.5, Java 17, 28 file Java, 6 service, không có test sẵn). Không phải git repo: đóng băng bằng `source-manifest.json`.
- 1 lượt mỗi phương pháp. Req. Cov và Stability: N/A.
- Mục đích: kiểm tra runner chạy hết, format output, token/time được ghi đúng; không dùng làm kết quả chương 5.
- Evidence: `outputs/chapter-5/experiment-<yyyymmdd-hhmm>/<project>/<method>/run-01/`.

Main run (sau pilot, cần duyệt lại): 6 dự án Maven trong `source-test` × 2 phương pháp × 5 lượt.

## 7. Phương án đã cân nhắc

1. **Script Python độc lập, stdlib** — người dùng chọn. Nhẹ, dễ trình bày; một đoạn `measure` chấm cho cả hai nên công bằng về đo.
2. Đo qua `SandboxTestRunService` của GreyTest — loại: sandbox có cơ chế tự sửa compile riêng, khó chứng minh LLM-only không bị thiệt.
3. Module Java trong backend — loại: thêm code sản phẩm chỉ để làm báo cáo.

## 8. Quyết định đã chốt với người dùng
- Chỉ so GreyTest với LLM-only.
- GreyTest dùng chế độ AI tự sinh BR; BR chuẩn chỉ dùng để chấm sau.
- LLM-only nhận đúng source + lời yêu cầu, một request duy nhất mỗi lượt, chỉ sinh Unit Test; vẫn lặp nhiều lượt độc lập ở main run.
- Bộ chỉ số mục 5.
- Pilot: course-registration, 1 lượt.

## 9. Quyết định do người viết thiết kế đưa ra (cần soát)
- LLM-only nhận toàn dự án (kể cả `pom.xml`, cấu hình text), bỏ tài nguyên web tĩnh.
- `--max-output` 64000 cho LLM-only; cho GreyTest tự resume tối đa 3 lần.
- Service scope = lớp implementation có `@Service`; coverage chính tính trên scope này, toàn dự án báo phụ.
- Stability bằng khóa tên test method thay cho mô tả Case.

## 10. Để lại cho người xây dựng
- Template prompt LLM-only cụ thể (giữ trung tính, không gợi ý kỹ thuật của GreyTest).
- Regex trích khối code và cách tìm file lỗi trong log Maven.
- Tên endpoint chính xác của GreyTest: đối chiếu controller hiện tại, không copy từ script cũ.
- Điều kiện tiên quyết: backend chạy ở `localhost:8080` với cùng `.env`, JDK 17, Maven 3.9.
