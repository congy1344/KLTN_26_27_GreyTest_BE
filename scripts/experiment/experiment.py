"""Trình chạy thực nghiệm GreyTest vs LLM-only.

Trách nhiệm: sinh test theo từng phương pháp (generate), đo bằng cùng một lệnh Maven + JaCoCo cho cả hai
(measure), gộp số liệu nhiều lượt (stats). Chỉ dùng thư viện chuẩn. Không ghi API key, header Authorization
hay mật khẩu vào evidence. Thiết kế: backend/docs/superpowers/specs/2026-11-16-greytest-vs-llm-only-experiment-design.md
"""
import argparse
import csv
import datetime
import difflib
import hashlib
import io
import itertools
import json
import os
import random
import re
import secrets
import shutil
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path
from xml.etree import ElementTree

RESOURCE_SUFFIXES = {".yml", ".yaml", ".properties", ".sql", ".xml"}
MAX_RESOURCE_BYTES = 100_000
MAX_ATTEMPTS = 4  # Giống PROVIDER_MAX_ATTEMPTS của GreyTest.
RETRY_DELAY_SECONDS = 5
TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b")

# Prompt cố định, trung tính, đúng cách một dev nhờ LLM viết test. Không đổi giữa các lượt.
PROMPT_HEADER = (
    "You are given the full source of a Java Spring Boot project.\n"
    "Write JUnit 5 unit tests with Mockito for every class annotated with @Service in this project.\n"
    "{target}"
    "Use only dependencies already declared in pom.xml.\n"
    "Return each test file as a separate ```java code block whose first line is a comment with its path, "
    "e.g. // src/test/java/com/example/FooServiceTest.java\n"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value) -> None:
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding="utf-8")


def new_run_dir(path: Path) -> Path:
    """Không bao giờ ghi đè evidence của lượt cũ."""
    path = Path(path)
    path.mkdir(parents=True, exist_ok=False)
    return path


def _is_input(rel: Path) -> bool:
    parts = rel.parts
    if any(p.startswith(".") for p in parts) or "target" in parts:
        return False
    if rel.as_posix() == "pom.xml":
        return True
    if parts[:3] == ("src", "main", "java"):
        return rel.suffix == ".java"
    # Chỉ lấy cấu hình dạng text; bỏ tài nguyên web tĩnh (html/js/css/ảnh).
    return parts[:3] == ("src", "main", "resources") and rel.suffix in RESOURCE_SUFFIXES


def _is_large_resource(file: Path, rel: Path) -> bool:
    # Dump dữ liệu/đặc tả lớn (VD SQL 215 KB của newbee-mall) không phải thứ dev dán vào chat; Java không bao giờ bị bỏ.
    return rel.parts[:3] == ("src", "main", "resources") and file.stat().st_size > MAX_RESOURCE_BYTES


def skipped_resources(project: Path) -> list:
    project = Path(project)
    return sorted(f.relative_to(project).as_posix() for f in project.rglob("*")
                  if f.is_file() and _is_input(f.relative_to(project)) and _is_large_resource(f, f.relative_to(project)))


def collect_sources(project: Path) -> list:
    """Bộ file đầu vào chung cho cả hai phương pháp: pom.xml, src/main/java, cấu hình text trong resources (≤ 100 KB)."""
    project = Path(project)
    result = []
    for file in project.rglob("*"):
        rel = file.relative_to(project)
        if not file.is_file() or not _is_input(rel) or _is_large_resource(file, rel):
            continue
        raw = file.read_bytes()
        warning = None
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            text, warning = raw.decode("utf-8", errors="replace"), "not UTF-8, decoded with replacement"
        result.append({"path": rel.as_posix(), "sha256": sha256(raw), "text": text, "warning": warning})
    return sorted(result, key=lambda s: s["path"])


def java_version(pom_text: str):
    for tag in ("maven.compiler.release", "java.version", "maven.compiler.source"):
        match = re.search(rf"<{re.escape(tag)}>\s*([^<\s]+)\s*</{re.escape(tag)}>", pom_text)
        if match:
            return match.group(1)
    return None


def service_scope(sources: list) -> list:
    """Phạm vi đo chốt trước, độc lập với output AI: lớp (không phải interface) có @Service."""
    scope = []
    for source in sources:
        if not source["path"].endswith(".java"):
            continue
        text = source["text"]
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        # Annotation có thể có tham số và theo sau là annotation/modifier khác trước từ khóa class.
        match = re.search(r"@Service\b(?:\([^)]*\))?[^{;]*?\b(class|interface|enum|record)\s+(\w+)", text)
        if match and match.group(1) == "class":
            scope.append((package.group(1) + "." if package else "") + match.group(2))
    return sorted(scope)


# ---------------------------------------------------------------- LLM-only

def load_env(path: Path) -> dict:
    env = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        env[key.strip()] = value
    return env


def build_prompt(sources: list, java) -> str:
    target = f"Target Java {java}.\n" if java else ""
    parts = [PROMPT_HEADER.format(target=target)]
    for source in sources:
        parts.append(f"\n### FILE: {source['path']}\n{source['text']}")
    return "".join(parts)


def request_body(prompt: str, model: str, temperature: float, max_output: int) -> dict:
    """Dựng body giống OpenAiLlmClient của backend để hai phương pháp gọi provider cùng một kiểu."""
    body = {"model": model, "messages": [{"role": "user", "content": prompt}], "temperature": temperature}
    if model == "gpt-6-luna":
        body["max_completion_tokens"] = max_output
        body["reasoning_effort"] = "none"
    else:
        body["max_tokens"] = max_output
    return body


