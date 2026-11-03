# Source update performance optimization

## Scope and execution plan

Specification: source-update optimization requested on 2026-11-03. Preserve
semantic validators, impact detection, fingerprint format, provenance and human review.
No prompt reductions, database schema changes or provider tuning.

1. Instrument parse/diff, impact, dependency loads/scans/hash/build, context,
   input/output fingerprints and semantic validation. Existing pipeline metrics
   already measure stage wall time, provider time, calls, retries and cache outcomes.
2. Run a repeatable local benchmark before changes to reuse. Keep the same fixture,
   source change and fingerprint workload for the optimized run.
3. Add a run-scoped source session. Establish source identity and digest at entry;
   monitor changes and validate again at exit. Reuse the dependency index across
   nested batch input scopes and workers; preserve synchronization around mutable
   JavaParser resolvers. On untrusted inputs use the existing verified-load path.
4. Test mutation/invalidation, parallel workers, scope cleanup/resume and provenance;
   run the existing diff, fingerprint, semantic and pipeline suites.
5. Review changes with the read-only reviewer, fix findings, record measurements
   and limitations here, and commit separately for baseline and optimization.

## Measurement rules

All duration metrics use monotonic nanoseconds and include failed attempts.
Worker durations are cumulative, not additive components of wall time; nested
fingerprint/validation/context timings overlap. Counters are per invocation scope.
No bottleneck or speed target is claimed before measurement.
