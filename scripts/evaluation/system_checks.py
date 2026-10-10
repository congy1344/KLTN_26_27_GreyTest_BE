"""Local chapter-5 fixture HTTP measurements. Never contacts a paid provider."""
import argparse
import concurrent.futures
import csv
import hashlib
import io
import json
import math
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PurePosixPath

BASE = "http://127.0.0.1:18081"
SOURCE = "package sample; import org.springframework.stereotype.Service; @Service public class PriceService { public int discount(int amount) { return amount >= 100 ? 10 : 0; } }"
RULE = "For amount equal to 100, discount returns 10; for amount below 100, discount returns 0."


def percentiles(values):
    ordered = sorted(values)
    if not ordered:
        raise ValueError("No observations")
    return statistics.median(ordered), ordered[math.ceil(.95 * len(ordered)) - 1]


def source_zip(count=1):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>chapter5</artifactId><version>1</version></project>")
        for index in range(count):
            name = "PriceService" if index == 0 else "PriceService" + str(index)
            archive.writestr("src/main/java/sample/" + name + ".java", SOURCE.replace("PriceService", name))
        archive.writestr("src/test/java/sample/PriceServiceExistingTest.java", "package sample; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.assertEquals; class PriceServiceExistingTest { @Test void belowBoundary() { assertEquals(0,new PriceService().discount(99)); } }")
    return data.getvalue()


def multipart(filename, payload):
    boundary = "chapter5-" + uuid.uuid4().hex
    body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + payload + ('\r\n--' + boundary + '--\r\n').encode()
    return body, "multipart/form-data; boundary=" + boundary


def downloaded_sources(payload, files):
    expected = {item["filePath"]: item["sourceCode"].encode() for item in files}
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP entries"
        for name in names:
            path = PurePosixPath(name)
            assert "\\" not in name and ":" not in name and not path.is_absolute() and ".." not in path.parts, name
        java = {name: archive.read(name) for name in names if name.endswith(".java")}
    assert java == expected, "Downloaded Java files differ from assembled API sources"
    return java