def _test_path(code: str):
    first = code.lstrip().split("\n", 1)[0]
    match = re.search(r"(src/test/java/[\w/.$-]+\.java)", first)
    # Output LLM là dữ liệu không tin cậy: header có ".." thì bỏ, suy đường dẫn từ package.
    if match and ".." not in match.group(1).split("/"):
        return match.group(1)
    package = re.search(r"^\s*package\s+([\w.]+)\s*;", code, re.M)
    name = (re.search(r"\bpublic\s+(?:final\s+)?class\s+(\w+)", code)
            or re.search(r"\b(?:class|interface|record|enum)\s+(\w+)", code))
    if not name:
        return None
    folder = package.group(1).replace(".", "/") + "/" if package else ""
    return f"src/test/java/{folder}{name.group(1)}.java"


def extract_test_files(text: str):
    """Trích nguyên văn các khối code; không sửa nội dung test do AI viết."""
    files, errors, end = {}, [], 0
    for match in re.finditer(r"```[\w-]*[ \t]*\r?\n(.*?)```", text, re.S):
        end = match.end()
        code = match.group(1)
        path = _test_path(code)
        if path is None:
            errors.append("cannot infer path for block at offset %d" % match.start())
            continue
        if path in files:
            errors.append("duplicate path, kept last: " + path)
        files[path] = code
    if "```" in text[end:]:
        errors.append("unclosed block at end of response (dropped)")
    return files, errors


def _is_transport_error(error) -> bool:
    if isinstance(error, urllib.error.HTTPError):
        return error.code == 429 or error.code >= 500
    return isinstance(error, (urllib.error.URLError, TimeoutError, ConnectionError))


def post_with_retry(url, body, headers, timeout, opener=urllib.request.urlopen, attempts=MAX_ATTEMPTS):
    """Chỉ thử lại khi lỗi truyền tải chưa có output (giống chính sách provider của GreyTest)."""
    data = json.dumps(body).encode("utf-8")
    log = []
    for attempt in range(1, attempts + 1):
        started = time.monotonic()
        try:
            request = urllib.request.Request(url, data=data, headers=headers, method="POST")
            response = opener(request, timeout=timeout)
            try:
                raw = response.read()
            finally:
                getattr(response, "close", lambda: None)()
            seconds = round(time.monotonic() - started, 3)
            payload = json.loads(raw)
            log.append({"attempt": attempt, "error": None, "seconds": seconds})
            return payload, log
        except Exception as error:  # noqa: BLE001 - phân loại ngay bên dưới
            detail = f"{type(error).__name__}: {error}"
            # Body của 401/403 có thể chứa mảnh API key đã che: không ghi ra đĩa.
            if isinstance(error, urllib.error.HTTPError) and error.code not in (401, 403):
                detail += " " + error.read()[:300].decode("utf-8", "replace")
            log.append({"attempt": attempt, "error": detail, "seconds": round(time.monotonic() - started, 3)})
            if not _is_transport_error(error) or attempt == attempts:
                raise RuntimeError(json.dumps(log, ensure_ascii=False)) from error
            time.sleep(RETRY_DELAY_SECONDS * attempt)
    raise AssertionError("unreachable")


def count_test_methods(code: str) -> int:
    return len(TEST_ANNOTATION.findall(code))


def generate_llm_only(project: Path, run_dir: Path, env: dict, max_output: int, timeout: int) -> dict:
    """Đúng một request nội dung: toàn dự án + lời yêu cầu; output chấm nguyên trạng."""
    if env.get("LLM_PROVIDER") != "openai":
        raise ValueError("LLM-only runner supports only LLM_PROVIDER=openai")
    sources = collect_sources(project)
    pom = next((s["text"] for s in sources if s["path"] == "pom.xml"), "")
    prompt = build_prompt(sources, java_version(pom))
    (run_dir / "prompt.txt").write_text(prompt, encoding="utf-8")
    model = env["LLM_MODEL"]
    body = request_body(prompt, model, float(env.get("LLM_TEMPERATURE", "0.3")), max_output)
    headers = {"Authorization": "Bearer " + env["LLM_API_KEY"], "Content-Type": "application/json"}
    result = {"status": "FAILED", "requests": 1, "retries": 0, "resumes": 0, "input_tokens": None,
              "output_tokens": None, "total_tokens": None, "cached_tokens": None, "test_files": 0,
              "test_methods": 0, "model": model, "scope_mismatch": None}
    started = time.monotonic()
    try:
        response, log = post_with_retry(env["LLM_OPENAI_URL"], body, headers, timeout)
    except RuntimeError as error:
        response, log = None, json.loads(str(error))
    result["generation_seconds"] = round(time.monotonic() - started, 3)
    result["ai_wait_seconds"] = round(sum(attempt["seconds"] for attempt in log), 3)
    result["retries"] = len(log) - 1
    write_json(run_dir / "attempts.json", log)
    if response is None:
        return result
    write_json(run_dir / "response.json", response)
    choice = (response.get("choices") or [{}])[0]
    usage = response.get("usage") or {}
    files, errors = extract_test_files((choice.get("message") or {}).get("content") or "")
    for path, code in files.items():
        target = run_dir / "tests" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(code, encoding="utf-8")
    write_json(run_dir / "extract-errors.json", errors)
    result.update(
        status="TRUNCATED" if choice.get("finish_reason") == "length" else "OK",
        input_tokens=usage.get("prompt_tokens"), output_tokens=usage.get("completion_tokens"),
        total_tokens=usage.get("total_tokens"),
        cached_tokens=(usage.get("prompt_tokens_details") or {}).get("cached_tokens"),
        test_files=len(files), test_methods=sum(count_test_methods(c) for c in files.values()),
        model=response.get("model") or model)
    return result


# ---------------------------------------------------------------- GreyTest qua API

STAGES = (("BUSINESS_RULE", "business-rules"), ("TEST_PLAN", "test-plans"),
          ("TEST_CASE", "test-cases"), ("UNIT_TEST", "unit-tests"))
ACTIVE = {"QUEUED", "RUNNING"}
POLL_SECONDS = 5
CONFLICT_DELAY_SECONDS = 5
STAGE_TIMEOUT_SECONDS = 3 * 3600  # ponytail: trần an toàn cho một attempt; pilot newbee-mall dài nhất ~40 phút.


