# Trình chạy thực nghiệm GreyTest vs LLM-only — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Một CLI Python (`generate | measure | stats`) chạy GreyTest và LLM-only trên cùng dự án, đo bằng cùng một đoạn code, xuất CSV thống kê.

**Architecture:** Một file `experiment.py` chỉ dùng thư viện chuẩn. `generate` ghi mọi thứ vào một thư mục lượt chạy (`run-dir`); `measure` đọc `run-dir/tests/`, chạy Maven + JaCoCo trong sandbox; `stats` đọc mọi `run-dir` dưới một thư mục gốc. Các hàm thuần (parse, prompt, thống kê) được unit test; luồng HTTP/Maven được kiểm chứng bằng pilot thật ở Task 6.

**Tech Stack:** Python 3.12 stdlib (`urllib`, `json`, `zipfile`, `xml.etree`, `difflib`, `statistics`, `unittest`), Maven 3.9.9, JDK 17, JaCoCo 0.8.12.

**Spec:** `backend/docs/superpowers/specs/2026-11-16-greytest-vs-llm-only-experiment-design.md`

## Global Constraints

- Chỉ thư viện chuẩn Python; không import gì từ `backend/scripts/evaluation/` hay `outputs/chapter-5/newbee-mall-run-20261106/`.
- Python: `C:/Users/ADMIN/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe` (máy không có `python` trong PATH). Trong plan gọi tắt là `$PY`.
- JDK đo: `C:/Users/ADMIN/tools/jdk-17.0.19+10` (mặc định `--java-home`; JDK mặc định của máy là 23, không dùng).
- Provider đọc từ `backend/.env`: `LLM_PROVIDER` phải là `openai`, dùng `LLM_OPENAI_URL`, `LLM_MODEL`, `LLM_TEMPERATURE` (mặc định 0.3), `LLM_API_KEY`. Không ghi key, Authorization header hay mật khẩu vào bất kỳ file evidence nào.
- LLM-only: đúng **một** request nội dung; `--max-output` mặc định 64000; timeout mặc định 900 giây; không JSON mode; không prompt cache.
- Retry chỉ khi lỗi truyền tải chưa có output (URLError/ConnectionError/timeout, HTTP 429, 5xx), tối đa 4 attempt, ghi mọi attempt.
- GreyTest: `--max-resume` 3, dừng khi 2 lần liên tiếp không tăng số item; base URL mặc định `http://localhost:8080`.
- Đo: `mvn -B -ntp -Dmaven.test.failure.ignore=true org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report`; tối đa 5 vòng loại file không compile; không sửa nội dung file test.
- Bộ file đầu vào: `pom.xml`, `src/main/java/**/*.java`, `src/main/resources/**` có đuôi `.yml .yaml .properties .sql .xml`. Bỏ `src/test`, `target`, `.git`, mọi thư mục bắt đầu bằng `.`.
- Service scope: lớp (không phải interface) có annotation `@Service` trong `src/main/java`.
- Stability: similarity `difflib.SequenceMatcher` > 0.85.
- Không thêm endpoint, schema DB hay code sản phẩm. Comment logic quan trọng bằng tiếng Việt; tên hàm/biến tiếng Anh; docstring đầu module nêu trách nhiệm.
- Commit không có trailer `Co-Authored-By: Claude`. Chỉ commit sau khi người dùng đồng ý; `git add` đúng các file của plan (repo `backend` đang có thay đổi dở không liên quan).

## Review Focus

1. **Source có BOM/CRLF hoặc không phải UTF-8** → đọc bằng `utf-8-sig`, lỗi decode thì đọc `errors="replace"` và ghi cảnh báo vào manifest, không crash. Test ở Task 1.
2. **LLM trả khối code không có đường dẫn, hoặc trùng đường dẫn** → suy đường dẫn từ `package` + `public class`; trùng thì giữ khối sau và ghi vào `extract-errors.json`. Test ở Task 2.
3. **Response bị cắt cụt (finish_reason = length) với khối code cuối chưa đóng** → bỏ khối dở, status `TRUNCATED`, vẫn giữ các file hoàn chỉnh. Test ở Task 2.
4. **Tất cả file test đều không compile** → dừng vòng lặp, Line/Branch = None, `status = "COMPILE_ERROR"`, không ném lỗi. Test ở Task 4.
5. **`run-dir` đã tồn tại** → `generate` từ chối (không ghi đè evidence cũ); `measure` từ chối nếu `run-dir/sandbox` đã tồn tại. Test ở Task 1 và Task 4.

