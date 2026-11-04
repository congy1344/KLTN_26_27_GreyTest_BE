"""Offline E2E evidence. Keeps every numeric window; never labels mock data as real LLM data."""
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import statistics

spec = importlib.util.spec_from_file_location("paired_summary", Path(__file__).with_name("summarize-source-update-paired.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def union(intervals):
    total, end = 0, None
    for left, right in sorted(intervals):
        assert right >= left
        if end is None or left > end:
            total += right - left
        elif right > end:
            total += right - end
        end = max(right, end if end is not None else right)
    return total


def millis(spans, predicate):
    return union([(s["startNanos"], s["endNanos"]) for s in spans if predicate(s)]) / 1e6


def bootstrap(values):
    rng = random.Random(36026)
    medians, tails = [], []
    for _ in range(5000):
        draw = rng.choices(values, k=len(values))
        medians.append(statistics.median(draw))
        tails.append(summary.percentile(draw, .95))
    return {label: [summary.percentile(draws, .025), summary.percentile(draws, .975)]
            for label, draws in (("p50CI95", medians), ("p95CI95", tails))}


def build(paths):
    result = {"phase": "3.6", "mode": "OFFLINE", "status": "MEASURED", "provider": "deterministic in-process mock",
              "realProviderRequests": 0, "apiKeyUsed": False, "assertLeaseOptimization": False,
              "batchConcurrency": 2, "inputTokens": None, "outputTokens": None, "actualBillingCostUsd": None,
              "realProviderQueueMillis": None, "realProviderNetworkMillis": None,
              "jdbcScope": "Correlated caller/worker JDBC spans; scheduled heartbeat thread has no profiler context, so its UPDATE/commit is not counted",
              "rawSources": [], "qualityRuns": [], "samples": [], "summary": [], "representativeSemanticEvidence": []}
    hashes, proofs = defaultdict(set), defaultdict(set)
    for path in paths:
        raw = json.loads(path.read_text(encoding="utf-8"))
        assert raw["mode"] == "OFFLINE" and raw["realProviderRequests"] == 0 and raw["assertLeaseOptimization"] is False
        delay = raw["simulatedProviderMillis"]
        result["rawSources"].append({"path": path.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                                     "delayMillis": delay, "samplesPerWorkload": raw["samplesPerWorkload"]})
        for quality in raw["qualityRuns"]:
            result["qualityRuns"].append({"delayMillis": delay, **quality})
        for original in raw["samples"]:
            spans = original["spans"]
            assert spans, "JDBC/critical-path measurements require instrumentation"
            row = {k: original[k] for k in ("runId", "projectId", "updateId", "sample", "workload", "wallMillis",
                    "processCpuMillis", "gcCountDelta", "gcMillisDelta", "llmCalls", "metrics", "artifactCounts",
                    "proposalActions", "sourceFixtureDigest", "outputDigest", "provenanceDigest")}
            row["simulatedProviderMillis"] = delay
            if "changedMethods" in original:
                row["changedMethods"] = original["changedMethods"]
                assert row["changedMethods"] == {"ONE_ADDED": 1, "ONE_MODIFIED": 1, "SIX_INDEPENDENT": 6, "RESUME": 6}[row["workload"]]
            assert sum(row["metrics"].get("profile." + stage + ".calls", 0) for stage in (
                "business-rule", "test-plan", "test-case", "unit-test")) == row["llmCalls"]
            row["semanticSha256"] = summary.canonical_digest(original["semanticEvidence"])
            workload = row["workload"].removesuffix("_WARM").removesuffix("_RETRY")
            hashes[workload].add(row["semanticSha256"])
            proofs[workload].add(row["sourceFixtureDigest"])
            if not any(x["workload"] == workload for x in result["representativeSemanticEvidence"]):
                result["representativeSemanticEvidence"].append({"workload": workload, "evidence": original["semanticEvidence"]})
            row["spanCounts"] = dict(Counter(s["name"] for s in spans))
            row["failedSpans"] = dict(Counter(s["name"] for s in spans if s["failed"]))
            provider = [s for s in spans if s["name"] == "provider.simulated"]
            assert len(provider) == row["llmCalls"]
            row["providerIntervals"] = provider
            row["simulatedProviderUnionMillis"] = millis(provider, lambda s: True)
            assert row["simulatedProviderUnionMillis"] <= row["wallMillis"]
            row["backendExclusiveElapsedMillis"] = row["wallMillis"] - row["simulatedProviderUnionMillis"]
            row["timingMillis"] = {
                "jdbcExecutionUnion": millis(spans, lambda s: s["name"].startswith("jdbc.execute")),
                "jdbcCommitUnion": millis(spans, lambda s: s["name"] == "jdbc.commit"),
                "transactionHoldUnion": millis(spans, lambda s: s["name"] == "transaction.hold"),
                "batchMapUnion": millis(spans, lambda s: s["name"] == "llmBatchExecutor.map"),
                "batchWorkerUnion": millis(spans, lambda s: s["name"] == "batch.worker"),
                "batchReadyToStartMax": max([(s["endNanos"] - s["startNanos"]) / 1e6 for s in spans if s["name"] == "batch.readyToStart"], default=0),
                "analyzeEnvelope": millis(spans, lambda s: s["name"] == "sourceUpdateService.analyzeUpdate"),
            }
            row["jdbcByPurpose"] = summary.jdbc_purpose_counts(spans)
            row["slowJdbcSpans"] = sorted([s for s in spans if s["name"].startswith("jdbc.")],
                    key=lambda s: s["endNanos"] - s["startNanos"], reverse=True)[:5]
            result["samples"].append(row)
    assert all(len(v) == 1 for v in hashes.values()), "Canonical artifact/impact/provenance differ across samples or delays"
    assert all(len(v) == 1 for v in proofs.values()), "Source fixture differs across samples or delays"
    groups = defaultdict(list)
    for row in result["samples"]:
        groups[(row["simulatedProviderMillis"], row["workload"])].append(row)
    for (delay, workload), rows in sorted(groups.items()):
        assert len({r["sample"] for r in rows}) == len(rows), "Duplicate sample"
        walls = [r["wallMillis"] for r in rows]
        result["summary"].append({"delayMillis": delay, "workload": workload, "samples": len(rows),
            "wallMillis": {**summary.describe(walls), **bootstrap(walls)},
            "backendExclusiveElapsedMillis": summary.describe([r["backendExclusiveElapsedMillis"] for r in rows]),
            "simulatedProviderUnionMillis": summary.describe([r["simulatedProviderUnionMillis"] for r in rows]),
            "llmCalls": dict(Counter(r["llmCalls"] for r in rows)),
            "regeneratedArtifacts": dict(Counter(r["metrics"].get("artifact.regenerated", 0) for r in rows)),
            "jdbcQueries": dict(Counter(r["spanCounts"].get("jdbc.executeQuery", 0) for r in rows)),
            "medianStageMillis": {stage: statistics.median(r["metrics"].get("stage." + stage + ".wallNanos", 0) / 1e6 for r in rows)
                                  for stage in ("BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST")}})
    result["qualityTestsExecuted"] = sum(q["generatedUnitTestsExecuted"] for q in result["qualityRuns"])
    result["canonicalEqualityVerified"] = True
    result["limits"] = ["Instrumented offline synthetic fixture, no real LLM latency/usage/quality claim",
        "Backend exclusive elapsed removes provider interval union, not backend CPU or all overlapping backend work",
        "Ten samples give exploratory p95 and wide bootstrap intervals, no production SLA or matched optimized baseline",
        "JDBC, batch and stage intervals overlap and cannot be added to root wall",
        "Lease heartbeat every20s is uncorrelated in this profiler; total server UPDATE/commit counts and heartbeat latency are unavailable",
        "Quality compile/JUnit uses GreyTest test classpath; not a standalone project Maven build"]
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="*", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        assert union([(0, 5), (3, 8), (10, 12)]) == 10
        assert union([(0, 8), (1, 2)]) == 8
        assert bootstrap([42] * 10) == {"p50CI95": [42, 42], "p95CI95": [42, 42]}
        print("Offline summary self-check passed")
    else:
        assert args.inputs and args.output
        document = build(args.inputs)
        args.output.write_bytes((json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        print(len(document["samples"]), "windows;", document["qualityTestsExecuted"], "quality tests")
