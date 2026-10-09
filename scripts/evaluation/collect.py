"""Offline collector for explicitly prepared, non-secret evaluation bundles."""
import argparse
import csv
import hashlib
import json
import re
import shutil
import unicodedata
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

VERSION = "chapter5-v1"
THRESHOLD = 0.85
CASE_FIELDS = ("test_type", "description", "preconditions", "test_data", "expected_result", "priority", "trace_source")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def percent(numerator, denominator):
    return None if denominator == 0 else 100 * numerator / denominator


def normalize(text):
    return " ".join(re.findall(r"\w+", unicodedata.normalize("NFKC", text).casefold()))


def stability(left, right):
    # Ghép một-một tối đa; không cho một case bên phải che nhiều case trùng bên trái.
    edges = []
    for a in left:
        scores = [(SequenceMatcher(None, normalize(a["description"]), normalize(b["description"]), autojunk=False).ratio(), j)
                  for j, b in enumerate(right) if a["test_type"] == b["test_type"]]
        edges.append([j for score, j in sorted(scores, key=lambda item: (-item[0], item[1])) if score > THRESHOLD])
    matches = {}

    def augment(i, seen):
        for j in edges[i]:
            if j in seen:
                continue
            seen.add(j)
            if j not in matches or augment(matches[j], seen):
                matches[j] = i
                return True
        return False

    for i in range(len(left)):
        augment(i, set())
    return {"matched": len(matches), "denominator": max(len(left), len(right)),
            "percent": percent(len(matches), max(len(left), len(right)))}


def modification(raw, snapshot):
    original = {c["evaluation_id"]: c for c in raw}
    current = {c["evaluation_id"]: c for c in snapshot}
    if len(original) != len(raw) or len(current) != len(snapshot):
        raise ValueError("Duplicate evaluation_id in case snapshot")
    common = original.keys() & current.keys()
    edited = sum(any(original[k].get(f) != current[k].get(f) for f in CASE_FIELDS) for k in common)
    return {"generated": len(raw), "edited": edited, "percent": percent(edited, len(raw)),
            "added": len(current.keys() - original.keys()), "removed": len(original.keys() - current.keys()),
            "not_approved": sum(c.get("status") != "APPROVED" for c in snapshot)}


def tokens(calls, expected):
    if expected < len(calls):
        raise ValueError("expected_calls smaller than recorded calls")
    totals = {"PROVIDER": 0, "ESTIMATED": 0}
    missing = expected - len(calls)
    for call in calls:
        source, value = call.get("usageSource"), call.get("totalTokens")
        if source not in totals or type(value) is not int or value < 0:
            missing += 1
        else:
            totals[source] += value
    return {"expected_calls": expected, "recorded_calls": len(calls), "missing_calls": missing,
            "provider_known_tokens": totals["PROVIDER"], "estimated_known_tokens": totals["ESTIMATED"],
            "total_tokens": None if missing else sum(totals.values())}


def stage_times(stages):
    result = []
    for stage in stages:
        elapsed = stage.get("wall_clock_seconds")
        if elapsed is not None and (type(elapsed) not in (int, float) or not 0 <= elapsed < float("inf")):
            raise ValueError("Invalid wall-clock duration")
        result.append({"stage": stage["stage"], "status": stage["status"], "wall_clock_seconds": elapsed})
    return result


def collect(bundle, destination):
    config = read(bundle)
    base = Path(bundle).resolve().parent
    required = ("run_id", "method", "dataset_kind", "dataset_id", "scope", "model", "provider_mode", "be_commit", "fe_commit", "status")
    metadata = {key: config[key] for key in required}
    if metadata["status"] not in ("Pass", "Fail", "Blocked", "Not Run"):
        raise ValueError("Invalid status")
    artifact_sources = {name: (base / relative).resolve() for name, relative in config["artifacts"].items()}
    if any(not path.is_file() for path in artifact_sources.values()):
        raise ValueError("Missing evidence artifact")
    raw = read(artifact_sources["raw_cases"])
    snapshot = read(artifact_sources["pre_unit_snapshot"])
    truth = read(artifact_sources["requirements"])
    identifiers = [r["id"] for r in truth]
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("Duplicate independent requirements")
    covered = set(config.get("covered_requirement_ids", []))
    if not covered <= set(identifiers):
        raise ValueError("Unknown requirement mapping")
    calls = []
    if "calls" in artifact_sources:
        calls = [json.loads(line) for line in artifact_sources["calls"].read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        if any(c.get("runId") != metadata["run_id"] for c in calls):
            raise ValueError("Mixed usage runId")
    result = {"protocol_version": VERSION, "collected_at": datetime.now(timezone.utc).isoformat(), **metadata,
              "requirement_coverage": {"covered": len(covered), "denominator": len(truth), "percent": percent(len(covered), len(truth))},
              "case_edit_audit": modification(raw, snapshot),
              "umr": modification(raw, snapshot) if config.get("human_hitl_verified") is True else None,
              "tokens": tokens(calls, config["expected_calls"]),
              "stages": stage_times(config.get("stages", [])), "limitations": config.get("limitations", []),
              "stability": None}
    if "comparison_cases" in artifact_sources:
        result["stability"] = stability(raw, read(artifact_sources["comparison_cases"]))
    # Tạo thư mục mới để không ghi đè bằng chứng của lần chạy trước.
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    raw_dir = destination / "artifacts"
    raw_dir.mkdir()
    hashes = {}
    for index, (name, source) in enumerate(sorted(artifact_sources.items())):
        target = raw_dir / (str(index) + source.suffix)
        shutil.copyfile(source, target)
        hashes[name] = {"path": str(target.relative_to(destination)), "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
    result["artifacts"] = hashes
    # Chỉ lưu cấu hình public đã định nghĩa; path được đổi sang bản sao để replay độc lập.
    archived_config = {**metadata, "expected_calls": config["expected_calls"],
                       "covered_requirement_ids": config.get("covered_requirement_ids", []),
                       "human_hitl_verified": config.get("human_hitl_verified", False),
                       "stages": result["stages"], "limitations": config.get("limitations", []),
                       "artifacts": {name: item["path"] for name, item in hashes.items()}}
    archived_path = destination / "bundle.json"
    archived_path.write_text(json.dumps(archived_config, ensure_ascii=False, indent=2), encoding="utf-8")
    result["input_config"] = {"path": "bundle.json", "sha256": hashlib.sha256(archived_path.read_bytes()).hexdigest()}
    result["covered_requirement_ids"] = sorted(covered)
    result["human_hitl_verified"] = archived_config["human_hitl_verified"]
    (destination / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    row = {**metadata, "requirement_coverage_percent": result["requirement_coverage"]["percent"],
           "umr_percent": result["umr"]["percent"] if result["umr"] else None, "total_tokens": result["tokens"]["total_tokens"],
           "stability_percent": result["stability"]["percent"] if result["stability"] else None}
    with (destination / "results.csv").open("w", newline="", encoding="utf-8-sig") as output:
        writer = csv.DictWriter(output, fieldnames=list(row))
        writer.writeheader()
        writer.writerow({key: "N/A" if value is None else value for key, value in row.items()})
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    collect(args.bundle, args.destination)