---

### Task 1: Thu thập source, service scope, manifest

**Files:**
- Create: `backend/scripts/experiment/experiment.py`
- Test: `backend/scripts/experiment/test_experiment.py`

**Interfaces:**
- Produces:
  - `collect_sources(project: Path) -> list[dict]` — mỗi phần tử `{"path": str (posix, tương đối), "sha256": str, "text": str, "warning": str | None}`, sắp xếp theo `path`.
  - `java_version(pom_text: str) -> str | None` — ưu tiên `maven.compiler.release`, rồi `java.version`, rồi `maven.compiler.source`.
  - `service_scope(sources: list[dict]) -> list[str]` — tên lớp đầy đủ (`package.Class`) có `@Service`, bỏ interface.
  - `new_run_dir(path: Path) -> Path` — tạo thư mục, ném `FileExistsError` nếu đã có.
  - `write_json(path: Path, value) -> None` (UTF-8, `ensure_ascii=False`, indent 1).

- [ ] **Step 1: Viết test thất bại**

Tạo project giả trong `tempfile.TemporaryDirectory()`: `pom.xml` có `<java.version>17</java.version>`, `src/main/java/a/OrderService.java` (`package a; @Service public class OrderService {}`), `src/main/java/a/Repo.java` (`@Service public interface Repo {}`), `src/main/resources/application.yml`, `src/main/resources/static/app.js`, `src/test/java/a/XTest.java`, `target/x.java`, file BOM `src/main/java/a/Bom.java` bắt đầu bằng `\ufeff`.

```python
def test_collect_sources_filters_and_hashes(self):
    paths = [s["path"] for s in collect_sources(root)]
    self.assertEqual(paths, ["pom.xml", "src/main/java/a/Bom.java", "src/main/java/a/OrderService.java",
                             "src/main/java/a/Repo.java", "src/main/resources/application.yml"])
    self.assertFalse(collect_sources(root)[1]["text"].startswith("\ufeff"))

def test_java_version_prefers_release(self):
    self.assertEqual(java_version("<java.version>17</java.version>"), "17")
    self.assertEqual(java_version("<maven.compiler.release>21</maven.compiler.release><java.version>17</java.version>"), "21")
    self.assertIsNone(java_version("<project/>"))

def test_service_scope_skips_interfaces(self):
    self.assertEqual(service_scope(collect_sources(root)), ["a.OrderService"])

def test_new_run_dir_refuses_existing(self):
    new_run_dir(root / "run")
    with self.assertRaises(FileExistsError):
        new_run_dir(root / "run")
```

- [ ] **Step 2: Chạy test, xác nhận FAIL**

