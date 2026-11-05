"""Summarize test-only Phase 3 traces; inclusive spans are never added to root wall time."""

import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import statistics


def percentile(values, quantile):
    return sorted(values)[math.ceil(len(values) * quantile) - 1]


def summarize_spans(spans):
    groups = defaultdict(lambda: {"count": 0, "wallInclusiveMs": 0.0, "cpuInclusiveMs": 0.0, "cpuObservedSpans": 0})
    for span in spans:
        group = groups[span["name"]]
        group["count"] += 1
        group["wallInclusiveMs"] += (span["endNanos"] - span["startNanos"]) / 1e6
        if span["cpuNanos"] >= 0:
            group["cpuInclusiveMs"] += span["cpuNanos"] / 1e6
            group["cpuObservedSpans"] += 1
    for group in groups.values():
        if not group["cpuObservedSpans"]:
            group["cpuInclusiveMs"] = None
    return dict(groups)


def summarize_jfr(events):
    groups = defaultdict(lambda: {"count": 0, "durationMs": 0.0})
    for stack, stat in events.items():
        name = stack.split(":", 1)[0]
        if name == "jdk.ThreadPark":
            if "CompletableFuture.join" in stack:
                name += ".stageBarrier"
            elif "LinkedBlockingQueue.poll" in stack:
                name += ".batchQueuePoll"
            elif "ThreadPoolExecutor.getTask" in stack:
                name += ".executorIdle"
            else:
                name += ".other"
        elif name == "jdk.JavaMonitorEnter":
            name += ":" + stack.split(":", 1)[1].split(" <- ", 1)[0]
        elif name == "jdk.ExecutionSample":
            # Sampling count is evidence of executing stacks, not CPU/wall attribution.
            name += ":" + stack.split(":", 1)[1].split(" <- ", 1)[0]
        groups[name]["count"] += stat["count"]
        groups[name]["durationMs"] += stat["durationNanos"] / 1e6
    return dict(groups)


def compact(data):
    output = {key: value for key, value in data.items() if key != "samples"}
    output["limitations"] = [
        "Local deterministic mock provider; not production or provider-network latency.",
        "Cold is new update/checkpoints, not OS/JVM/disk cold; warm excludes analyzeUpdate.",
        "Wall/CPU span totals are inclusive and may overlap; never add them to e2e wall.",
        "JFR threshold and fully-contained event filtering yield observed lower bounds.",
        "Windows thread CPU timer is quantized; process CPU includes GC/JFR/background work.",
        "Ten samples provide an exploratory p95 (nearest rank=max), not a stable production tail.",
    ]
    output["samples"] = []
    for sample in data["samples"]:
        row = {key: value for key, value in sample.items() if key not in ("spans", "jfr")}
        row["spanGroups"] = summarize_spans(sample["spans"])
        row["batchIntervals"] = [span for span in sample["spans"] if span["name"] in ("batch.worker", "batch.readyToStart", "llmBatchExecutor.map", "provider.local")]
        row["longestTransactionIntervals"] = sorted(
            (span for span in sample["spans"] if span["name"] == "transaction.hold"),
            key=lambda span: span["endNanos"] - span["startNanos"], reverse=True)[:3]
        row["jfrGroups"] = summarize_jfr(sample["jfr"])
        output["samples"].append(row)
    workloads = defaultdict(list)
    for row in output["samples"]:
        workloads[row["workload"]].append(row)
    output["summary"] = {}
    for workload, rows in workloads.items():
        wall = [row["wallMillis"] for row in rows]
        output["summary"][workload] = {"n": len(rows), "wallP50Ms": statistics.median(wall),
                                       "wallP95Ms": percentile(wall, .95),
                                       "processCpuP50Ms": statistics.median(row["processCpuMillis"] for row in rows)}
    return output


def self_test():
    assert percentile(list(range(1, 11)), .95) == 10
    assert statistics.median([1, 2, 3, 4]) == 2.5
    spans = [{"name": "root", "startNanos": 0, "endNanos": 10_000_000, "cpuNanos": -1},
             {"name": "child", "startNanos": 2_000_000, "endNanos": 8_000_000, "cpuNanos": 1_000_000}]
    result = summarize_spans(spans)
    assert result["root"]["wallInclusiveMs"] == 10  # Not 16: child overlaps root.
    assert result["child"]["wallInclusiveMs"] == 6
    assert result["child"]["cpuInclusiveMs"] == 1
    jfr = summarize_jfr({"jdk.ThreadPark:x <- LinkedBlockingQueue.poll": {"count": 2, "durationNanos": 1_000_000}})
    assert jfr["jdk.ThreadPark.batchQueuePoll"] == {"count": 2, "durationMs": 1}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, nargs="?")
    parser.add_argument("output", type=Path, nargs="?")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test()
    if not args.self_test:
        if args.input is None or args.output is None:
            parser.error("input and output are required")
        report = compact(json.loads(args.input.read_text(encoding="utf-8")))
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report["summary"], indent=2))
