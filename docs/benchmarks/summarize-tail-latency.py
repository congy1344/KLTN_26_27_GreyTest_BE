"""Phase 3.5 evidence, using the existing paired summary. No samples are discarded."""
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import statistics

spec = importlib.util.spec_from_file_location("paired_summary", Path(__file__).with_name("summarize-source-update-paired.py"))
paired_summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(paired_summary)


def union(intervals):
    total = 0
    end = None
    for left, right in sorted(intervals):
        assert right >= left
        total += right - max(left, end if end is not None else left) if end is None or right > end else 0
        end = max(right, end if end is not None else right)
    return total


def intervals(spans, predicate):
    return union([(s["startNanos"], s["endNanos"]) for s in spans if predicate(s)]) / 1e6


def tail_ci(rows):
    pairs = defaultdict(dict)
    for row in rows:
        assert row["variant"] not in pairs[row["pair"]]
        pairs[row["pair"]][row["variant"]] = row["wallMillis"]
    assert all(set(p) == {"legacy", "optimized"} for p in pairs.values())
    values = list(pairs.values())
    rng = random.Random(35026)
    draws = defaultdict(list)
    for _ in range(10000):
        selected = rng.choices(values, k=len(values))
        for probability, label in ((.5, "p50"), (.95, "p95")):
            a, b = [paired_summary.percentile([p[v] for p in selected], probability) for v in ("legacy", "optimized")]
            draws["legacy" + label].append(a)
            draws["optimized" + label].append(b)
            draws[label + "Ratio"].append(b / a)
    return {key: [paired_summary.percentile(v, .025), paired_summary.percentile(v, .975)] for key, v in draws.items()}