def source_zip(sources: list) -> bytes:
    """Zip đúng bộ file LLM-only nhận, để hai phương pháp có cùng đầu vào."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in sources:
            archive.writestr(source["path"], source["text"].encode("utf-8"))
    return buffer.getvalue()


def should_resume(item_counts: list, status: str, resumes_used: int, max_resume: int) -> bool:
    """Chỉ resume khi được bật, còn lượt và lần trước có thêm item."""
    if status == "COMPLETED" or resumes_used >= max_resume:
        return False
    c = item_counts
    return not (len(c) >= 2 and c[-1] <= c[-2])


def extract_zip_tests(data: bytes, dest: Path) -> list:
    """Chỉ lấy file test trong src/test/java; helper ở gốc zip (GreyTestSourceCheck.java) không phải test AI sinh."""
    written = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            marker = name.find("src/test/java/")
            if not name.endswith(".java") or marker < 0:
                continue
            rel = name[marker:]
            if any(part in ("", "..") for part in rel.split("/")):
                continue  # Chống path traversal.
            code = archive.read(name).decode("utf-8-sig", errors="replace")
            target = Path(dest) / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(code, encoding="utf-8")
            written.append(rel)
    return written


class Api:
    """Client HTTP tối thiểu cho backend GreyTest; token chỉ giữ trong bộ nhớ."""

    def __init__(self, base_url: str):
        self.base, self.token = base_url.rstrip("/"), None

    def call(self, method, path, body=None, content_type="application/json", timeout=300, raw=False):
        headers = {"Authorization": "Bearer " + self.token} if self.token else {}
        if body is not None:
            if isinstance(body, (dict, list)):
                body = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + path, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status, payload = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, payload = error.code, error.read()
        if raw:
            return status, payload
        try:
            return status, json.loads(payload) if payload else None
        except ValueError:
            return status, payload.decode("utf-8", "replace")


def _expect(status, payload, ok, what):
    if status not in ok:
        raise RuntimeError(f"{what} failed: HTTP {status} {str(payload)[:300]}")
    return payload


def pipeline_totals(stages: dict) -> dict:
    """runMetrics của lượt resume đã cộng dồn lượt trước, nên mỗi stage chỉ lấy attempt cuối rồi cộng các stage."""
    totals = {"requests": 0, "input_tokens": 0, "output_tokens": 0, "total_tokens": 0, "stage_elapsed_millis": 0}
    waits = []
    for attempts in stages.values():
        last = attempts[-1] if attempts else {}
        waits.append(last.get("llmWaitMillis"))
        for key, field in (("requests", "recordedCalls"), ("input_tokens", "inputTokens"),
                           ("output_tokens", "outputTokens"), ("total_tokens", "totalTokens"),
                           ("stage_elapsed_millis", "elapsedMillis")):
            totals[key] += last.get(field) or 0
    totals["ai_wait_seconds"] = sum(waits) / 1000 if waits and None not in waits else None
    return totals


def retry_conflict(call, attempts: int = 6):
    """Backend có thể trả 409 ngay sau COMPLETED vì khóa project chưa nhả; chờ ngắn rồi gọi lại."""
    for attempt in range(1, attempts + 1):
        status, payload = call()
        if status != 409 or attempt == attempts:
            return status, payload
        time.sleep(CONFLICT_DELAY_SECONDS)


def _poll(api, pid, stage, previous_run_id):
    """Chờ job mới kết thúc; bỏ qua trạng thái cũ còn sót của lượt trước (cùng runId)."""
    stale_deadline = time.monotonic() + 120
    deadline = time.monotonic() + STAGE_TIMEOUT_SECONDS
    while True:
        if time.monotonic() > deadline:
            raise RuntimeError(f"{stage} did not finish within {STAGE_TIMEOUT_SECONDS}s")
        time.sleep(POLL_SECONDS)
        status, progress = api.call("GET", f"/api/projects/{pid}/generation-progress/{stage}")
        if status != 200 or not isinstance(progress, dict):
            continue
        run_id = (progress.get("runMetrics") or {}).get("runId")
        stale = run_id is not None and run_id == previous_run_id and time.monotonic() < stale_deadline
        if progress.get("status") not in ACTIVE and not progress.get("busy") and not stale:
            # Backend tự "Tiếp tục sinh" khi stage xong một phần/lỗi AI: chờ một nhịp xem có lượt mới được xếp hàng không.
            time.sleep(POLL_SECONDS)
            status, again = api.call("GET", f"/api/projects/{pid}/generation-progress/{stage}")
            if status == 200 and isinstance(again, dict) and (again.get("status") in ACTIVE or again.get("busy")):
                continue
            return progress


def generate_greytest(project: Path, run_dir: Path, base_url: str, max_resume: int) -> dict:
    """Chạy pipeline GreyTest hoàn toàn tự động: BR (AI tự sinh) -> Plan -> Case -> Unit Test, tự duyệt."""
    api = Api(base_url)
    _expect(*api.call("GET", "/api/ping", timeout=30), {200}, "health check")
    auth = _expect(*api.call("POST", "/api/auth/register", {
        "email": f"exp-{uuid.uuid4().hex}@evaluation.test", "password": secrets.token_urlsafe(24),
        "fullName": "Experiment run"}), {200, 201}, "register")  # Mật khẩu không lưu ra đĩa.
    api.token = auth["token"]
    sources = collect_sources(project)
    boundary = "x" + uuid.uuid4().hex
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="project.zip"\r\n'
            "Content-Type: application/zip\r\n\r\n")
    body = head.encode() + source_zip(sources) + f"\r\n--{boundary}--\r\n".encode()
    uploaded = _expect(*api.call("POST", "/api/projects/upload", body, f"multipart/form-data; boundary={boundary}"),
                       {200, 201}, "upload")
    pid = uploaded["id"]
    result = {"status": "FAILED", "requests": 0, "retries": None, "resumes": 0, "input_tokens": 0,
              "output_tokens": 0, "total_tokens": 0, "cached_tokens": None, "test_files": 0, "test_methods": 0,
              "model": None, "scope_mismatch": None, "project_id": pid, "stages": []}
    started = time.monotonic()
    stage_metrics = {}
    try:
        _run_pipeline(api, pid, run_dir, sources, max_resume, result, stage_metrics)
    except Exception as error:  # noqa: BLE001 - giữ evidence và token đã tốn, lượt vẫn vào thống kê
        result["status"], result["error"] = "FAILED", f"{type(error).__name__}: {error}"[:1000]
    result.update(pipeline_totals(stage_metrics))
    result["generation_seconds"] = round(time.monotonic() - started, 3)
    if result["status"] != "FAILED":
        download_started = time.monotonic()
        status, data = api.call("GET", f"/api/projects/{pid}/unit-tests/download?servicePath=.",
                                timeout=3000, raw=True)
        _expect(status, data[:300], {200}, "download")
        result["download_seconds"] = round(time.monotonic() - download_started, 3)
        (run_dir / "greytest-unit-tests.zip").write_bytes(data)
        written = extract_zip_tests(data, run_dir / "tests")
        result["test_files"] = len(written)
        result["test_methods"] = sum(count_test_methods((run_dir / "tests" / w).read_text(encoding="utf-8"))
                                     for w in written)
    return result


def _run_pipeline(api, pid, run_dir, sources, max_resume, result, stage_metrics):
    analysis = _expect(*api.call("POST", f"/api/projects/{pid}/analyze", timeout=600), {200}, "analyze")
    write_json(run_dir / "analysis.json", analysis)
    detected = {c.get("qualifiedName") for c in analysis.get("classes") or [] if c.get("classType") == "SERVICE"}
    expected = set(service_scope(sources))
    result["scope_mismatch"] = (sorted(f"-{n}" for n in expected - detected)
                                + sorted(f"+{n}" for n in detected - expected))
    for stage, path in STAGES:
        counts, run_id, status = [], None, None
        while True:
            _expect(*retry_conflict(lambda: api.call(
                "POST", f"/api/projects/{pid}/{path}/generate?servicePath=.&resume={'true' if counts else 'false'}")),
                    {200, 202}, f"{stage} generate")
            attempts = stage_metrics.setdefault(stage, [])
            # Giữ token đã biết khi polling lỗi, đồng thời đánh dấu số đo AI chưa đầy đủ.
            attempts.append({**(attempts[-1] if attempts else {}), "llmWaitMillis": None})
            progress = _poll(api, pid, stage, run_id)
            metrics = progress.get("runMetrics") or {}
            attempts[-1] = metrics
            run_id, status = metrics.get("runId"), progress.get("status")
            items = api.call("GET", f"/api/projects/{pid}/{path}?servicePath=.")[1]
            counts.append(len(items) if isinstance(items, list) else 0)
            write_json(run_dir / f"{stage}-attempt-{len(counts)}.json", progress)
            # runMetrics ở đây là số cộng dồn từ attempt đầu của stage (backend tự cộng khi resume).
            result["stages"].append({"stage": stage, "attempt": len(counts), "status": status, "items": counts[-1],
                                     "cumulativeElapsedMillis": metrics.get("elapsedMillis"), "runId": run_id,
                                     "cumulativeLlmWaitMillis": metrics.get("llmWaitMillis"),
                                     "cumulativeRecordedCalls": metrics.get("recordedCalls"),
                                     "cumulativeTotalTokens": metrics.get("totalTokens"),
                                     "errors": [s["errorMessage"][:500] for s in progress.get("steps") or []
                                                if s.get("errorMessage")]})
            result["resumes"] += 1 if len(counts) > 1 else 0
            if not should_resume(counts, status, len(counts) - 1, max_resume):
                break
        write_json(run_dir / f"{path}.json", api.call("GET", f"/api/projects/{pid}/{path}?servicePath=.")[1])
        if stage == "UNIT_TEST":
            result["status"] = "OK" if status == "COMPLETED" else ("PARTIAL" if counts[-1] else "FAILED")
            return
        if status != "COMPLETED":
            raise RuntimeError(f"{stage} ended {status} after {len(counts)} attempt(s)")
        _expect(*retry_conflict(lambda: api.call(
            "POST", f"/api/projects/{pid}/{path}/approve?servicePath=.", timeout=600)), {200}, f"{stage} approve")


# ---------------------------------------------------------------- measure

JACOCO_VERSION = "0.8.12"
MAVEN_TIMEOUT_SECONDS = 1800
DEFAULT_JAVA_HOME = "C:/Users/ADMIN/tools/jdk-17.0.19+10"
MAVEN_GOALS = ["-B", "-V", "-ntp", "-Dmaven.test.failure.ignore=true",
               f"org.jacoco:jacoco-maven-plugin:{JACOCO_VERSION}:prepare-agent", "test",
               f"org.jacoco:jacoco-maven-plugin:{JACOCO_VERSION}:report"]
COMPILE_ERROR_LINE = re.compile(r"^\[ERROR\]\s+(.+?\.java):(\[\d+,\d+\].*)$", re.M)


def percent(covered: int, missed: int):
    total = covered + missed
    return round(100 * covered / total, 2) if total else None


def failed_test_files(maven_log: str, sandbox: Path) -> dict:
    """File test gây lỗi compile, kèm dòng lỗi đầu tiên; bỏ qua lỗi ở source production."""
    prefix = Path(sandbox).as_posix().rstrip("/").lower() + "/"
    failed = {}
    for match in COMPILE_ERROR_LINE.finditer(maven_log):
        path = match.group(1).strip().replace("\\", "/")
        if re.match(r"^/[A-Za-z]:/", path):
            path = path[1:]
        if path.lower().startswith(prefix):
            rel = path[len(prefix):]
        elif "/src/test/java/" in path:
            rel = path[path.index("/src/test/java/") + 1:]
        else:
            continue
        if rel.startswith("src/test/java/"):
            failed.setdefault(rel, match.group(2).strip())
    return failed


def parse_jacoco(xml_text: str, scope: list) -> dict:
    """Counter LINE/BRANCH trên service scope (gộp inner class vào lớp ngoài) và toàn dự án."""
    root = ElementTree.fromstring(xml_text)
    per_class = {}
    for node in root.iter("class"):
        name = node.get("name", "").split("$")[0].replace("/", ".")
        bucket = per_class.setdefault(name, {"LINE": [0, 0], "BRANCH": [0, 0]})
        for counter in node.findall("counter"):
            if counter.get("type") in bucket:
                bucket[counter.get("type")][0] += int(counter.get("covered", 0))
                bucket[counter.get("type")][1] += int(counter.get("missed", 0))
    result = {"line": [0, 0], "branch": [0, 0], "project_line": [0, 0], "project_branch": [0, 0],
              "missing_classes": [name for name in scope if name not in per_class],
              "covered_classes": [name for name in scope if per_class.get(name, {}).get("LINE", [0])[0] > 0]}
    for name in scope:
        for kind in ("LINE", "BRANCH"):
            for i in (0, 1):
                result[kind.lower()][i] += per_class.get(name, {}).get(kind, [0, 0])[i]
    for counter in root.findall("counter"):
        key = {"LINE": "project_line", "BRANCH": "project_branch"}.get(counter.get("type"))
        if key:
            result[key] = [int(counter.get("covered", 0)), int(counter.get("missed", 0))]
    return result


def parse_surefire(report_dir: Path) -> dict:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for report in sorted(Path(report_dir).glob("TEST-*.xml")):
        suite = ElementTree.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0) or 0)
    return totals


def run_maven(sandbox: Path, java_home: Path, log_path: Path) -> str:
    """Cùng một lệnh đo cho mọi phương pháp; JDK được chỉ định rõ, không dùng JDK mặc định của máy."""
    env = dict(os.environ, JAVA_HOME=str(java_home))
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    mvn = shutil.which("mvn") or "mvn"
    # ponytail: timeout giết mvn; trên Windows JVM con có thể còn sống, cần dọn tay nếu gặp.
    completed = subprocess.run([mvn, *MAVEN_GOALS], cwd=sandbox, env=env, capture_output=True,
                               text=True, encoding="utf-8", errors="replace", timeout=MAVEN_TIMEOUT_SECONDS)
    log = (completed.stdout or "") + (completed.stderr or "")
    Path(log_path).write_text(log, encoding="utf-8")
    return log


def _test_files(sandbox: Path) -> list:
    root = sandbox / "src/test/java"
    return sorted(p.relative_to(sandbox).as_posix() for p in root.rglob("*.java")) if root.exists() else []


def cmd_measure(args) -> int:
    project, run_dir = Path(args.project).resolve(), Path(args.run).resolve()
    sandbox = run_dir / "sandbox"
    if sandbox.exists():
        print("sandbox already exists, refusing to overwrite: " + str(sandbox))
        return 2
    started = time.monotonic()

    def ignore(directory, names):
        skip = {"target", ".git"}
        if Path(directory) == project / "src":
            skip.add("test")  # Không chạy test sẵn có của dự án.
        return [n for n in names if n in skip]

    shutil.copytree(project, sandbox, ignore=ignore)
    if (run_dir / "tests").exists():
        shutil.copytree(run_dir / "tests", sandbox, dirs_exist_ok=True)
    files_total = len(_test_files(sandbox))
    failures, rounds, log, compile_ok, timed_out = [], 0, "", False, False
    # Loại file không compile rồi chạy lại tới khi qua; javac chỉ báo một phần lỗi mỗi lần nên phải lặp.
    # Mỗi vòng loại ít nhất một file nên vòng lặp luôn dừng (tối đa files_total + 1 vòng).
    while _test_files(sandbox):
        rounds += 1
        try:
            log = run_maven(sandbox, Path(args.java_home), run_dir / f"maven-round-{rounds}.log")
        except subprocess.TimeoutExpired:
            timed_out = True
            break
        failed = failed_test_files(log, sandbox) if "COMPILATION ERROR" in log else {}
        if not failed:
            compile_ok = "COMPILATION ERROR" not in log
            break
        for rel, error in failed.items():
            target = run_dir / "excluded-compile-fail" / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(sandbox / rel), str(target))
            failures.append({"round": rounds, "file": rel, "error": error})
    write_json(run_dir / "compile-failures.json", failures)
    remaining = _test_files(sandbox)
    scope = json.loads((run_dir / "service-scope.json").read_text(encoding="utf-8"))
    jacoco_xml = sandbox / "target/site/jacoco/jacoco.xml"
    counters = parse_jacoco(jacoco_xml.read_text(encoding="utf-8"), scope) if compile_ok and jacoco_xml.exists() else None
    tests = parse_surefire(sandbox / "target/surefire-reports") if compile_ok else None
    version = re.search(r"Apache Maven (\S+)", log)
    if files_total == 0:
        status = "NO_TESTS"  # Không có file test để đo (ví dụ lượt sinh FAILED), không phải lỗi compile.
    elif timed_out:
        status = "TIMEOUT"
    else:
        status = "OK" if compile_ok and counters else ("COMPILE_ERROR" if not compile_ok else "NO_COVERAGE_REPORT")
    result = {
        "status": status, "files_total": files_total, "files_compiled": len(remaining) if compile_ok else 0,
        "files_excluded": len(failures), "rounds": rounds,
        "tests": tests and tests["tests"], "passed": tests and tests["tests"] - tests["failures"] - tests["errors"] - tests["skipped"],
        "failures": tests and tests["failures"], "errors": tests and tests["errors"], "skipped": tests and tests["skipped"],
        "line_pct": counters and percent(*counters["line"]), "branch_pct": counters and percent(*counters["branch"]),
        "project_line_pct": counters and percent(*counters["project_line"]),
        "project_branch_pct": counters and percent(*counters["project_branch"]),
        "counters": counters, "missing_classes": counters and counters["missing_classes"],
        # Service được test = lớp scope có ít nhất một dòng được JaCoCo ghi nhận chạy qua.
        "tested_services": counters and len(counters["covered_classes"]),
        "scope_size": len(scope), "java_home": str(args.java_home), "maven_version": version and version.group(1),
        "measure_seconds": round(time.monotonic() - started, 3)}
    write_json(run_dir / "measure.json", result)
    print(json.dumps({k: result[k] for k in ("status", "files_compiled", "files_total", "tests", "passed",
                                              "line_pct", "branch_pct")}))
    return 0


# ---------------------------------------------------------------- stats

STABILITY_THRESHOLD = 0.85
# Cho phép chú thích // hoặc /* */ xen giữa annotation và method (GreyTest ghi "// GreyTest trace" ở đó).
_GAP = r"(?:\s|//[^\n]*|/\*.*?\*/)*"
TEST_METHOD = re.compile(
    r"@(?:Test|ParameterizedTest|RepeatedTest)\b(?:\([^)]*\))?"
    rf"(?:{_GAP}@\w+(?:\([^)]*\))?)*{_GAP}(?:(?:public|protected|private|static|final)\s+)*void\s+(\w+)\s*\(",
    re.DOTALL)
RUN_COLUMNS = ["project", "method", "run", "status", "measure_status", "requests", "retries", "resumes",
               "input_tokens", "output_tokens", "total_tokens", "cached_tokens", "generation_seconds",
               "ai_wait_seconds",
               "test_files", "files_compiled", "files_excluded", "tests", "passed", "failures", "errors",
               "pass_pct", "line_pct", "branch_pct", "project_line_pct", "project_branch_pct",
               "tested_services", "scope_size", "req_cov_pct"]
SUMMARY_METRICS = RUN_COLUMNS[5:]


def test_method_keys(tests_dir: Path) -> list:
    """Khóa so sánh giữa các lượt: lớp được test + tên test method đã chuẩn hóa."""
    keys = []
    for file in sorted(Path(tests_dir).rglob("*.java")):
        owner = re.sub(r"Tests?$", "", file.stem)
        for name in TEST_METHOD.findall(file.read_text(encoding="utf-8", errors="replace")):
            keys.append(f"{owner}#{name.lower().replace('_', '')}")
    return keys


def pair_stability(a: list, b: list, threshold: float = STABILITY_THRESHOLD):
    """Ghép một-một tham lam theo similarity giảm dần; 100 * matched / max(len)."""
    if not a and not b:
        return None
    pairs = sorted(((difflib.SequenceMatcher(None, x, y).ratio(), i, j)
                    for i, x in enumerate(a) for j, y in enumerate(b)), reverse=True)
    used_a, used_b = set(), set()
    for ratio, i, j in pairs:
        if ratio <= threshold:
            break
        if i not in used_a and j not in used_b:
            used_a.add(i)
            used_b.add(j)
    return round(100 * len(used_a) / max(len(a), len(b)), 2)


def req_coverage(mapping_csv: Path, total_rules: int):
    if not Path(mapping_csv).is_file() or not total_rules:
        return None
    with open(mapping_csv, encoding="utf-8-sig", newline="") as handle:
        covered = {row["br_id"].strip() for row in csv.DictReader(handle) if row.get("test", "").strip()}
    return percent(len(covered), total_rules - len(covered))


def describe(values: list) -> dict:
    data = [float(v) for v in values if v is not None]
    if not data:
        return {"n": 0, "mean": None, "sd": None, "median": None, "min": None, "max": None}
    return {"n": len(data), "mean": round(statistics.mean(data), 2),
            "sd": round(statistics.stdev(data), 2) if len(data) > 1 else None,
            "median": round(statistics.median(data), 2), "min": min(data), "max": max(data)}


def _average_ranks(values: list) -> list:
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks, i = [0.0] * len(values), 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        for k in range(i, j + 1):
            ranks[order[k]] = (i + j) / 2 + 1  # Hạng trung bình cho giá trị bằng nhau.
        i = j + 1
    return ranks


def mann_whitney_exact(a: list, b: list):
    """Kiểm định Mann-Whitney U hai phía, phân phối chính xác bằng liệt kê (phù hợp 5 lượt mỗi phương pháp)."""
    if not a or not b:
        return None, None
    n1, pooled = len(a), list(a) + list(b)
    ranks = _average_ranks(pooled)
    offset, mu = n1 * (n1 + 1) / 2, n1 * len(b) / 2
    observed = sum(ranks[:n1]) - offset
    if len(pooled) > 20:
        return observed, None  # ponytail: liệt kê quá lớn; cần xấp xỉ chuẩn nếu mỗi nhóm > 10 lượt.
    extreme = total = 0
    for chosen in itertools.combinations(range(len(pooled)), n1):
        total += 1
        if abs(sum(ranks[i] for i in chosen) - offset - mu) >= abs(observed - mu) - 1e-9:
            extreme += 1
    return observed, extreme / total


def cliffs_delta(a: list, b: list):
    if not a or not b:
        return None
    return sum((x > y) - (x < y) for x in a for y in b) / (len(a) * len(b))


def wilcoxon_exact(a: list, b: list):
    """Wilcoxon signed-rank hai phía, chính xác, cho các cặp (trung bình dự án của hai phương pháp)."""
    if len(a) != len(b) or len(a) < 2:
        return None
    diffs = [x - y for x, y in zip(a, b) if x != y]
    if not diffs:
        return 1.0
    ranks = _average_ranks([abs(d) for d in diffs])
    mu = sum(ranks) / 2
    observed = sum(r for r, d in zip(ranks, diffs) if d > 0)
    extreme = 0
    for signs in itertools.product((0, 1), repeat=len(ranks)):
        if abs(sum(r for r, s in zip(ranks, signs) if s) - mu) >= abs(observed - mu) - 1e-9:
            extreme += 1
    return extreme / 2 ** len(ranks)


COMPARE_METRICS = ["line_pct", "branch_pct", "pass_pct", "tests", "passed", "project_line_pct",
                   "project_branch_pct", "tested_services", "total_tokens", "requests", "generation_seconds",
                   "ai_wait_seconds"]


def compare_rows(rows: list) -> list:
    """GreyTest vs LLM-only: theo dự án (Mann-Whitney + Cliff's delta trên các lượt) và gộp macro (Wilcoxon trên trung bình dự án)."""
    def values(project, method, metric):
        return [float(r[metric]) for r in rows if r["project"] == project and r["method"] == method
                and r.get(metric) is not None]

    projects = sorted({r["project"] for r in rows})
    result = []
    for metric in COMPARE_METRICS:
        grey_means, llm_means = [], []
        for project in projects:
            g, l = values(project, "greytest", metric), values(project, "llm-only", metric)
            mg = round(statistics.mean(g), 2) if g else None
            ml = round(statistics.mean(l), 2) if l else None
            u, p = mann_whitney_exact(g, l)
            delta = cliffs_delta(g, l)
            result.append([project, metric, len(g), mg, len(l), ml,
                           round(mg - ml, 2) if g and l else None, u,
                           round(p, 4) if p is not None else None, round(delta, 3) if delta is not None else None])
            if g and l:
                grey_means.append(statistics.mean(g))
                llm_means.append(statistics.mean(l))
        p = wilcoxon_exact(grey_means, llm_means)
        mg = round(statistics.mean(grey_means), 2) if grey_means else None
        ml = round(statistics.mean(llm_means), 2) if llm_means else None
        result.append(["ALL (macro)", metric, len(grey_means), mg, len(llm_means), ml,
                       round(mg - ml, 2) if grey_means else None, None,
                       round(p, 4) if p is not None else None, None])
    return result


def _write_csv(path: Path, header: list, rows: list) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows([["" if v is None else v for v in row] for row in rows])


def cmd_stats(args) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    total_rules = None
    if args.ground_truth:
        truth = json.loads(Path(args.ground_truth).read_text(encoding="utf-8-sig"))
        total_rules = len(truth["rules"] if isinstance(truth, dict) else truth)
    rows, groups = [], {}
    for generation_file in sorted(Path(args.runs).rglob("generation.json")):
        run_dir = generation_file.parent
        generation = json.loads(generation_file.read_text(encoding="utf-8"))
        measure_file = run_dir / "measure.json"
        measure = json.loads(measure_file.read_text(encoding="utf-8")) if measure_file.exists() else {}
        tests, passed = measure.get("tests"), measure.get("passed")
        row = {**generation, **measure, "run": run_dir.name, "status": generation.get("status"),
               "measure_status": measure.get("status"),
               "pass_pct": percent(passed, tests - passed) if tests else None,
               "req_cov_pct": req_coverage(run_dir / "req-mapping.csv", total_rules)}
        rows.append(row)
        groups.setdefault((row["project"], row["method"]), []).append(run_dir)
    rows.sort(key=lambda r: (r["project"], r["method"], r["run"]))
    _write_csv(out / "runs.csv", RUN_COLUMNS, [[r.get(c) for c in RUN_COLUMNS] for r in rows])
    summary, stability = [], []
    for (project, method), run_dirs in sorted(groups.items()):
        group = [r for r in rows if (r["project"], r["method"]) == (project, method)]
        for metric in SUMMARY_METRICS:
            d = describe([r.get(metric) for r in group])
            summary.append([project, method, metric, *d.values()])
        keys = {run.name: test_method_keys(run / "tests") for run in run_dirs}
        pair_values = []
        for a, b in itertools.combinations(sorted(keys), 2):
            value = pair_stability(keys[a], keys[b])
            stability.append([project, method, a, b, value])
            pair_values.append(value)
        summary.append([project, method, "stability_pct", *describe(pair_values).values()])
    _write_csv(out / "summary.csv", ["project", "method", "metric", "n", "mean", "sd", "median", "min", "max"],
               summary)
    _write_csv(out / "stability.csv", ["project", "method", "run_a", "run_b", "stability_pct"], stability)
    _write_csv(out / "compare.csv", ["project", "metric", "n_greytest", "mean_greytest", "n_llm_only", "mean_llm_only",
                                     "diff", "mann_whitney_u", "p_value", "cliffs_delta"], compare_rows(rows))
    print(f"{len(rows)} runs -> {out}")
    return 0


# ---------------------------------------------------------------- generate

def cmd_generate(args) -> int:
    project = Path(args.project).resolve()
    sources = collect_sources(project)
    pom = next((s["text"] for s in sources if s["path"] == "pom.xml"), "")
    if re.search(r"<modules>\s*<module>", pom):
        # ponytail: chỉ hỗ trợ dự án Maven một module; multi-module cần gom source từng module.
        raise ValueError("Multi-module Maven project is not supported: " + project.name)
    env = load_env(Path(args.env))  # Đọc trước khi tạo run-dir để lỗi cấu hình không để lại thư mục rỗng.
    run_dir = new_run_dir(Path(args.out))
    write_json(run_dir / "source-manifest.json",
               [{k: s[k] for k in ("path", "sha256", "warning")} for s in sources])
    write_json(run_dir / "service-scope.json", service_scope(sources))
    write_json(run_dir / "skipped-resources.json", skipped_resources(project))
    write_json(run_dir / "config.json", {
        "method": args.method, "project": project.name, "model": env.get("LLM_MODEL"),
        "url": env.get("LLM_OPENAI_URL"), "temperature": float(env.get("LLM_TEMPERATURE", "0.3")),
        "max_output": args.max_output, "timeout": args.timeout, "max_resume": args.max_resume,
        "attempts": MAX_ATTEMPTS, "runner_sha256": sha256(Path(__file__).read_bytes()),
        "started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")})
    try:
        if args.method == "llm-only":
            result = generate_llm_only(project, run_dir, env, args.max_output, args.timeout)
        else:
            result = generate_greytest(project, run_dir, args.base_url, args.max_resume)
            # ponytail: model của backend lấy từ cùng .env; runner không đọc được cấu hình container.
            result["model"] = env.get("LLM_MODEL")
    except Exception as error:  # noqa: BLE001 - lượt lỗi vẫn phải có generation.json để vào thống kê
        result = {"status": "FAILED", "error": f"{type(error).__name__}: {error}"[:1000]}
    result.update(method=args.method, project=project.name)
    write_json(run_dir / "generation.json", result)
    print(json.dumps({k: result.get(k) for k in ("method", "status", "requests", "total_tokens",
                                                   "ai_wait_seconds", "generation_seconds", "test_files", "test_methods")}))
    return 0 if result["status"] != "FAILED" else 1


