"""Import frozen synthetic oracle evidence; makes no provider call or Java run."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from collect import collect


def write(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def pilot(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    prepared = destination / "prepared"
    prepared.mkdir(parents=True, exist_ok=False)
    response = json.loads((source / "direct/test-case.response.json").read_text(encoding="utf-8-sig"))["cases"]
    raw = [{**case, "evaluation_id": str(i)} for i, case in enumerate(response)]
    approved = json.loads((source / "direct/approved-case.json").read_text(encoding="utf-8-sig"))
    matches = [c for c in raw if c["description"] == approved["description"] and c["test_type"] == approved["testType"]]
    if len(matches) != 1:
        raise ValueError("Historical selected case must be unique")
    selected = {**matches[0], "test_data": approved["testData"], "status": "APPROVED"}
    write(prepared / "raw-cases.json", raw)
    write(prepared / "pre-unit-snapshot.json", [selected])
    # Mẫu số chỉ thuộc scope equality của pilot, không phải toàn domain transfer.
    write(prepared / "requirements.json", [{"id": "EQ-001", "description": "Positive amount, balance equals amount: succeeds and returns zero", "source": "Frozen evidence.md independent reference requirement, 2026-10-08"}])
    execution = json.loads((source / "direct/execution.json").read_text(encoding="utf-8-sig"))
    test_hash = hashlib.sha256((source / "direct/TransferServiceTest.java").read_bytes()).hexdigest()
    if execution["generatedTestSha256"] != test_hash:
        raise ValueError("Historical test hash differs from execution evidence")
    correct, faulty = execution["correct"], execution["faulty"]
    unit_text = (source / "direct/unit-test.response.json").read_text(encoding="utf-8-sig").strip()
    if unit_text.startswith("```"):
        unit_text = re.sub(r"^```(?:json)?\s*|\s*```$", "", unit_text)
    unit = json.loads(unit_text)["unit_tests"][0]
    equality_method = "fixture." + unit["test_class_name"] + "#" + unit["test_method_name"]
    detection = (correct["found"] == correct["succeeded"] == faulty["found"] == faulty["failed"] == 1
                 and correct["failed"] == faulty["succeeded"] == 0
                 and faulty["failureCauses"] == ["java.lang.IllegalArgumentException: Insufficient balance"]
                 and faulty["failureMethods"] == [unit["test_method_name"]]
                 and len(faulty["failureFrames"]) == 1
                 and all("fixture.TransferService#transfer" in frames
                         and any(frame == equality_method or frame.startswith("fixture." + unit["test_class_name"] + "#lambda$" + unit["test_method_name"] + "$") for frame in frames)
                         for frames in faulty["failureFrames"]))
    backend = Path(__file__).resolve().parents[2]
    be_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=backend, text=True).strip()
    config = {"run_id": destination.name, "method": "GREYTEST_DIRECT_HISTORICAL_IMPORT", "dataset_kind": "synthetic",
              "dataset_id": "TransferService-equality-oracle", "scope": "Single positive equality case; historical import only",
              "model": "gpt-5.6-luna (recorded in evidence.md)", "provider_mode": "offline-frozen-evidence",
              "be_commit": be_commit, "fe_commit": "N/A: frontend not exercised", "status": "Pass" if detection else "Fail",
              "expected_calls": 3, "human_hitl_verified": False, "covered_requirement_ids": ["EQ-001"] if detection else [],
              "artifacts": {"raw_cases": "raw-cases.json", "pre_unit_snapshot": "pre-unit-snapshot.json", "requirements": "requirements.json"},
              "stages": [{"stage": s, "status": "historical-evidence", "wall_clock_seconds": None} for s in ["TEST_PLAN", "TEST_CASE", "UNIT_TEST"]],
              "limitations": ["No new generation/compilation/test execution; imported 2026-10-08 evidence only", "be_commit identifies importer environment, not historical producer", "Historical call usage/timing absent: N/A", "7 non-selected raw cases omitted in fixture approval; not counted as edits", "One selected raw case input changed 500/500 to 100/100: fixture edit ratio=1/8, human UMR N/A", "Mapping of EQ-001 derives from frozen fixture reference requirement; human independent review not verified", "Not an OSS dataset member, no stability repeats, no browser/DB evidence"]}
    for name, relative in {"execution": "direct/execution.json", "generated_test": "direct/TransferServiceTest.java", "unit_response": "direct/unit-test.response.json", "correct_source": "direct/correct/TransferService.java", "faulty_source": "direct/faulty/TransferService.java", "evidence": "evidence.md", "hitl_log": "direct/case-review.txt", "raw_response": "direct/test-case.response.json", "approved_case": "direct/approved-case.json"}.items():
        config["artifacts"][name] = str(source / relative)
    write(prepared / "bundle.json", config)
    return collect(prepared / "bundle.json", destination / "collected")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    pilot(args.source, args.destination)