Run: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`
Expected: FAIL / ImportError `collect_sources`.

- [ ] **Step 3: Cài đặt các hàm trên trong `experiment.py`**

`service_scope` dùng regex trên text: `package (\S+);` và `@Service\b[^{;]*?\b(class)\s+(\w+)` (annotation có thể có tham số, giữa annotation và `class` có thể có annotation/modifier khác); khớp `interface` thì bỏ.

- [ ] **Step 4: Chạy test, xác nhận PASS**

Run: như Step 2. Expected: 4 test OK.

---

### Task 2: LLM-only — prompt, request, trích file test, gọi có retry

**Files:**
- Modify: `backend/scripts/experiment/experiment.py`
- Test: `backend/scripts/experiment/test_experiment.py`

**Interfaces:**
- Consumes: `collect_sources`, `java_version` (Task 1).
- Produces:
  - `load_env(path: Path) -> dict[str, str]` — đọc `KEY=VALUE`, bỏ dòng `#`/rỗng, bỏ ngoặc kép bao quanh.
  - `build_prompt(sources: list[dict], java: str | None) -> str`.
  - `request_body(prompt: str, model: str, temperature: float, max_output: int) -> dict`.
  - `extract_test_files(text: str) -> tuple[dict[str, str], list[str]]` — `{đường dẫn src/test/java/...: code}`, danh sách lỗi.
  - `post_with_retry(url: str, body: dict, headers: dict, timeout: int, opener=urllib.request.urlopen, attempts: int = 4) -> tuple[dict, list[dict]]` — trả JSON response và log attempt `{"attempt", "error" | None, "seconds"}`; hết attempt thì ném `RuntimeError` kèm log.
  - `generate_llm_only(project: Path, run_dir: Path, env: dict, max_output: int, timeout: int) -> dict` — trả `generation` dict (xem Task 3 về khóa chung).

- [ ] **Step 1: Viết test thất bại**

```python
def test_prompt_contains_sources_and_java_but_no_test_hints(self):
    p = build_prompt([{"path": "pom.xml", "text": "<p/>"}, {"path": "src/main/java/a/S.java", "text": "class S{}"}], "17")
    self.assertIn("### FILE: src/main/java/a/S.java", p)
    self.assertIn("Java 17", p)
    self.assertIn("@Service", p)
    for leaked in ("Business Rule", "Test Plan", "Test Case", "GreyTest"):
        self.assertNotIn(leaked, p)

def test_request_body_luna_uses_completion_tokens(self):
    b = request_body("x", "gpt-6-luna", 0.3, 64000)
    self.assertEqual((b["max_completion_tokens"], b["reasoning_effort"], b["temperature"]), (64000, "none", 0.3))
    self.assertNotIn("response_format", b)
    self.assertEqual(request_body("x", "gpt-4o-mini", 0.3, 100)["max_tokens"], 100)

def test_extract_uses_path_header_or_package(self):
    text = ("```java\n// src/test/java/a/STest.java\npackage a;\nclass STest {}\n```\n"
            "```java\npackage b;\npublic class TTest {}\n```\n")
    files, errors = extract_test_files(text)
    self.assertEqual(sorted(files), ["src/test/java/a/STest.java", "src/test/java/b/TTest.java"])
    self.assertEqual(errors, [])

def test_extract_drops_unclosed_last_block(self):
    files, errors = extract_test_files("```java\npackage a;\nclass ATest {}\n```\n```java\npackage a;\nclass BTe")
    self.assertEqual(list(files), ["src/test/java/a/ATest.java"])
    self.assertEqual(len(errors), 1)

def test_extract_duplicate_path_keeps_last(self):
    block = "```java\npackage a;\nclass ATest { int v = %d; }\n```\n"
    files, errors = extract_test_files(block % 1 + block % 2)
    self.assertIn("v = 2", files["src/test/java/a/ATest.java"])
    self.assertEqual(len(errors), 1)

def test_retry_only_on_transport_errors(self):
    calls = []
    def flaky(request, timeout):
        calls.append(1)
        if len(calls) < 3: raise urllib.error.URLError("reset")
        return io.BytesIO(b'{"ok": 1}')
    response, log = post_with_retry("http://x", {}, {}, 5, opener=flaky)
    self.assertEqual((response, len(log)), ({"ok": 1}, 3))

def test_retry_does_not_repeat_http_400(self):
    def bad(request, timeout):
        raise urllib.error.HTTPError("http://x", 400, "bad", {}, io.BytesIO(b"{}"))
    with self.assertRaises(RuntimeError):
        post_with_retry("http://x", {}, {}, 5, opener=bad)
```

- [ ] **Step 2: Chạy test, xác nhận FAIL**

Run: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`
Expected: FAIL / ImportError `build_prompt`.

- [ ] **Step 3: Cài đặt**

Prompt cố định (tiếng Anh, trung tính, đúng cách một dev nhờ LLM), giữ nguyên văn để tái lập:

```text
You are given the full source of a Java Spring Boot project.
Write JUnit 5 unit tests with Mockito for every class annotated with @Service in this project.
Target Java {java}. Use only dependencies already declared in pom.xml.
Return each test file as a separate ```java code block whose first line is a comment with its path, e.g. // src/test/java/com/example/FooServiceTest.java