class Checks:
    def __init__(self, output):
        self.output = output
        output.mkdir(parents=True, exist_ok=False)
        self.events = []
        self.token = None

    def call(self, method, route, payload=None, token=True, content_type=None, save=True):
        headers = {}
        if token:
            headers["Authorization"] = self.token if token is True else token
        if payload is not None and content_type is None:
            payload = json.dumps(payload).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(urllib.request.Request(BASE + route, data=payload, headers=headers, method=method), timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            self.events.append(dict(method=method, route=route.split("?")[0], status="UNKNOWN", milliseconds=(time.perf_counter()-started)*1000, failure_category=type(error).__name__))
            self.finish_failure(type(error).__name__)
            raise
        elapsed = (time.perf_counter() - started) * 1000
        if save:
            self.events.append(dict(method=method, route=route, status=status, milliseconds=elapsed, response_sha256=hashlib.sha256(raw).hexdigest()))
            if not route.startswith("/api/auth/"):
                (self.output / (str(len(self.events)).zfill(4) + "-response.bin")).write_bytes(raw)
        try:
            data = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            data = raw
        return status, data, elapsed

    def finish_failure(self, category):
        # Không lưu thông báo exception có thể chứa token/password từ provider hoặc request.
        (self.output / "http-events.json").write_text(json.dumps(self.events, indent=2), encoding="utf-8")
        (self.output / "failure.json").write_text(json.dumps(dict(error_type=category, failed_phase=self.events[-1]["route"] if self.events else "initialization"), indent=2), encoding="utf-8")
        (self.output / "summary.json").write_text(json.dumps(dict(result="Blocked" if category in ("URLError", "TimeoutError", "OSError") else "Fail", error_type=category, observations="Partial observations retained in raw artifacts; incomplete run excluded from aggregate metrics"), indent=2), encoding="utf-8")

    def register(self):
        status, data, _ = self.call("POST", "/api/auth/register", dict(email=uuid.uuid4().hex + "@chapter5.test", password="chapter5-test-password", fullName="Chapter5 fixture"), token=False)
        assert status == 200, status
        self.last_user_id = data["user"]["id"]
        return "Bearer " + data["token"]

    def extra_security(self):
        self.token = self.register()
        project, _ = self.upload(source_zip())
        prefix = "/api/projects/" + str(project) + "/"
        assert self.call("POST", prefix + "analyze")[0] == 200
        analysis = self.call("GET", prefix + "analysis")[1]
        method = analysis["classes"][0]["methods"][0]["id"]
        assert self.call("POST", prefix + "business-rules", dict(methodId=method, description=RULE, sourceBranchId="TERNARY-1"))[0] == 201
        assert self.call("POST", prefix + "business-rules/approve")[0] == 200
        for resource, stage in (("test-plans", "TEST_PLAN"), ("test-cases", "TEST_CASE"), ("unit-tests", "UNIT_TEST")):
            self.generate(project, resource, stage)
            if resource != "unit-tests":
                assert self.call("POST", prefix + resource + "/approve")[0] == 200
        results = []
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("../chapter5-safe-sentinel.txt", "inert traversal fixture")
            archive.writestr("pom.xml", "<project/>")
        body, kind = multipart("traversal.zip", data.getvalue())
        status, _, _ = self.call("POST", "/api/projects/upload", body, content_type=kind)
        results.append(dict(check="zip-traversal", expected=400, actual=status, result="Pass" if status == 400 else "Fail"))
        requests = []
        class Sentinel(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"safe external fixture")
            def log_message(self, *args):
                pass
        with ThreadingHTTPServer(("127.0.0.1", 0), Sentinel) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            address = "http://127.0.0.1:" + str(server.server_port) + "/safe-sentinel"
            for label, declaration in (("xml-general-entity", '<!ENTITY safe SYSTEM "' + address + '">'), ("xml-parameter-entity", '<!ENTITY % safe SYSTEM "' + address + '"> %safe;')):
                before = len(requests)
                xml = '<!DOCTYPE report [' + declaration + ']><report name="safe"><counter type="LINE" missed="0" covered="1"/><counter type="BRANCH" missed="0" covered="2"/></report>'
                if label == "xml-general-entity":
                    xml = xml.replace('<counter type="LINE"', '&safe;<counter type="LINE"')
                body, kind = multipart("jacoco.xml", xml.encode())
                status, _, _ = self.call("POST", prefix + "coverage/upload", body, content_type=kind)
                contacted = len(requests) - before
                results.append(dict(check=label, expected="no external HTTP fetch", actual=status, external_requests=contacted, result="Pass" if contacted == 0 and status in (201, 400) else "Fail"))
            server.shutdown()
            worker.join(timeout=2)
        disabled = self.register()
        user_id = int(self.last_user_id)
        command = ["C:/Program Files/PostgreSQL/17/bin/psql.exe", "-h", "127.0.0.1", "-p", "55439", "-U", "chapter5_test", "-d", "chapter5_system", "-v", "ON_ERROR_STOP=1", "-c", "UPDATE auth_user SET enabled=false WHERE id=" + str(user_id)]
        execution = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
        assert execution.returncode == 0, "Could not disable own isolated fixture actor"
        status, _, _ = self.call("GET", "/api/projects", token=disabled)
        results.append(dict(check="disabled-user", expected=403, actual=status, result="Pass" if status == 403 else "Fail"))
        (self.output / "security.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        (self.output / "http-events.json").write_text(json.dumps(self.events, indent=2), encoding="utf-8")
        return results

    def upload(self, payload):
        body, kind = multipart("chapter5.zip", payload)
        status, data, elapsed = self.call("POST", "/api/projects/upload", body, content_type=kind)
        assert status == 201, (status, data)
        return data["id"], elapsed

    def generate(self, project, resource, stage):
        prefix = "/api/projects/" + str(project) + "/"
        status, data, _ = self.call("POST", prefix + resource + "/generate")
        assert status == 202, (status, data)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status, data, _ = self.call("GET", prefix + "generation-progress/" + stage)
            if data.get("status") in ("COMPLETED", "FAILED", "PAUSED"):
                assert data["status"] == "COMPLETED", data
                return
            time.sleep(.05)
        raise AssertionError("Generation timed out")

    def run(self, measure=True):
        self.token = self.register()
        other = self.register()
        payload = source_zip()
        (self.output / "source.zip").write_bytes(payload)
        project, _ = self.upload(payload)
        prefix = "/api/projects/" + str(project) + "/"
        assert self.call("POST", prefix + "analyze")[0] == 200
        status, analysis, _ = self.call("GET", prefix + "analysis")
        assert status == 200 and analysis["totalClasses"] == 1 and analysis["existingTestFiles"] == 1, analysis
        method = analysis["classes"][0]["methods"][0]["id"]
        assert self.call("POST", prefix + "business-rules", dict(methodId=method, description=RULE, sourceBranchId="TERNARY-1"))[0] == 201
        assert self.call("POST", prefix + "business-rules/approve")[0] == 200
        for resource, stage in (("test-plans", "TEST_PLAN"), ("test-cases", "TEST_CASE"), ("unit-tests", "UNIT_TEST")):
            self.generate(project, resource, stage)
            if resource != "unit-tests":
                assert self.call("POST", prefix + resource + "/approve")[0] == 200
        status, tests, _ = self.call("GET", prefix + "unit-tests")
        assert status == 200 and "assertEquals(10, sut.discount(100))" in tests[0]["sourceCode"]
        (self.output / "generated-test.java").write_text(tests[0]["sourceCode"], encoding="utf-8")
        status, download, _ = self.call("GET", prefix + "unit-tests/download")
        assert status == 200 and zipfile.is_zipfile(io.BytesIO(download))
        (self.output / "unit-tests.zip").write_bytes(download)
        files = self.call("GET", prefix + "unit-tests/files")[1]
        downloaded = downloaded_sources(download, files)
        replay = self.output / "replay"
        replay.mkdir(exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            archive.extractall(replay)
        test_dir = replay / "src/test/java/sample"
        # Vòng 1 cố ý chỉ chạy test biên: suite existing đầy đủ đã phủ cả hai nhánh.
        existing = test_dir / "PriceServiceExistingTest.java"
        existing_source = existing.read_bytes()
        existing.unlink()
        for name, source in downloaded.items():
            destination = replay / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source)
        (replay / "pom.xml").write_text('''<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>chapter5</artifactId><version>1</version>
<properties><maven.compiler.release>17</maven.compiler.release></properties>
<dependencies><dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>6.1.13</version></dependency><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.3</version><scope>test</scope></dependency></dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin><plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version></plugin></plugins></build></project>''', encoding="utf-8")
        import os
        environment = os.environ.copy()
        environment["JAVA_HOME"] = "C:/Users/ADMIN/tools/jdk-17.0.19+10"
        execution = subprocess.run(["C:/Users/ADMIN/tools/apache-maven-3.9.9/bin/mvn.cmd", "-o", "org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent", "test", "org.jacoco:jacoco-maven-plugin:0.8.12:report"], cwd=replay, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        (self.output / "replay.log").write_bytes(execution.stdout)
        assert execution.returncode == 0, "Downloaded generated test replay failed: see replay.log"
        coverage_xml = (replay / "target/site/jacoco/jacoco.xml").read_bytes()
        body, kind = multipart("jacoco.xml", coverage_xml)
        status, coverage, _ = self.call("POST", prefix + "coverage/upload", body, content_type=kind)
        assert status == 201, (status, coverage)
        assert coverage["branchCoverage"] == 50 and coverage["gaps"], coverage
        before_cases = self.call("GET", prefix + "test-cases")[1]
        before_tests = self.call("GET", prefix + "unit-tests")[1]
        status, refined, _ = self.call("POST", prefix + "coverage/refine")
        assert status == 200 and refined["round"] == 2, (status, refined)
        supplemental = refined["unitTests"]
        assert len(supplemental) == 1 and supplemental[0]["generationType"] == "SUPPLEMENT_EXISTING_TEST", refined
        assert "assertEquals(0, sut.discount(99))" in supplemental[0]["sourceCode"], supplemental
        after_cases = self.call("GET", prefix + "test-cases")[1]
        after_tests = self.call("GET", prefix + "unit-tests")[1]
        assert all(case in after_cases for case in before_cases)
        assert all(test in after_tests for test in before_tests)
        status, download2, _ = self.call("GET", prefix + "unit-tests/download")
        assert status == 200
        (self.output / "unit-tests-round2.zip").write_bytes(download2)
        for name, source in downloaded_sources(download2, self.call("GET", prefix + "unit-tests/files")[1]).items():
            destination = replay / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source)
        existing.write_bytes(existing_source)
        execution = subprocess.run(["C:/Users/ADMIN/tools/apache-maven-3.9.9/bin/mvn.cmd", "-o", "clean", "org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent", "test", "org.jacoco:jacoco-maven-plugin:0.8.12:report"], cwd=replay, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        (self.output / "replay-round2.log").write_bytes(execution.stdout)
        assert execution.returncode == 0, "Round2 replay failed"
        body, kind = multipart("jacoco.xml", (replay / "target/site/jacoco/jacoco.xml").read_bytes())
        status, coverage2, _ = self.call("POST", prefix + "coverage/upload", body, content_type=kind)
        assert status == 201 and coverage2["branchCoverage"] == 100 and coverage2["round"] == 2, coverage2
        for route in ("traceability", "export?format=json", "export?format=markdown"):
            assert self.call("GET", prefix + route)[0] == 200
        security = []
        for label, token, expected, route in (("no-header", False, 401, prefix + "analysis"), ("bad-token", "Bearer invalid", 401, prefix + "analysis"), ("other-owner", other, 403, prefix + "analysis"), ("user-admin", True, 403, "/api/admin/users")):
            status, _, _ = self.call("GET", route, token=token)
            security.append(dict(check=label, expected=expected, actual=status, result="Pass" if status == expected else "Fail"))
        reads = []
        for concurrency in ((1, 3, 5, 10) if measure else ()):
            for _ in range(10):
                self.call("GET", prefix + "analysis", save=False)
            def read(index):
                route = prefix + ("analysis" if index % 2 == 0 else "traceability")
                status, _, elapsed = self.call("GET", route, save=False)
                return dict(concurrency=concurrency, index=index, route=route, status=status, milliseconds=elapsed)
            start = time.perf_counter()
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
                observed = list(pool.map(read, range(100)))
            duration = time.perf_counter() - start
            median, p95 = percentiles([row["milliseconds"] for row in observed])
            reads.append(dict(concurrency=concurrency, requests=100, duration_seconds=duration, median_ms=median, p95_ms=p95, requests_per_second=100/duration, error_rate=sum(row["status"] != 200 for row in observed)/100))
            (self.output / ("read-c" + str(concurrency) + ".json")).write_text(json.dumps(observed, indent=2), encoding="utf-8")
        scales = []
        for count in ((10, 100, 500) if measure else ()):
            payload = source_zip(count)
            (self.output / ("scale-" + str(count) + ".zip")).write_bytes(payload)
            for repeat in range(3):
                scaled, upload_ms = self.upload(payload)
                route = "/api/projects/" + str(scaled) + "/"
                status, _, elapsed = self.call("POST", route + "analyze")
                _, result, _ = self.call("GET", route + "analysis")
                scales.append(dict(classes=count, repeat=repeat+1, zip_sha256=hashlib.sha256(payload).hexdigest(), bytes=len(payload), upload_ms=upload_ms, analyze_ms=elapsed, status=status, actual_classes=result.get("totalClasses"), actual_methods=result.get("totalMethods"), failed_parse_files=result.get("failedParseFiles"), correct=status==200 and result.get("totalClasses")==count and result.get("totalMethods")==count and result.get("failedParseFiles")==0))
        summary = dict(project_id=project, provider="deterministic test fixture", human_uat="Pending", reads=reads, scales=scales, security=security, coverage=coverage, coverage_round2=coverage2, flow1="Pass", flow2="Pass: controlled boundary-only round1 subset; original existing suite already covers both branches", existing_source_sha256=hashlib.sha256(existing_source).hexdigest())
        (self.output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        (self.output / "http-events.json").write_text(json.dumps(self.events, indent=2), encoding="utf-8")
        return summary


def self_check():
    import tempfile
    with tempfile.TemporaryDirectory() as temporary:
        try:
            Checks(Path(temporary))
            raise AssertionError("Existing evidence directory accepted")
        except FileExistsError:
            pass
        from unittest.mock import patch
        for partial in (False, True):
            checks = Checks(Path(temporary) / str(partial))
            if partial:
                checks.events.append(dict(method="GET", route="/api/projects", status=200, milliseconds=1))
            with patch("urllib.request.urlopen", side_effect=OSError("sentinel-password-key")):
                try:
                    checks.call("GET", "/api/projects", token=False)
                    raise RuntimeError("Transport error accepted")
                except OSError:
                    pass
            assert checks.events[-1]["status"] == "UNKNOWN"
            assert (checks.output / "summary.json").exists()
            assert "sentinel-password-key" not in "".join(path.read_text() for path in checks.output.glob("*.json"))
    for name, content, expected in (("../escape.java", b"x", []), ("/root.java", b"x", []), ("C:\\escape.java", b"x", []), ("src/test/java/T.java", b"bad", [dict(filePath="src/test/java/T.java", sourceCode="good")])):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr(name, content)
        try:
            downloaded_sources(data.getvalue(), expected)
            raise RuntimeError("Invalid archive accepted")
        except AssertionError:
            pass
    assert percentiles(range(1, 101)) == (50.5, 95)
    try:
        percentiles([])
        raise AssertionError("Empty observations accepted")
    except ValueError:
        pass
    archive = zipfile.ZipFile(io.BytesIO(source_zip(3)))
    assert len([name for name in archive.namelist() if name.startswith("src/main/")]) == 3
    assert b"PriceService2" in archive.read("src/main/java/sample/PriceService2.java")
    body, kind = multipart("fixture.zip", b"abc")
    assert b'name="file"' in body and b"abc\r\n--" in body and kind.startswith("multipart/form-data")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--skip-measurements", action="store_true")
    parser.add_argument("--extra-security", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        print("Self-check Pass")
    else:
        if args.output is None:
            parser.error("--output required")
        checks = Checks(args.output)
        try:
            print(json.dumps(checks.extra_security() if args.extra_security else checks.run(not args.skip_measurements), indent=2))
        except Exception as error:
            checks.finish_failure(type(error).__name__)
            raise SystemExit("Run failed: " + type(error).__name__) from None
