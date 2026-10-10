"""Import one instrumented synthetic live oracle run; never calls a provider."""
import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path
from collect import collect


def read(path):
    text = path.read_text(encoding="utf-8-sig").strip()
    if text.startswith("```json"):
        text = text[7:].lstrip()
    return json.JSONDecoder().raw_decode(text)[0]


def write(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def detects_oracle(execution, unit, test_bytes):
    correct, faulty = execution["correct"], execution["faulty"]
    method = unit["test_method_name"]
    prefix = "fixture." + unit["test_class_name"]
    return (hashlib.sha256(test_bytes).hexdigest() == execution["generatedTestSha256"]
            and correct["found"] > 0 and correct["failed"] == 0
            and correct["succeeded"] == correct["found"] == faulty["found"]
            and faulty["failed"] > 0 and faulty["succeeded"] + faulty["failed"] == faulty["found"]
            and len(faulty["failureMethods"]) == len(faulty["failureCauses"]) == len(faulty["failureFrames"]) == faulty["failed"]
            and all(m == method for m in faulty["failureMethods"])
            and all(c == "java.lang.IllegalArgumentException: Insufficient balance" for c in faulty["failureCauses"])
            and all("fixture.TransferService#transfer" in frames and
                    any(f == prefix + "#" + method or f.startswith(prefix + "#lambda$" + method + "$") for f in frames)
                    for frames in faulty["failureFrames"]))


def self_check():
    unit = {"test_class_name": "TransferServiceTest", "test_method_name": "equality"}
    execution = {"generatedTestSha256": hashlib.sha256(b"frozen").hexdigest(),
                 "correct": {"found": 1, "succeeded": 1, "failed": 0},
                 "faulty": {"found": 1, "succeeded": 0, "failed": 1, "failureMethods": ["equality"],
                            "failureCauses": ["java.lang.IllegalArgumentException: Insufficient balance"],
                            "failureFrames": [["fixture.TransferService#transfer", "fixture.TransferServiceTest#equality"]]}}
    assert detects_oracle(execution, unit, b"frozen")
    assert not detects_oracle(execution, unit, b"changed")
    execution["faulty"]["failureFrames"] = [["fixture.TransferService#transfer", "fixture.TransferServiceTest#setUp"]]
    assert not detects_oracle(execution, unit, b"frozen")
    execution["faulty"]["failureMethods"] = []
    assert not detects_oracle(execution, unit, b"frozen")
    temporary_root = Path(__file__).resolve().parents[2] / "target"
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
        source = Path(temporary) / "failed-provider"
        (source / "direct").mkdir(parents=True)
        write(source / "environment.json", {"model": "self-check"})
        write(source / "direct/test-case.response.json", {"cases": None})
        (source / "direct/attempts.jsonl").write_text(json.dumps({"stage": "test-plan", "status": "PROVIDER_ERROR", "elapsedMillis": 2}) + "\n")
        import_run(source, Path(temporary) / "imported")
        summary = read(Path(temporary) / "imported/collected/results.json")
        assert summary["status"] == "Fail" and summary["tokens"]["total_tokens"] is None
        assert summary["requirement_coverage"]["percent"] is None


def import_run(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    prepared = destination / "prepared"
    prepared.mkdir(parents=True, exist_ok=False)
    raw, selected, checks, out_of_domain = [], [], [], []
    for route in ("direct", "reviewed"):
        directory = source / route
        required = ("test-case.response.json", "approved-case.json", "unit-test.response.json", "execution.json")
        if not all((directory / name).is_file() for name in required):
            checks.append({"route": route, "detected": False, "status": "INCOMPLETE"})
            if (directory / "test-case.response.json").is_file():
                try:
                    raw.extend({**case, "evaluation_id": f"{route}-{i}"} for i, case in enumerate(read(directory / "test-case.response.json")["cases"]))
                except (ValueError, KeyError, TypeError):
                    pass
            continue
        cases = read(directory / "test-case.response.json")["cases"]
        cases = [{**case, "evaluation_id": f"{route}-{i}"} for i, case in enumerate(cases)]
        raw.extend(cases)
        approved = read(directory / "approved-case.json")
        matches = [c for c in cases if c["description"] == approved["description"]
                   and c["test_type"] == approved["testType"] and c["test_data"] == approved["testData"]
                   and c["expected_result"] == approved["expectedResult"]]
        if len(matches) != 1:
            raise ValueError("Controlled selection must retain one uniquely identified generated case")
        selected.append({**matches[0], "status": "APPROVED"})
        for case in cases:
            data = case["test_data"]
            values = data.get("input", data)
            if isinstance(values, dict):
                if isinstance(values.get("amount"), (int, float)) and values["amount"] <= 0:
                    out_of_domain.append({"route": route, "case": case, "reason": "amount must be positive"})
                elif isinstance(values.get("balance"), (int, float)) and values["balance"] < 0:
                    out_of_domain.append({"route": route, "case": case, "reason": "balance must be non-negative"})
        unit = read(directory / "unit-test.response.json")["unit_tests"][0]
        test_file = directory / (unit["test_class_name"] + ".java")
        execution = read(directory / "execution.json")
        method = unit["test_method_name"]
        detected = detects_oracle(execution, unit, test_file.read_bytes())
        checks.append({"route": route, "detected": detected, "test_sha256": execution["generatedTestSha256"], "method": method})
    write(prepared / "raw-cases.json", raw)
    write(prepared / "snapshot.json", selected)
    write(prepared / "out-of-domain.json", out_of_domain)
    write(prepared / "oracle-checks.json", checks)
    complete = all(c.get("status") != "INCOMPLETE" for c in checks)
    write(prepared / "requirements.json", [{"id": "EQ-001", "description": "Positive balance=amount succeeds and returns zero", "source": "Frozen synthetic RULE in harness; independent of faulty implementation"}] if complete else [])
    environment = read(source / "environment.json") if (source / "environment.json").is_file() else {"model": "N/A"}
    calls_path = source / "usage" / source.name / "calls.jsonl"
    calls = [json.loads(line) for line in calls_path.read_text().splitlines()] if calls_path.is_file() else []
    attempts = []
    for route in ("direct", "review", "reviewed"):
        attempts_path = source / route / "attempts.jsonl"
        if attempts_path.is_file():
            attempts.extend({**json.loads(line), "route": route} for line in attempts_path.read_text().splitlines())
    backend = Path(__file__).resolve().parents[2]
    config = {"run_id": source.name, "method": "GREYTEST_SYNTHETIC_DIRECT_AND_REVIEWED", "dataset_kind": "synthetic",
              "dataset_id": "TransferService-equality-oracle", "scope": "Two automated fixture paths, one positive equality boundary",
              "model": environment["model"], "provider_mode": "live-openai-compatible",
              "be_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=backend, text=True).strip(),
              "fe_commit": "N/A", "status": "Pass" if all(c["detected"] for c in checks) else "Fail" if attempts else "Blocked",
              "expected_calls": len(attempts), "human_hitl_verified": False,
              "covered_requirement_ids": ["EQ-001"] if all(c["detected"] for c in checks) else [],
              "artifacts": {"raw_cases": "raw-cases.json", "pre_unit_snapshot": "snapshot.json", "requirements": "requirements.json",
                            "oracle_checks": "oracle-checks.json", "out_of_domain": "out-of-domain.json"},
              "stages": [{"stage": a["route"] + ":" + a["stage"], "status": a["status"], "wall_clock_seconds": a["elapsedMillis"] / 1000} for a in attempts],
              "limitations": ["Automated fixture approval; human UMR and UAT N/A", "One synthetic boundary, not 5–6 OSS dataset evaluation",
                              "No browser, HTTP/DB integration, JaCoCo, repeat stability or full-domain accuracy", "be_commit is checkpoint; working harness changes included",
                              "Requirement linkage is scoped to EQ-001; not full transfer requirement coverage", "No retries; every attempted call retained",
                              "Recorded input/output token zeros do not establish measured zero; component counts N/A when adapter exposes no breakdown; total kept as reported"]}
    if calls_path.is_file():
        config["artifacts"]["calls"] = str(calls_path)
    if not complete:
        config["limitations"].append("Incomplete route; requirement coverage and unobserved outcomes N/A")
    for path in source.rglob("*"):
        if path.is_file() and path.suffix in (".json", ".jsonl", ".java", ".md", ".txt", ".sha256"):
            config["artifacts"]["raw_" + path.relative_to(source).as_posix().replace("/", "_")] = str(path)
    write(prepared / "bundle.json", config)
    return collect(prepared / "bundle.json", destination / "collected")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, nargs="?")
    parser.add_argument("destination", type=Path, nargs="?")
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.source and args.destination:
        import_run(args.source, args.destination)
    else:
        parser.error("source and destination are required")