### FILE: <path>
<content>
...
```

Dòng `Target Java {java}.` bỏ hẳn khi `java` là None. `extract_test_files`: tìm khối bằng regex ```` ```(?:java)?\n(.*?)``` ```` (DOTALL); khối mở cuối không đóng → lỗi `"unclosed block"`. Đường dẫn: comment dòng đầu chứa `src/test/java/...java`; không có thì `package` + tên `class` đầu tiên; không suy được thì lỗi và bỏ khối. `post_with_retry` coi `HTTPError` 429/≥500, `URLError`, `TimeoutError`, `ConnectionError` là lỗi truyền tải; HTTPError khác ném ngay.

`generate_llm_only`: ghi `prompt.txt`, `response.json` (thô), `tests/<path>` cho từng file, `extract-errors.json`; status `TRUNCATED` nếu `finish_reason == "length"`, `FAILED` nếu hết retry (vẫn ghi attempts), ngược lại `OK`. Usage lấy `usage.prompt_tokens`, `usage.completion_tokens`, `usage.total_tokens`, `usage.prompt_tokens_details.cached_tokens` (thiếu → None).

- [ ] **Step 4: Chạy test, xác nhận PASS**

Run: như Step 2. Expected: toàn bộ test Task 1–2 OK.

---

### Task 3: GreyTest qua API và khung `generate`

**Files:**
- Modify: `backend/scripts/experiment/experiment.py`
- Test: `backend/scripts/experiment/test_experiment.py`

**Interfaces:**
- Consumes: `collect_sources`, `service_scope`, `new_run_dir`, `write_json` (Task 1); `generate_llm_only`, `load_env`, `post_with_retry` (Task 2).
- Produces:
  - `source_zip(sources: list[dict]) -> bytes` — zip đúng bộ file đầu vào (cùng bộ với LLM-only).
  - `should_resume(item_counts: list[int], status: str, resumes_used: int, max_resume: int) -> bool` — False khi status `COMPLETED`; False khi `resumes_used >= max_resume`; False khi 2 phần tử cuối không tăng so với phần tử trước nó (`counts[-1] <= counts[-2]` và `counts[-2] <= counts[-3]`); còn lại True.
  - `extract_zip_tests(data: bytes, dest: Path) -> list[str]` — chỉ entry `.java`; giữ phần từ `src/test/java/` trở đi, nếu không có thì đặt theo `package` + tên file; trả danh sách đường dẫn đã ghi.
  - `generate_greytest(project: Path, run_dir: Path, base_url: str, max_resume: int) -> dict`.
  - `cmd_generate(args) -> int` — khung chung.
  - Khóa chung của `generation.json`: `method, project, status ("OK"|"TRUNCATED"|"PARTIAL"|"FAILED"), requests, retries, resumes, input_tokens, output_tokens, total_tokens, cached_tokens, generation_seconds, test_files, test_methods, model, scope_mismatch (chỉ GreyTest, list | None)`.

- [ ] **Step 1: Viết test thất bại**

```python
def test_should_resume_rules(self):
    self.assertFalse(should_resume([5], "COMPLETED", 0, 3))
    self.assertTrue(should_resume([5], "PARTIAL", 0, 3))
    self.assertFalse(should_resume([5, 8], "PARTIAL", 3, 3))
    self.assertFalse(should_resume([5, 5, 5], "PARTIAL", 2, 3))
    self.assertTrue(should_resume([5, 5, 6], "PARTIAL", 2, 5))

def test_extract_zip_tests_keeps_only_java_under_test_root(self):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("project/src/test/java/a/STest.java", "package a; class STest {}")
        z.writestr("TTest.java", "package b; class TTest {}")
        z.writestr("run-greytest-coverage.sh", "echo")
    written = extract_zip_tests(buf.getvalue(), root / "tests")
    self.assertEqual(sorted(written), ["src/test/java/a/STest.java", "src/test/java/b/TTest.java"])

def test_source_zip_matches_collected_sources(self):
    names = zipfile.ZipFile(io.BytesIO(source_zip(collect_sources(root)))).namelist()
    self.assertEqual(sorted(names), [s["path"] for s in collect_sources(root)])