def augment(raw):
    result = paired_summary.compact_evidence(raw)
    groups = defaultdict(list)
    for original, row in zip(raw["samples"], result["samples"]):
        for key in ("runId", "projectId", "updateId", "epochStartMillis", "epochEndMillis", "sourceFixtureDigest",
                    "gcCountDelta", "gcMillisDelta", "gcEvents", "databaseSamples", "poolSamples", "observerErrors"):
            if key in original:
                row[key] = original[key]
        spans = original.get("spans", [])
        row["stageSpans"] = [s for s in spans if s["name"].lower() in ("sourceupdateservice.analyzeupdate", "incrementalgenerationservice.generateall", "incrementalgenerationservice.generatestage")]
        names = Counter(s["name"] for s in spans)
        row["spanCounts"] = dict(sorted(names.items()))
        if raw["instrumented"]:
            # save() tự gọi guard: AOP không thấy self-invocation, nhưng complete/mark đều gọi save đúng một lần.
            row["leaseAssertionCount"] = sum(names[name] for name in (
                "generationCheckpointStore.assertLease", "generationCheckpointStore.complete", "generationCheckpointStore.mark"))
        row["failedSpans"] = dict(Counter(s["name"] for s in spans if s["failed"]))
        row["timingMillis"] = {
            "jdbcExecutionUnion": intervals(spans, lambda s: s["name"].startswith("jdbc.execute")),
            "commitUnion": intervals(spans, lambda s: s["name"] == "jdbc.commit"),
            "connectionAcquireUnion": intervals(spans, lambda s: s["name"] == "jdbc.getConnection"),
            "transactionHoldUnion": intervals(spans, lambda s: s["name"] == "transaction.hold"),
            "providerUnion": intervals(spans, lambda s: s["name"] == "provider.local"),
        } if raw["instrumented"] else None
        seen = {s["spanId"] for s in row.get("databaseSamples", [])}
        row["sampledJdbcSpans"] = [s for s in spans if s["id"] in seen]
        if any(s["blockers"] for s in row.get("databaseSamples", [])):
            row["transactionSpans"] = [s for s in spans if s["name"] == "transaction.hold"]
        assert len(row["sampledJdbcSpans"]) == len(seen), "Missing sampled SQL span"
        by_id = {s["id"]: s for s in row["sampledJdbcSpans"]}
        for sample in row.get("databaseSamples", []):
            span = by_id[sample["spanId"]]
            assert span["backendPid"] == sample["backendPid"] and span["thread"] == sample["thread"]
            assert span["startNanos"] <= sample["startNanos"] <= sample["endNanos"] <= span["endNanos"]
        groups[(row["workload"], row["variant"])].append(original)
    result["longestColdTraces"] = []
    for (workload, variant), rows in sorted(groups.items()):
        if raw["instrumented"] and workload in ("SIX_INDEPENDENT", "ONE_ADDED", "ONE_ADDED_128_UNRELATED", "RESUME"):
            for row in sorted(rows, key=lambda r: r["wallMillis"], reverse=True)[:2]:
                result["longestColdTraces"].append({k: row[k] for k in ("runId", "pair", "variant", "workload", "wallMillis", "spans", "jfr")})
    by_workload = defaultdict(list)
    for row in raw["samples"]:
        by_workload[row["workload"]].append(row)
    pairs = defaultdict(dict)
    for row in result["samples"]:
        pairs[(row["workload"], row["pair"])][row["variant"]] = row
    result["differentPartialWorkPairs"] = []
    for (workload, pair), variants in pairs.items():
        a, b = variants["legacy"], variants["optimized"]
        if a["metrics"].get("artifact.regenerated", 0) != b["metrics"].get("artifact.regenerated", 0):
            assert workload in ("RESUME", "RESUME_RETRY"), "Unexpected non-resume work difference"
            result["differentPartialWorkPairs"].append({"workload": workload, "pair": pair,
                "legacyRegenerated": a["metrics"].get("artifact.regenerated", 0),
                "optimizedRegenerated": b["metrics"].get("artifact.regenerated", 0)})
            continue  # Giữ nguyên sample; lỗi chèn vào batch đến trước có thể để lại số artifact khác nhau.
        if raw["instrumented"]:
            assert a["leaseAssertionCount"] == b["leaseAssertionCount"]
            assert a["spanCounts"]["jdbc.executeQuery"] - b["spanCounts"]["jdbc.executeQuery"] == a["leaseAssertionCount"]
            for name in ("jdbc.executeUpdate", "jdbc.commit"):
                assert a["spanCounts"].get(name, 0) == b["spanCounts"].get(name, 0)
    result["wallBootstrap95"] = {key: tail_ci(rows) for key, rows in sorted(by_workload.items())}
    result["format"] = "greytest-tail-latency-v1"
    result["tailLimits"] = [
        "Database samples are observations, not exact wait residence durations; 20ms fixed delay plus sampler query time can miss short waits",
        "GC MXBean deltas are process-wide approximate boundary counters; GCPhasePause overlap is the diagnostic STW measure",
        "Within-category interval unions remove overlap; categories overlap each other and must never be added",
        "Percentile bootstrap intervals resample intact A/B pairs; p95 confidence is limited with 20 pairs",
        "Stage/query tracing and the observer are disabled in the control; unavailable measurements are not zero",
    ]
    return result


def self_check():
    assert union([(1, 5), (3, 7), (8, 9)]) == 7
    assert union([(1, 9), (2, 3), (9, 10)]) == 9
    assert union([]) == 0
    rows = [{"pair": i, "variant": v, "wallMillis": 100 if v == "legacy" else 80} for i in range(4) for v in ("legacy", "optimized")]
    assert tail_ci(rows)["p95Ratio"] == [.8, .8]
    paired_summary.self_check()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        print("tail summary self-check passed")
    else:
        assert args.input and args.output
        document = augment(json.loads(args.input.read_text(encoding="utf-8")))
        document["rawSource"] = {"path": args.input.as_posix(), "sha256": hashlib.sha256(args.input.read_bytes()).hexdigest()}
        args.output.write_bytes((json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        print(args.output.as_posix(), len(document["samples"]), "windows")
