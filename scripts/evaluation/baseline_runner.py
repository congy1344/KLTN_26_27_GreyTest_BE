"""One-call local baseline generation; dry-run by default, never executes generated Java."""
import argparse
import hashlib
import json
import math
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath


BACKEND = Path(__file__).resolve().parents[2]
METHODS = ("llm-only", "prompt-based")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2).encode("utf-8")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def git(root, *arguments):
    return subprocess.run(["git", "-C", str(root), *arguments], check=True,
                          capture_output=True).stdout


def checked_source_path(root, name):
    path = PurePosixPath(name)
    if (path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name
            or any(ord(c) < 32 for c in name) or path.suffix != ".java"
            or "/src/main/java/" not in "/" + name
            or any(part.startswith(".") for part in path.parts)):
        raise ValueError("Source whitelist accepts only relative production Java paths")
    resolved = (root / name).resolve()
    if not resolved.is_relative_to(root):
        raise ValueError("Source escapes frozen checkout")
    return resolved


def source_context(dataset, snapshot):
    """Không tự quét repository: chỉ gửi whitelist đã đóng băng và đối chiếu Git."""
    for key in ("dataset_id", "commit", "analysis_scope"):
        expected = dataset["id"] if key == "dataset_id" else dataset[key]
        if snapshot.get(key) != expected:
            raise ValueError("Source snapshot binding mismatch: " + key)
    if dataset.get("provenance_status") != "FROZEN_SOURCE":
        raise ValueError("Dataset source is not frozen")
    root = Path(dataset["source_path"]).resolve()
    if git(root, "rev-parse", "HEAD").decode().strip() != dataset["commit"]:
        raise ValueError("Checkout HEAD differs from frozen commit")
    files = snapshot.get("files", [])
    names = [item["path"] for item in files]
    if not files or len(set(names)) != len(names) or dataset["analysis_scope"] not in names:
        raise ValueError("Whitelist must contain unique files including analysis_scope")
    inventory = {item["path"]: item for item in read_json(dataset["blob_inventory"])}
    sources = []
    for item in files:
        name = item["path"]
        path = checked_source_path(root, name)
        raw = path.read_bytes()
        frozen = git(root, "show", dataset["commit"] + ":" + name)
        entry = inventory.get(name)
        if not entry:
            raise ValueError("Source is absent from frozen inventory")
        actual = raw.replace(b"\r\n", b"\n") if entry["comparison"] == "CRLF_TO_LF_NORMALIZED" else raw
        if (digest(raw) != item["sha256"] or item["sha256"] != entry["sha256"]
                or actual != frozen):
            raise ValueError("Source differs from frozen Git/inventory/snapshot: " + name)
        if name == dataset["analysis_scope"] and item["sha256"] != dataset["scope_sha256"]:
            raise ValueError("Service scope hash mismatch")
        sources.append({"path": name, "sha256": item["sha256"], "text": raw.decode("utf-8")})
    return sources


def approved_rules(path, dataset, snapshot_hash):
    oracle = read_json(path)
    if (oracle.get("human_review_verified") is not True
            or not oracle.get("reviewer") or not oracle.get("reviewed_at")
            or not oracle.get("independent_source")
            or oracle.get("dataset_id") != dataset["id"]
            or oracle.get("commit") != dataset["commit"]
            or oracle.get("analysis_scope") != dataset["analysis_scope"]
            or oracle.get("source_snapshot_sha256") != snapshot_hash):
        raise ValueError("Independent BR approval and source binding are required")
    evidence_path = Path(path).resolve().parent / oracle["review_evidence_file"]
    if digest(evidence_path.read_bytes()) != oracle.get("review_evidence_sha256"):
        raise ValueError("Human review evidence hash mismatch")
    rules = oracle.get("rules")
    if (not isinstance(rules, list) or not rules
            or any(not isinstance(r, dict) or not r.get("id") or not r.get("description") for r in rules)
            or len({r["id"] for r in rules}) != len(rules)):
        raise ValueError("Approved BR must have unique IDs and nonempty descriptions")
    return [{"id": r["id"], "description": r["description"]} for r in rules]


def make_prompt(sources, rules=None):
    prompt = ("Generate test cases and complete JUnit 5 + Mockito unit test file contents in ONE response. "
              "Return only a JSON object with nonempty arrays cases and unit_tests. "
              "Each case must contain id, test_type, description, preconditions, test_data, expected_result, "
              "priority. Each unit_tests item must contain file_name and code (complete Java source). "
              "Do not perform repairs or multiple steps. Source names and raw contents follow:\n"
              + json.dumps(sources, ensure_ascii=False))
    if rules is not None:
        prompt += ("\nApproved independent business rules determine expected behavior, even when source disagrees:\n"
                   + json.dumps(rules, ensure_ascii=False))
    return prompt


