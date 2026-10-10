"""Measure frozen OSS corpus through local GreyTest production analysis API, without AI."""
import argparse
import hashlib
import json
import secrets
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


BASE = "http://127.0.0.1:18081"
COUNTERS = ("totalClasses", "totalMethods", "totalEndpoints", "totalRelations",
            "totalControllerServiceRelations", "existingTestFiles", "totalProductionFiles",
            "parsedProductionFiles", "failedParseFiles", "failedParseFilePaths")


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def scope_classes(analysis, relative_path):
    # Đối chiếu đường dẫn đóng băng, không thay số file lexical thành kết quả AST.
    suffix = relative_path.replace("\\", "/")
    return [item for item in analysis.get("classes", [])
            if item.get("filePath", "").replace("\\", "/").endswith(suffix)]


class CorpusRun:
    def __init__(self, output):
        self.output = output
        output.mkdir(parents=True, exist_ok=False)
        self.events = []
        self.token = None

    def call(self, method, route, payload=None, content_type="application/json", save=None):
        headers = {"Content-Type": content_type}
        if self.token:
            headers["Authorization"] = self.token
        if isinstance(payload, dict):
            payload = json.dumps(payload).encode()
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(urllib.request.Request(BASE + route, data=payload,
                    headers=headers, method=method), timeout=300) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        elapsed = 1000 * (time.perf_counter() - started)
        self.events.append(dict(method=method, route=route, status=status,
                                milliseconds=elapsed, response_sha256=hashlib.sha256(raw).hexdigest()))
        write_json(self.output / "http-events.json", self.events)
        if save:
            (self.output / save).write_bytes(raw)
        return status, json.loads(raw), elapsed

    def run(self, datasets):
        status, registration, _ = self.call("POST", "/api/auth/register", dict(
            email="corpus-" + uuid.uuid4().hex + "@chapter5.test",
            password=secrets.token_urlsafe(24), fullName="Chapter5 corpus preflight"))
        if status != 200:
            raise RuntimeError("Registration returned " + str(status))
        self.token = "Bearer " + registration["token"]
        rows = []
        for dataset in datasets:
            transport_failed = False
            row = dict(dataset_id=dataset["id"], source_tree_commit=dataset["source_tree_commit"],
                       checkout_head=dataset["checkout_head"], analysis_scope=dataset["analysis_scope"],
                       provider_calls=0, result="Fail")
            rows.append(row)
            try:
                archive = Path(dataset["staged_archive"])
                payload = archive.read_bytes()
                digest = hashlib.sha256(payload).hexdigest()
                row["archive_sha256"] = digest
                if digest != dataset["staged_archive_sha256"]:
                    raise ValueError("Frozen archive SHA256 mismatch")
                boundary = "corpus-" + uuid.uuid4().hex
                body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="corpus-'
                        + dataset["id"] + '.zip"\r\nContent-Type: application/zip\r\n\r\n').encode() + payload + (
                        '\r\n--' + boundary + '--\r\n').encode()
                status, upload, elapsed = self.call("POST", "/api/projects/upload", body,
                    "multipart/form-data; boundary=" + boundary, dataset["id"] + "-upload.json")
                row.update(upload_http_status=status, upload_ms=elapsed)
                if status != 201:
                    raise RuntimeError("Upload returned " + str(status))
                row["project_id"] = upload["id"]
                prefix = "/api/projects/" + str(upload["id"]) + "/"
                status, analysis, elapsed = self.call("POST", prefix + "analyze", save=dataset["id"] + "-analysis.json")
                row.update(analyze_http_status=status, analyze_ms=elapsed)
                if status != 200:
                    raise RuntimeError("Analyze returned " + str(status))
                row["counters"] = {key: analysis[key] for key in COUNTERS}
                status, manifest, _ = self.call("GET", prefix + "analysis/manifest", save=dataset["id"] + "-manifest.json")
                row["manifest_http_status"] = status
                if status != 200:
                    raise RuntimeError("Manifest returned " + str(status))
                row["manifest_counts"] = {key: len(value) for key, value in manifest.items() if isinstance(value, list)}
                status, services, _ = self.call("GET", prefix + "services", save=dataset["id"] + "-services.json")
                row["services_http_status"] = status
                if status != 200:
                    raise RuntimeError("Services returned " + str(status))
                selected = scope_classes(analysis, dataset["analysis_scope"])
                row["selected_scope_classes"] = selected
                row["selected_scope_found"] = bool(selected)
                row["selected_service_ready"] = any(item.get("classType") == "SERVICE" and item.get("methods") for item in selected)
                row["result"] = "Pass" if row["selected_service_ready"] and analysis["failedParseFiles"] == 0 else "Fail"
            except (ValueError, RuntimeError, OSError, KeyError) as error:
                row["error"] = str(error)
                # Timeout không chứng minh server đã dừng; không tạo workload tiếp theo.
                transport_failed = isinstance(error, OSError)
                if transport_failed:
                    row["server_completion"] = "UNKNOWN"
            finally:
                write_json(self.output / "summary.json", rows)
                print(dataset["id"] + ": " + row["result"], flush=True)
            if transport_failed:
                break
        return rows


def self_check():
    from unittest.mock import patch

    assert len(scope_classes({"classes": [{"filePath": "x\\src\\main\\java\\S.java"}]},
                             "src/main/java/S.java")) == 1
    assert scope_classes({"classes": [{"filePath": "src/main/java/Other.java"}]},
                         "src/main/java/S.java") == []
    archive = Path(__file__)
    dataset = dict(id="self-check", source_tree_commit="fixture", checkout_head="fixture",
                   analysis_scope="fixture", staged_archive=str(archive),
                   staged_archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
    run = CorpusRun.__new__(CorpusRun)
    run.output = Path("self-check-no-files")
    with patch.object(run, "call", side_effect=[(200, {"token": "fixture"}, 0),
                                               TimeoutError("fixture timeout")]) as mocked, \
            patch(__name__ + ".write_json"), patch("builtins.print"):
        rows = run.run([dataset, dataset])
    assert len(rows) == 1 and mocked.call_count == 2
    assert rows[0]["server_completion"] == "UNKNOWN" and rows[0]["result"] == "Fail"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        print("self-check Pass")
    else:
        if args.output is None:
            parser.error("--output required")
        manifest = json.loads(Path(__file__).with_name("datasets.json").read_text(encoding="utf-8-sig"))
        rows = CorpusRun(args.output).run(manifest["datasets"])
        raise SystemExit(0 if all(row["result"] == "Pass" for row in rows) else 1)