# ---------------------------------------------------------------- matrix

def build_order(projects: list, methods: list, repeats: int, seed: int) -> list:
    """Thứ tự chạy xáo trộn với seed cố định để hai phương pháp xen kẽ theo thời gian (giảm ảnh hưởng tải provider)."""
    order = [{"project": p, "method": m, "repeat": r} for p in projects for m in methods for r in range(1, repeats + 1)]
    random.Random(seed).shuffle(order)
    return order


def _matrix_log(root: Path, **event) -> None:
    event["utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    with open(root / "matrix-log.jsonl", "a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False) + "\n")
    print(json.dumps(event, ensure_ascii=False), flush=True)


def cmd_matrix(args) -> int:
    """Chạy toàn bộ ma trận dự án × phương pháp × lượt; chạy lại lệnh sẽ tiếp tục từ bước chưa xong."""
    root = Path(args.root)
    root.mkdir(parents=True, exist_ok=True)
    paths = {Path(p).resolve().name: Path(p).resolve() for p in args.projects}
    order = build_order(list(paths), args.methods, args.repeats, args.seed)
    order_file = root / "order.json"
    if order_file.exists() and json.loads(order_file.read_text(encoding="utf-8")) != order:
        raise ValueError("order.json differs from current arguments; use a new --root")
    write_json(order_file, order)
    stamp = lambda: datetime.datetime.now().strftime("%Y%m%d-%H%M%S")  # noqa: E731
    for item in order:
        name, method, repeat = item["project"], item["method"], item["repeat"]
        run = root / name / method / f"run-{repeat:02d}"
        if run.exists() and not (run / "generation.json").exists():
            # Bị ngắt giữa chừng do máy/runner (không phải lỗi phương pháp): để riêng, có log, rồi chạy lại.
            target = root / "interrupted" / f"{name}-{method}-run-{repeat:02d}-{stamp()}"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(run), str(target))
            _matrix_log(root, event="interrupted_generation_set_aside", run=str(run), moved_to=str(target))
        if not (run / "generation.json").exists():
            _matrix_log(root, event="generate_start", project=name, method=method, repeat=repeat)
            try:
                code = cmd_generate(argparse.Namespace(
                    project=str(paths[name]), method=method, out=str(run), env=args.env, max_output=args.max_output,
                    timeout=args.timeout, base_url=args.base_url, max_resume=args.max_resume))
            except Exception as error:  # noqa: BLE001 - lỗi cấu hình (VD multi-module): ghi log, chạy mục khác
                _matrix_log(root, event="generate_error", project=name, method=method, repeat=repeat, error=str(error))
                continue
            _matrix_log(root, event="generate_done", project=name, method=method, repeat=repeat, exit=code)
        if (run / "generation.json").exists() and not (run / "measure.json").exists():
            if (run / "sandbox").exists():
                (run / "sandbox").rename(run / f"sandbox.interrupted-{stamp()}")
            _matrix_log(root, event="measure_start", project=name, method=method, repeat=repeat)
            code = cmd_measure(argparse.Namespace(project=str(paths[name]), run=str(run), java_home=args.java_home))
            _matrix_log(root, event="measure_done", project=name, method=method, repeat=repeat, exit=code)
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="GreyTest vs LLM-only experiment runner")
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("generate")
    gen.add_argument("--project", required=True)
    gen.add_argument("--method", required=True, choices=("llm-only", "greytest"))
    gen.add_argument("--out", required=True)
    gen.add_argument("--env", default=str(Path(__file__).resolve().parents[2] / ".env"))
    gen.add_argument("--max-output", type=int, default=64000)
    gen.add_argument("--timeout", type=int, default=900)
    gen.add_argument("--base-url", default="http://localhost:8080")
    gen.add_argument("--max-resume", type=int, default=0,
                     help="Số lượt resume mỗi stage; mặc định 0 để đo một lượt pipeline")
    meas = sub.add_parser("measure")
    meas.add_argument("--project", required=True)
    meas.add_argument("--run", required=True)
    meas.add_argument("--java-home", default=DEFAULT_JAVA_HOME)
    st = sub.add_parser("stats")
    st.add_argument("--runs", required=True)
    st.add_argument("--out", required=True)
    st.add_argument("--ground-truth")
    mat = sub.add_parser("matrix")
    mat.add_argument("--projects", nargs="+", required=True)
    mat.add_argument("--methods", nargs="+", default=["llm-only", "greytest"], choices=("llm-only", "greytest"))
    mat.add_argument("--repeats", type=int, default=5)
    mat.add_argument("--seed", type=int, default=20261116)
    mat.add_argument("--root", required=True)
    for action in gen._actions:
        if action.dest in ("env", "max_output", "timeout", "base_url", "max_resume"):
            mat._add_action(action)
    mat.add_argument("--java-home", default=DEFAULT_JAVA_HOME)
    args = parser.parse_args(argv)
    return {"generate": cmd_generate, "measure": cmd_measure, "stats": cmd_stats,
            "matrix": cmd_matrix}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
