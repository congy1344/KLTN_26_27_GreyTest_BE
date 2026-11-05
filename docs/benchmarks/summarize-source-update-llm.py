"""Summarize existing profiler intervals; never infer real token usage or sum overlapping wall time."""
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path


def union_ms(intervals):
    total = 0
    right = None
    for start, end in sorted(intervals):
        if right is None or start > right:
            total += end - start
        else:
            total += max(0, end - right)
        right = max(right or end, end)
    return total / 1e6


def stats(values):
    values = [value for value in values if value is not None]
    if not values:
        return None
    values = sorted(values)
    pos = (len(values) - 1) * .95
    lo = math.floor(pos)
    hi = math.ceil(pos)
    return {'p50': statistics.median(values), 'p95': values[lo] + (values[hi] - values[lo]) * (pos - lo)}


assert union_ms([(0, 10_000_000), (5_000_000, 12_000_000), (20_000_000, 22_000_000)]) == 14
assert abs(stats([1, 2, 3, 4])['p95'] - 3.85) < 1e-12
assert stats([None]) is None
if sys.argv[1:] == ['--self-check']:
    print('Interval union, percentile and unavailable-value checks passed')
    raise SystemExit(0)
source, output = map(Path, sys.argv[1:])
raw = json.loads(source.read_text(encoding='utf-8'))
runs = []
for sample in raw['samples']:
    spans = sample['spans']
    metrics = sample['metrics']
    providers = [span for span in spans if span['name'] == 'provider.local']
    jdbc = [span for span in spans if span['name'].startswith('jdbc.execute') or span['name'] == 'jdbc.commit']
    lease = [span for span in spans if span['name'] == 'jdbc.executeQuery' and span['purpose'] in
             ('generationCheckpointStore.assertLease', 'generationCheckpointStore.complete', 'generationCheckpointStore.mark')]
    counts = Counter(span['name'] for span in spans)
    named = defaultdict(list)
    for span in spans:
        named[span['name']].append((span['startNanos'], span['endNanos']))
    groups = {name: {'count': len(v), 'unionMillis': union_ms(v), 'summedMillis': sum(b-a for a,b in v)/1e6} for name,v in named.items()}
    targets = set()
    batches = defaultdict(list)
    for span in spans:
        if span['name'] == 'batch.worker':
            for target in json.loads(span['targets']):
                targets.add((span['stage'], target))
            batches[span['stage']].append({key: span[key] for key in ('batch','targets','startNanos','endNanos','failed')})
    run = {key: sample[key] for key in ('workload','sample','runId','wallMillis','processCpuMillis','llmCalls','metrics','artifactCounts','proposalActions','outputDigest','provenanceDigest')}
    run.update(providerUnionMillis=union_ms([(s['startNanos'],s['endNanos']) for s in providers]),
               providerSummedMillis=sum(s['endNanos']-s['startNanos'] for s in providers)/1e6,
               providerCallsByStage=dict(Counter(s['stage'] for s in providers)),
               jdbcUnionMillis=union_ms([(s['startNanos'],s['endNanos']) for s in jdbc]),
               jdbcQueries=counts['jdbc.executeQuery'], jdbcUpdates=counts['jdbc.executeUpdate'], jdbcCommits=counts['jdbc.commit'],
               targetCount=len(targets), batches=dict(batches), spanGroups=groups,
               cacheHit=metrics.get('cache.hit',0), cacheMiss=metrics.get('cache.miss',0),
               cacheInvalid=metrics.get('cache.invalid',0), checkpointSkip=metrics.get('checkpoint.skip',0),
               checkpointInvalid=metrics.get('checkpoint.invalid',0), jfr=sample['jfr'])
    if raw['instrumented']:
        assert len(providers) == sample['llmCalls']
        assert len(lease) % 2 == 0
        run['checkpointHitRate'] = metrics.get('checkpoint.skip', 0) / len(targets) if targets else None
        run['leaseQueries'] = len(lease)
        run['leaseAssertCalls'] = len(lease) // 2
        run['leaseQueryUnionMillis'] = union_ms([(s['startNanos'],s['endNanos']) for s in lease])
        run['wallOutsideMockProviderMillis'] = sample['wallMillis'] - run['providerUnionMillis']
        run['batchCompletionSkewMillis'] = {stage: (max(b['endNanos'] for b in group)-min(b['endNanos'] for b in group))/1e6 for stage,group in batches.items()}
    else:
        assert not spans
        for key in ('providerUnionMillis','providerSummedMillis','providerCallsByStage','jdbcUnionMillis','jdbcQueries','jdbcUpdates','jdbcCommits','targetCount'):
            run[key] = None
        run['checkpointHitRate'] = run['leaseQueries'] = run['leaseAssertCalls'] = run['leaseQueryUnionMillis'] = run['wallOutsideMockProviderMillis'] = None
    run['inputTokens'] = run['outputTokens'] = run['providerQueueMillis'] = run['providerNetworkMillis'] = None
    runs.append(run)
summary = {}
for workload in sorted(set(run['workload'] for run in runs)):
    group = [run for run in runs if run['workload'] == workload]
    fields = ['wallMillis','processCpuMillis','llmCalls','providerUnionMillis','providerSummedMillis','jdbcUnionMillis','jdbcQueries','jdbcUpdates','jdbcCommits','cacheHit','cacheMiss','cacheInvalid','checkpointSkip','checkpointInvalid','leaseQueries','leaseAssertCalls','leaseQueryUnionMillis','wallOutsideMockProviderMillis','checkpointHitRate']
    result = {field: stats([run[field] for run in group]) for field in fields}
    result['metrics'] = {key: stats([run['metrics'].get(key,0) for run in group]) for key in sorted(set().union(*(run['metrics'].keys() for run in group)))}
    result['spanGroups'] = {name: {key: stats([run['spanGroups'].get(name,{}).get(key,0) for run in group]) for key in ('count','unionMillis','summedMillis')} for name in sorted(set().union(*(run['spanGroups'].keys() for run in group)))}
    summary[workload] = result
evidence = {key: value for key,value in raw.items() if key != 'samples'}
evidence.update(method='Current build only; profile/control sensitivity, no optimized runtime variant',
                percentile='linear interpolation; six samples are descriptive, not stable production tails',
                rawSource=str(source), samples=runs, summary=summary)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes((json.dumps(evidence, ensure_ascii=False, indent=2)+'\n').encode('utf-8'))
print('Wrote', output, 'windows', len(runs))
for workload, result in summary.items():
    print(workload, 'wall',result['wallMillis'],'provider',result['providerUnionMillis'],'jdbc',result['jdbcUnionMillis'])