def live_config():
    # Không đọc .env trong dry-run; chỉ allowlist key cần cho protocol hiện tại.
    keys = {"LLM_PROVIDER", "LLM_API_KEY", "LLM_MODEL", "LLM_OPENAI_MODEL", "LLM_TEMPERATURE",
            "LLM_MAX_TOKENS", "LLM_TIMEOUT_SECONDS", "LLM_OPENAI_URL", "LLM_OPENAI_JSON_MODE"}
    config = {}
    env_file = BACKEND / ".env"
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            name, sep, value = line.strip().partition("=")
            if sep and name in keys:
                config[name] = value.strip().strip("\"'")
    config.update({k: os.environ[k] for k in keys if k in os.environ})
    if config.get("LLM_PROVIDER") != "openai" or not config.get("LLM_API_KEY", "").strip():
        raise ValueError("This baseline supports the configured OpenAI-compatible client only")
    endpoint = config.get("LLM_OPENAI_URL", "https://api.vietapi.tech/v1/chat/completions")
    url = urllib.parse.urlsplit(endpoint)
    if url.scheme != "https" or not url.netloc or url.username or url.password or url.query or url.fragment:
        raise ValueError("Provider endpoint requires HTTPS without embedded credentials/query")
    options = {"model": config.get("LLM_OPENAI_MODEL", config.get("LLM_MODEL", "gpt-4o-mini")),
               "temperature": float(config.get("LLM_TEMPERATURE", "0.3")),
               "max_tokens": int(config.get("LLM_MAX_TOKENS", "16384")),
               "json_mode": config.get("LLM_OPENAI_JSON_MODE", "true").lower() == "true",
               "timeout_seconds": int(config.get("LLM_TIMEOUT_SECONDS", "60"))}
    if (not math.isfinite(options["temperature"]) or options["max_tokens"] <= 0
            or options["timeout_seconds"] <= 0):
        raise ValueError("Invalid provider options")
    return endpoint, config["LLM_API_KEY"], options


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def call_provider(prompt, endpoint, key, options):
    body = {"model": options["model"], "messages": [{"role": "user", "content": prompt}],
            "temperature": options["temperature"], "max_tokens": options["max_tokens"]}
    if options["json_mode"]:
        body["response_format"] = {"type": "json_object"}
    request = urllib.request.Request(endpoint, data=encoded(body),
                                     headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=options["timeout_seconds"]) as response:
            return response.status, response.read(), None
    except urllib.error.HTTPError as error:
        return error.code, error.read(), "HTTP_ERROR"
    except (urllib.error.URLError, TimeoutError, OSError):
        # Không lưu exception text vì proxy/URL có thể chứa thông tin xác thực.
        return None, b"", "TRANSPORT_ERROR"


def parse_response(raw):
    root = json.loads(raw)
    choices = root.get("choices", [])
    text = choices[0].get("message", {}).get("content", "") if choices else ""
    text = text or root.get("output_text", "")
    if not isinstance(text, str) or not text.strip():
        raise ValueError("MISSING_OUTPUT")
    result = json.loads(text)
    if (not isinstance(result, dict) or not isinstance(result.get("cases"), list) or not result["cases"]
            or not isinstance(result.get("unit_tests"), list) or not result["unit_tests"]
            or any(not isinstance(case, dict) or not all(k in case for k in
                   ("id", "test_type", "description", "preconditions", "test_data", "expected_result", "priority"))
                   for case in result["cases"])
            or any(not isinstance(unit, dict) or not isinstance(unit.get("code"), str) or not unit["code"].strip()
                   or not isinstance(unit.get("file_name"), str) or not unit["file_name"].endswith(".java")
                   for unit in result["unit_tests"])):
        raise ValueError("INVALID_OUTPUT_SCHEMA")
    return result


def token_usage(raw):
    try:
        usage = json.loads(raw).get("usage", {})
        values = {k: usage.get(k) if type(usage.get(k)) is int and usage[k] >= 0 else None
                  for k in ("prompt_tokens", "completion_tokens", "total_tokens")}
        if any(v is not None for v in values.values()):
            return {"source": "PROVIDER", **values}
    except (ValueError, AttributeError, TypeError):
        pass
    return {"source": "UNKNOWN", "prompt_tokens": None, "completion_tokens": None, "total_tokens": None}