```

- [ ] **Step 2: Chạy test, xác nhận FAIL**

Run: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`
Expected: FAIL / ImportError `should_resume`.

- [ ] **Step 3: Cài đặt**

Endpoint (đã đối chiếu controller ngày 16/11):

| Việc | Request |
|---|---|
| Health | `GET /api/ping` |
| Tài khoản mới mỗi lượt | `POST /api/auth/register` `{email: "exp-<uuid>@evaluation.test", password: secrets.token_urlsafe(24), fullName: "Experiment run"}` → `token`; header `Authorization: Bearer <token>` |
| Upload | `POST /api/projects/upload` multipart field `file` → 201, `id` |
| Phân tích | `POST /api/projects/{id}/analyze` (timeout 600) → lưu `analysis.json`; lớp `classType == "SERVICE"` dùng để so với `service_scope` → `scope_mismatch` |
| Sinh mỗi bước | `POST /api/projects/{id}/{path}/generate?servicePath=.&resume=true` với path theo thứ tự `business-rules`, `test-plans`, `test-cases`, `unit-tests` |
| Theo dõi | `GET /api/projects/{id}/generation-progress/{STAGE}` mỗi 5 giây tới khi status ∉ {QUEUED, RUNNING}; STAGE = `BUSINESS_RULE`, `TEST_PLAN`, `TEST_CASE`, `UNIT_TEST` |
| Đếm item | `GET /api/projects/{id}/{path}?servicePath=.` (độ dài list) |
| Duyệt (3 bước đầu) | `POST /api/projects/{id}/{path}/approve?servicePath=.` (timeout 600) |
| Tải test | `GET /api/projects/{id}/unit-tests/download?servicePath=.` (timeout 3000) |

Mỗi attempt cộng `runMetrics.recordedCalls/inputTokens/outputTokens/totalTokens/elapsedMillis` và lưu `<STAGE>-attempt-<n>.json`. Bước không `COMPLETED` sau khi `should_resume` trả False → dừng pipeline, `status = "PARTIAL"` (đã có Unit Test một phần) hoặc `"FAILED"`. `generation_seconds` = wall clock từ trước `analyze` đến khi bước Unit Test kết thúc; thời gian tải ghi riêng `download_seconds`. `cached_tokens` = None (runMetrics không có). Lưu output 4 bước vào `<path>.json`.

`cmd_generate`: `new_run_dir(out)` → `collect_sources` → ghi `source-manifest.json` (path, sha256, warning; không ghi text), `service-scope.json`, `config.json` (method, model, URL không key, temperature, max_output, timeout, max_resume, attempts=4, sha256 của `experiment.py`, thời điểm UTC) → gọi theo method → ghi `generation.json`.

- [ ] **Step 4: Chạy test, xác nhận PASS**

Run: như Step 2. Expected: toàn bộ test Task 1–3 OK.

---

### Task 4: `measure` — sandbox, loại file không compile, JaCoCo, Surefire

**Files:**
- Modify: `backend/scripts/experiment/experiment.py`
- Test: `backend/scripts/experiment/test_experiment.py`

**Interfaces:**
- Consumes: `collect_sources`, `write_json` (Task 1); `run-dir/tests/`, `run-dir/service-scope.json` (Task 3).
- Produces:
  - `failed_test_files(maven_log: str, sandbox: Path) -> dict[str, str]` — `{đường dẫn tương đối src/test/java/...: dòng lỗi đầu tiên}`, chỉ file dưới `src/test/java`.
  - `parse_jacoco(xml_text: str, scope: list[str]) -> dict` — `{"line": [covered, missed], "branch": [covered, missed], "project_line": [...], "project_branch": [...]}`; lớp trong scope không có trong report → cộng 0 covered và không tính được missed: ghi vào `"missing_classes"`.
  - `parse_surefire(report_dir: Path) -> dict` — `{"tests", "failures", "errors", "skipped"}`.
  - `percent(covered: int, missed: int) -> float | None` (None khi mẫu số 0, làm tròn 2 chữ số).
  - `run_maven(sandbox: Path, java_home: Path, log_path: Path) -> str` — chạy lệnh đo, ghi log, trả nội dung log (hàm cấp module để test patch được).
  - `cmd_measure(args) -> int`; ghi `measure.json`: `status ("OK"|"COMPILE_ERROR"), files_total, files_compiled, files_excluded, rounds, tests, passed, failures, errors, skipped, line_pct, branch_pct, project_line_pct, project_branch_pct, counters, missing_classes, tested_services, java_home, maven_version, measure_seconds`.

