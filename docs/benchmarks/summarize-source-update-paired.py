"""Summarize Phase 3.2 paired runs. Usage: python <script> <samples.json> [...]."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import statistics
import sys
from collections import defaultdict


def percentile(values, probability):
    values = sorted(values)
    index = (len(values) - 1) * probability
    low = int(index)
    return values[low] + (values[min(low + 1, len(values) - 1)] - values[low]) * (index - low)


def describe(values):
    return {"median": statistics.median(values), "p95": percentile(values, .95),
            "min": min(values), "max": max(values), "stdev": statistics.stdev(values) if len(values) > 1 else 0}


def paired(rows):
    pairs = defaultdict(dict)
    for row in rows:
        assert row["variant"] not in pairs[row["pair"]], "Duplicate variant in pair"
        pairs[row["pair"]][row["variant"]] = row
    assert pairs and all(set(pair) == {"legacy", "optimized"} for pair in pairs.values()), "Incomplete sample pair"
    ratios = [pair["optimized"]["wallMillis"] / pair["legacy"]["wallMillis"] for pair in pairs.values()]
    deltas = [pair["optimized"]["wallMillis"] - pair["legacy"]["wallMillis"] for pair in pairs.values()]
    randomizer = random.Random(32026)
    bootstrap = [statistics.median(randomizer.choices(ratios, k=len(ratios))) for _ in range(10000)]
    return {"pairs": len(pairs), "legacyWallMillis": describe([pair["legacy"]["wallMillis"] for pair in pairs.values()]),
            "optimizedWallMillis": describe([pair["optimized"]["wallMillis"] for pair in pairs.values()]),
            "pairedOptimizedOverLegacy": describe(ratios), "pairedDeltaMillis": describe(deltas),
            "medianRatioBootstrap95": [percentile(bootstrap, .025), percentile(bootstrap, .975)]}


def summarize(document):
    grouped = defaultdict(list)
    for row in document["samples"]:
        grouped[row["workload"]].append(row)
    return {"optimization": document["optimization"], "instrumented": document["instrumented"], "jfr": document["jfr"],
            "workloads": {name: paired(rows) for name, rows in sorted(grouped.items())},
            "limits": ["Synthetic deterministic provider, no API latency; 10 pairs give uncertain tail estimates",
                       "CPU and allocatedBytes spans are inclusive: never sum nested spans",
                       "JFR includes fixture setup/cleanup; attribute only recorded measured intervals/threads",
                       "Canonical comparison normalizes database IDs, generated BR/TP/TC technical labels, and dense UT Case<case-id> identifiers; source logic and parameter order remain unchanged"]}


def canonical_digest(evidence):
    return hashlib.sha256(json.dumps(evidence, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()


def base_workload(name):
    return "RESUME" if name.startswith("RESUME") else name.removesuffix("_WARM")


def span_aggregates(spans, full=False):
    grouped = {}
    long_names = {"e2e", "batch.worker", "aiagentservice.snapshotcontext", "promptmanager.render",
                  "generationresponseparser.parse", "provider.local"}
    for span in spans:
        name = span["name"]
        if not full and not any(name.lower().startswith(prefix) for prefix in long_names) and not name.startswith("jdbc."):
            continue
        key = (span["stage"], name, span.get("purpose", "unavailable")) if full else (name,)
        identity = {"stage": key[0], "name": key[1], "purpose": key[2]} if full else {"name": name}
        stats = grouped.setdefault(key, {**identity, "count": 0, "failed": 0, "inclusiveWallNanos": 0,
                                        "inclusiveCpuNanos": 0, "inclusiveAllocatedBytes": 0,
                                        "cpuUnavailable": 0, "allocationUnavailable": 0})
        stats["count"] += 1
        stats["failed"] += int(span["failed"])
        stats["inclusiveWallNanos"] += span["endNanos"] - span["startNanos"]
        for source, target, missing in (("cpuNanos", "inclusiveCpuNanos", "cpuUnavailable"),
                                        ("allocatedBytes", "inclusiveAllocatedBytes", "allocationUnavailable")):
            value = span.get(source, -1)
            if value < 0:
                stats[missing] += 1
            else:
                stats[target] += value
    return [grouped[key] for key in sorted(grouped)]


def jdbc_purpose_counts(spans):
    grouped = {}
    for span in spans:
        if span["name"].startswith("jdbc."):
            purpose = span.get("purpose", "unavailable")
            counts = grouped.setdefault(purpose, {})
            counts[span["name"]] = counts.get(span["name"], 0) + 1
    return grouped


def jfr_aggregates(events):
    grouped = {}
    for key, stats in events.items():
        event = key.split(":", 1)[0]
        target = grouped.setdefault(event, {"count": 0, "durationNanos": 0})
        target["count"] += stats["count"]
        target["durationNanos"] += stats["durationNanos"]
    return grouped


def compact_evidence(document):
    evidence_by_fixture = {}
    representatives = {}
    hashes = defaultdict(dict)
    for row in document["samples"]:
        if "semanticEvidence" not in row:
            continue
        workload = base_workload(row["workload"])
        evidence = row["semanticEvidence"]
        digest = canonical_digest(evidence)
        key = (workload, row["pair"], row["variant"])
        assert key not in evidence_by_fixture, "Duplicate semantic fixture"
        evidence_by_fixture[key] = digest
        hashes[(workload, row["pair"])][row["variant"]] = digest
        representatives.setdefault((workload, row["variant"]), {"workload": workload, "variant": row["variant"],
                                                              "pair": row["pair"], "semanticSha256": digest, "evidence": evidence})
    assert hashes and all(set(pair) == {"legacy", "optimized"} and pair["legacy"] == pair["optimized"]
                          for pair in hashes.values()), "Canonical paired evidence differs or is incomplete"
    samples = []
    query_traces = {}
    fields = ("pair", "order", "variant", "workload", "wallMillis", "processCpuMillis", "llmCalls", "changedMethods",
              "metrics", "artifactCounts", "proposalActions", "outputDigest", "provenanceDigest")
    for row in document["samples"]:
        sample = {field: row[field] for field in fields if field in row}
        sample["semanticSha256"] = evidence_by_fixture[(base_workload(row["workload"]), row["pair"], row["variant"])]
        sample["spanAggregates"] = span_aggregates(row.get("spans", []))
        sample["jdbcByPurpose"] = jdbc_purpose_counts(row.get("spans", []))
        representative_key = (base_workload(row["workload"]), row["variant"])
        if "semanticEvidence" in row and representative_key not in query_traces:
            query_traces[representative_key] = {"workload": representative_key[0], "variant": row["variant"],
                                               "pair": row["pair"], "measuredPhase": row["workload"],
                                               "spanAggregates": span_aggregates(row.get("spans", []), full=True)}
        sample["jfrByEvent"] = jfr_aggregates(row.get("jfr", {}))
        samples.append(sample)
    metadata = {key: value for key, value in document.items() if key != "samples"}
    return {"format": "greytest-paired-compact-v1", **metadata, "summary": summarize(document),
            "correctness": {"canonicalSemanticPairsVerified": len(hashes),
                            "semanticHashScope": "Settled fixture after repeat/resume; every row references its fixture evidence",
                            "warmAndResumeChecks": "Harness requires exact output/input+output-provenance digests and zero warm LLM calls",
                            "generatedUnitTestExecution": "Actual proposal code compiled and executed unchanged on candidate source in warmup; see qualityRuns"},
            "aggregationLimits": ["Span CPU/allocation/wall values are inclusive and cannot be summed across names or purposes",
                                  "Unavailable CPU/allocation contributes only to unavailable count, never a zero measurement",
                                  "JFR waits are thresholded lower bounds (1ms), execution samples use 10ms period; control has no profiler/JFR data",
                                  "Full canonical evidence and stage/name/purpose traces retained for one cold sample per workload/variant; hashes retain every fixture comparison"],
            "representativeQueryTrace": [query_traces[key] for key in sorted(query_traces)],
            "representativeSemanticEvidence": [representatives[key] for key in sorted(representatives)], "samples": samples}


def self_check():
    rows = [{"pair": pair, "variant": variant, "wallMillis": 100 if variant == "legacy" else 80}
            for pair in range(10) for variant in ("legacy", "optimized")]
    result = paired(rows)
    assert result["pairedOptimizedOverLegacy"]["median"] == .8
    assert result["pairedDeltaMillis"]["median"] == -20
    assert result["medianRatioBootstrap95"] == [.8, .8]
    spans = [{"stage": "BR", "name": "jdbc.executeQuery", "purpose": "repository.read", "failed": False,
              "startNanos": 1, "endNanos": 4, "cpuNanos": -1, "allocatedBytes": 7}]
    aggregate = span_aggregates(spans)[0]
    assert aggregate["inclusiveWallNanos"] == 3 and aggregate["cpuUnavailable"] == 1
    assert aggregate["inclusiveAllocatedBytes"] == 7
    assert "stage" not in aggregate and "purpose" not in aggregate
    assert span_aggregates(spans, full=True)[0]["purpose"] == "repository.read"
    assert jdbc_purpose_counts(spans) == {"repository.read": {"jdbc.executeQuery": 1}}
    sample_rows = [dict(row, workload="ONE_ADDED", semanticEvidence={"business": "42", "ids": ["stable"]}, spans=spans,
                        jfr={"jdk.ThreadPark:stack": {"count": 1, "durationNanos": 2}}) for row in rows]
    compact = compact_evidence({"optimization": "A", "instrumented": True, "jfr": True, "samples": sample_rows,
                                "qualityRuns": [{"generatedUnitTestsExecuted": 1}]})
    assert compact["correctness"]["canonicalSemanticPairsVerified"] == 10
    assert len(compact["representativeSemanticEvidence"]) == 2
    assert len(compact["representativeQueryTrace"]) == 2
    assert compact["samples"][0]["jdbcByPurpose"] == {"repository.read": {"jdbc.executeQuery": 1}}
    assert "purpose" not in compact["samples"][0]["spanAggregates"][0]
    assert compact["representativeQueryTrace"][0]["spanAggregates"][0]["purpose"] == "repository.read"
    assert all("spans" not in row and len(row["semanticSha256"]) == 64 for row in compact["samples"])
    assert compact["samples"][0]["jfrByEvent"]["jdk.ThreadPark"]["count"] == 1
    try:
        paired(rows[:-1])
    except AssertionError:
        pass
    else:
        raise AssertionError("Missing variant accepted")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="*")
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--compact-output", type=Path, help="Write compact evidence for exactly one input; raw input stays unchanged")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        print("paired summary self-check passed")
    elif args.compact_output:
        assert len(args.inputs) == 1, "Compact output requires exactly one input"
        with Path(args.inputs[0]).open(encoding="utf-8") as source:
            document = compact_evidence(json.load(source))
        with args.compact_output.open("w", encoding="utf-8") as target:
            json.dump(document, target, indent=2, ensure_ascii=False)
            target.write("\n")
        print(f"Compact evidence written to {args.compact_output}")
    else:
        assert args.inputs, __doc__
        result = []
        for path in args.inputs:
            with Path(path).open(encoding="utf-8") as source:
                result.append(summarize(json.load(source)))
        print(json.dumps(result, indent=2))