def run(args):
    dataset = next(d for d in read_json(args.datasets)["datasets"] if d["id"] == args.dataset)
    snapshot_bytes = Path(args.source_snapshot).read_bytes()
    snapshot = json.loads(snapshot_bytes.decode("utf-8-sig"))
    snapshot_hash = digest(snapshot_bytes)
    sources = source_context(dataset, snapshot)
    rules = None
    if args.method == "prompt-based":
        if not args.approved_br:
            raise ValueError("Prompt-based requires --approved-br")
        rules = approved_rules(args.approved_br, dataset, snapshot_hash)
    elif args.approved_br:
        raise ValueError("LLM-only rejects BR input")
    prompt = make_prompt([{k: s[k] for k in ("path", "text")} for s in sources], rules)
    options, endpoint, key = None, None, None
    if args.live:
        build = snapshot.get("build", {})
        if (dataset.get("ready_for_paid_runs") is not True or dataset.get("build_status") != "PASS"
                or dataset.get("oracle_status") != "HUMAN_REVIEW_VERIFIED" or build.get("status") != "PASS"):
            raise ValueError("Live run blocked by dataset build/oracle eligibility gates")
        evidence = Path(args.source_snapshot).resolve().parent / build["evidence_file"]
        if digest(evidence.read_bytes()) != build.get("evidence_sha256"):
            raise ValueError("Build evidence hash mismatch")
        # Cả LLM-only cũng cần oracle độc lập để đánh giá sau sinh, nhưng không đưa vào prompt.
        if not args.evaluation_oracle:
            raise ValueError("Live run requires independently reviewed --evaluation-oracle")
        approved_rules(args.evaluation_oracle, dataset, snapshot_hash)
        if args.method == "prompt-based" and Path(args.evaluation_oracle).read_bytes() != Path(args.approved_br).read_bytes():
            raise ValueError("Prompt-based input and evaluation oracle must be identical")
        endpoint, key, options = live_config()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    (output / "prompt.txt").write_text(prompt, encoding="utf-8")
    (output / "source-snapshot.json").write_bytes(encoded({k: snapshot[k] for k in
                                                         ("dataset_id", "commit", "analysis_scope", "files")}))
    metadata = {"method": args.method, "dataset_id": dataset["id"], "commit": dataset["commit"],
                "analysis_scope": dataset["analysis_scope"], "source_snapshot_sha256": snapshot_hash,
                "sources": [{k: s[k] for k in ("path", "sha256")} for s in sources],
                "prompt_sha256": digest(prompt.encode("utf-8")), "configured_options": options,
                "options_sha256": digest(encoded(options)) if options else None,
                "provider_endpoint_sha256": digest(endpoint.encode()) if endpoint else None,
                "model": options["model"] if options else "N/A_DRY_RUN",
                "approved_br_sha256": digest(Path(args.approved_br).read_bytes()) if args.approved_br else None,
                "evaluation_oracle_sha256": digest(Path(args.evaluation_oracle).read_bytes()) if args.live else None,
                "expected_calls": 1 if args.live else 0, "actual_calls": 0,
                "status": "DRY_RUN_NOT_EXPERIMENT_RESULT", "wall_clock_seconds": None,
                "tokens": token_usage(b"{}"), "compilation": "NOT_EXECUTED", "human_hitl_verified": False}
    # Giữ manifest trước call để lượt lỗi/interrupted không biến mất khỏi evidence.
    (output / "run.json").write_bytes(encoded(metadata))
    if args.live:
        metadata.update(status="CALL_STARTED", actual_calls=1)
        (output / "run.json").write_bytes(encoded(metadata))
        started = time.monotonic()
        status, raw, error = call_provider(prompt, endpoint, key, options)
        metadata["wall_clock_seconds"] = time.monotonic() - started
        # Proxy có thể echo credential: ưu tiên không lưu secret, đánh dấu redaction rõ ràng.
        secret = key.encode("utf-8")
        escaped_secret = json.dumps(key, ensure_ascii=True)[1:-1].encode("utf-8")
        redacted = secret in raw or escaped_secret in raw
        if redacted:
            raw = raw.replace(secret, b"[REDACTED]").replace(escaped_secret, b"[REDACTED]")
        (output / "response.raw").write_bytes(raw)
        metadata.update(http_status=status, response_sha256=digest(raw), tokens=token_usage(raw),
                        response_redacted=redacted, status=error or "GENERATED_UNVALIDATED",
                        response_model=None, finish_reason=None)
        try:
            response = json.loads(raw)
            metadata["response_model"] = response.get("model")
            metadata["finish_reason"] = response.get("choices", [{}])[0].get("finish_reason")
        except (ValueError, TypeError, AttributeError, IndexError):
            pass
        if not error:
            try:
                result = parse_response(raw)
                (output / "generated.json").write_bytes(encoded(result))
            except (ValueError, KeyError, IndexError, TypeError, AttributeError):
                metadata["status"] = "MALFORMED_OUTPUT"
        (output / "run.json").write_bytes(encoded(metadata))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--datasets", default=str(Path(__file__).with_name("datasets.json")))
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--method", choices=METHODS, required=True)
    parser.add_argument("--source-snapshot", required=True)
    parser.add_argument("--approved-br")
    parser.add_argument("--evaluation-oracle")
    parser.add_argument("--output", required=True)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args)
    except (ValueError, KeyError, StopIteration, OSError, subprocess.CalledProcessError):
        parser.exit(2, "Baseline preflight failed; check source bindings, gates and new output directory.\n")
    print(result["status"])
    return 0 if result["status"] in ("DRY_RUN_NOT_EXPERIMENT_RESULT", "GENERATED_UNVALIDATED") else 1


if __name__ == "__main__":
    raise SystemExit(main())