- [ ] **Step 1: Viết test thất bại**

```python
LOG = ("[ERROR] COMPILATION ERROR :\n"
       "[ERROR] /D:/x/sandbox/src/test/java/a/ATest.java:[12,5] cannot find symbol\n"
       "[ERROR] /D:/x/sandbox/src/test/java/a/ATest.java:[14,1] cannot find symbol\n"
       "[ERROR] /D:/x/sandbox/src/main/java/a/S.java:[1,1] boom\n")

def test_failed_test_files_only_test_sources(self):
    self.assertEqual(failed_test_files(LOG, Path("D:/x/sandbox")),
                     {"src/test/java/a/ATest.java": "[12,5] cannot find symbol"})

JACOCO = """<report name="r">
 <package name="a">
  <class name="a/OrderService"><counter type="LINE" missed="2" covered="8"/><counter type="BRANCH" missed="1" covered="3"/></class>
  <class name="a/OrderService$Inner"><counter type="LINE" missed="1" covered="1"/></class>
  <class name="a/Ctrl"><counter type="LINE" missed="10" covered="0"/></class>
 </package>
 <counter type="LINE" missed="13" covered="9"/><counter type="BRANCH" missed="1" covered="3"/>
</report>"""

def test_parse_jacoco_scope_includes_inner_classes(self):
    r = parse_jacoco(JACOCO, ["a.OrderService", "a.Missing"])
    self.assertEqual((r["line"], r["branch"], r["project_line"]), ([9, 3], [3, 1], [9, 13]))
    self.assertEqual(r["missing_classes"], ["a.Missing"])

def test_percent_zero_denominator(self):
    self.assertIsNone(percent(0, 0))
    self.assertEqual(percent(1, 2), 33.33)
```

Thêm `test_parse_surefire_sums_reports` (2 file `TEST-*.xml` với `<testsuite tests="3" failures="1" errors="0" skipped="0">` và `tests="2" errors="1"` → `{"tests": 5, "failures": 1, "errors": 1, "skipped": 0}`), `test_measure_refuses_existing_sandbox` (tạo `run/sandbox` rồi gọi `cmd_measure` → trả mã khác 0, không chạy Maven), và `test_measure_all_files_fail_gives_compile_error`: project giả + `run/tests/src/test/java/a/ATest.java`, `unittest.mock.patch("experiment.run_maven")` trả log `COMPILATION ERROR` chỉ ra `ATest.java` → `measure.json` có `status == "COMPILE_ERROR"`, `line_pct is None`, `files_excluded == 1`, file nằm trong `run/excluded-compile-fail/`.

- [ ] **Step 2: Chạy test, xác nhận FAIL**

Run: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`
Expected: FAIL / ImportError `failed_test_files`.

- [ ] **Step 3: Cài đặt**

`parse_jacoco`: tên lớp JaCoCo `a/OrderService$Inner` → chuẩn hóa `a.OrderService` (bỏ phần từ `$`), cộng counter vào lớp ngoài; lấy counter `<class>` trực tiếp, không cộng counter cấp `<package>`/`<report>` cho scope; `project_*` lấy counter con trực tiếp của `<report>`.

`cmd_measure`: copy project bằng `shutil.copytree(ignore=...)` bỏ `target`, `src/test`, `.git`; chép `run-dir/tests/**` vào `sandbox/`; chạy Maven với `JAVA_HOME=--java-home`, `PATH` có `<java-home>/bin` đứng đầu, log ghi `maven-round-<n>.log`. Nếu log có `COMPILATION ERROR` và `failed_test_files` khác rỗng: chuyển file sang `run-dir/excluded-compile-fail/<path>`, ghi `compile-failures.json` (vòng, file, lỗi), lặp (≤5 vòng). Không còn file test hoặc compile lỗi mà không xác định được file → `COMPILE_ERROR`, coverage None. `tested_services`: lớp scope có ít nhất một file test chứa tên lớp đó (đếm trên file đã compile).

- [ ] **Step 4: Chạy test, xác nhận PASS**

Run: như Step 2. Expected: toàn bộ test Task 1–4 OK.

---

### Task 5: `stats` — runs.csv, summary.csv, Stability, Req. Cov

**Files:**
- Modify: `backend/scripts/experiment/experiment.py`
- Test: `backend/scripts/experiment/test_experiment.py`

**Interfaces:**
- Consumes: `generation.json`, `measure.json`, `tests/` trong mỗi `run-dir` (Task 3–4).
- Produces:
  - `test_method_keys(tests_dir: Path) -> list[str]` — mỗi method có `@Test` (kể cả `@ParameterizedTest`) → khóa `"<tên file bỏ Test.java>#<tên method lowercase, bỏ _ >"`.
  - `pair_stability(a: list[str], b: list[str], threshold: float = 0.85) -> float | None` — ghép tham lam một-một theo similarity giảm dần; `100 * matched / max(len(a), len(b))`; hai tập rỗng → None.
  - `req_coverage(mapping_csv: Path, total_rules: int) -> float | None` — CSV `br_id,test`; BR khác nhau có ≥1 dòng / `total_rules`; file không có → None.
  - `describe(values: list[float | None]) -> dict` — `{"n", "mean", "sd", "median", "min", "max"}` trên giá trị khác None; `sd` None khi n < 2.
  - `cmd_stats(args) -> int` — tìm mọi thư mục có `generation.json` dưới `--runs`, ghi `runs.csv`, `summary.csv`, `stability.csv` (từng cặp lượt cùng project × method).

- [ ] **Step 1: Viết test thất bại**

```python
def test_pair_stability_one_to_one(self):
    a = ["OrderService#createorder", "OrderService#cancelorder"]
    b = ["OrderService#createorder", "OrderService#cancelorders", "Pay#x"]
    self.assertAlmostEqual(pair_stability(a, b), 100 * 2 / 3, places=2)
    self.assertIsNone(pair_stability([], []))
    self.assertEqual(pair_stability(["A#x"], []), 0.0)

def test_describe_sd_needs_two(self):
    self.assertEqual(describe([10.0, None])["sd"], None)
    d = describe([10.0, 20.0, None])
    self.assertEqual((d["n"], d["mean"], d["median"]), (2, 15.0, 15.0))

def test_req_coverage_counts_distinct_rules(self):
    (root / "m.csv").write_text("br_id,test\nBR1,A#x\nBR1,A#y\nBR2,B#z\n", encoding="utf-8")
    self.assertEqual(req_coverage(root / "m.csv", 4), 50.0)
    self.assertIsNone(req_coverage(root / "none.csv", 4))

def test_test_method_keys_reads_annotations(self):
    write(root / "t/src/test/java/a/OrderServiceTest.java",
          "class OrderServiceTest {\n @Test\n void create_Order() {}\n @ParameterizedTest\n void cancel() {}\n void helper() {}\n}")
    self.assertEqual(test_method_keys(root / "t"), ["OrderService#createorder", "OrderService#cancel"])
```

- [ ] **Step 2: Chạy test, xác nhận FAIL**

Run: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`
Expected: FAIL / ImportError `pair_stability`.

- [ ] **Step 3: Cài đặt**

Cột `runs.csv`: `project, method, run, status, measure_status, requests, retries, resumes, input_tokens, output_tokens, total_tokens, cached_tokens, generation_seconds, test_files, files_compiled, files_excluded, tests, passed, failures, errors, pass_pct, line_pct, branch_pct, project_line_pct, project_branch_pct, tested_services, scope_size, req_cov_pct`. `run` = tên thư mục lượt (`run-01`); project/method lấy từ `generation.json`. `pass_pct` = `percent(passed, tests - passed)`. `req_cov_pct` đọc `run-dir/req-mapping.csv`, tổng BR từ `--ground-truth <json>` (độ dài list `rules`), không có → trống. `summary.csv`: một dòng cho mỗi (project, method, metric) với cột của `describe`, cộng dòng `stability_pct` từ trung bình các cặp. Ô None ghi rỗng.

- [ ] **Step 4: Chạy test, xác nhận PASS**

Run: như Step 2. Expected: toàn bộ test OK.

---

### Task 6: CLI, README và pilot course-registration

**Files:**
- Modify: `backend/scripts/experiment/experiment.py` (`main()` với `argparse` subcommands)
- Create: `backend/scripts/experiment/README.md`

**Interfaces:**
- Consumes: `cmd_generate`, `cmd_measure`, `cmd_stats`.
- Produces: CLI
  - `generate --project DIR --method {llm-only,greytest} --out RUN_DIR [--env backend/.env] [--max-output 64000] [--timeout 900] [--base-url http://localhost:8080] [--max-resume 3]`
  - `measure --project DIR --run RUN_DIR [--java-home C:/Users/ADMIN/tools/jdk-17.0.19+10]`
  - `stats --runs ROOT --out DIR [--ground-truth FILE]`

- [ ] **Step 1: Viết `main()` và README**

README (tiếng Việt, ngắn): điều kiện tiên quyết (backend Docker ở 8080 dùng cùng `backend/.env`, JDK 17, Maven 3.9.9, `$PY`), 3 lệnh mẫu cho pilot dưới đây, cấu trúc `run-dir`, nhắc rằng pilot không phải kết quả chương 5.

- [ ] **Step 2: Chạy toàn bộ unit test**

Run: `$PY -m unittest discover -s backend/scripts/experiment -p test_experiment.py -v`
Expected: toàn bộ OK.

- [ ] **Step 3: Kiểm tra backend sẵn sàng**

Run: `curl -s http://localhost:8080/api/ping` và `docker compose -f backend/docker-compose.yml ps`
Expected: ping trả 200; container backend chạy với `LLM_PROVIDER=openai`, `LLM_MODEL=gpt-6-luna`. Nếu chưa chạy: dừng, báo người dùng (không tự build lại image).

- [ ] **Step 4: Chạy pilot LLM-only (gọi API thật, tốn phí nhỏ)**

```bash
OUT=outputs/chapter-5/experiment-20261116-pilot/course-registration
$PY backend/scripts/experiment/experiment.py generate --project source-test/course-registration --method llm-only --out $OUT/llm-only/run-01
$PY backend/scripts/experiment/experiment.py measure --project source-test/course-registration --run $OUT/llm-only/run-01
```
Expected: `generation.json` status `OK` hoặc `TRUNCATED`, `requests` = 1, `test_files` > 0; `measure.json` có `line_pct` hoặc `COMPILE_ERROR` kèm `compile-failures.json`.

- [ ] **Step 5: Chạy pilot GreyTest**

```bash
$PY backend/scripts/experiment/experiment.py generate --project source-test/course-registration --method greytest --out $OUT/greytest/run-01
$PY backend/scripts/experiment/experiment.py measure --project source-test/course-registration --run $OUT/greytest/run-01
```
Expected: `generation.json` có 4 bước, `scope_mismatch` được ghi (rỗng hoặc liệt kê lớp lệch), token > 0.

- [ ] **Step 6: Thống kê và kiểm tra evidence**

```bash
$PY backend/scripts/experiment/experiment.py stats --runs $OUT --out $OUT/../stats
grep -rIl -i "authorization\|bearer \|sk-\|LLM_API_KEY" $OUT $OUT/../stats
```
Expected: `runs.csv` có 2 dòng, `summary.csv` có n = 1, sd trống; lệnh `grep` không in file nào.

- [ ] **Step 7: Báo cáo và commit (sau khi người dùng đồng ý)**

Báo bảng 2 hàng từ `runs.csv` và các điểm lệch thấy được. Khi được đồng ý:

```bash
git -C backend add scripts/experiment/experiment.py scripts/experiment/test_experiment.py scripts/experiment/README.md docs/superpowers/specs/2026-11-16-greytest-vs-llm-only-experiment-design.md docs/superpowers/plans/2026-11-16-greytest-vs-llm-only-experiment.md
git -C backend commit -m "feat(experiment): add GreyTest vs LLM-only experiment runner"
```
