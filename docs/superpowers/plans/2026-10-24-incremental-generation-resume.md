# Incremental Generation Optimization Implementation Plan

> **For agentic workers:** Use subagent-driven-development for isolated tasks and a read-only reviewer after integration. Do not commit unrelated existing work.

**Goal:** Resume validated incremental artifacts safely without repeated snapshot parsing or unnecessary LLM calls.

**Architecture:** PostgreSQL stores validated AI responses and fenced artifact checkpoints. Immutable snapshot analysis is shared within a run and reused between stages. Draft overlays and revision guards protect human decisions.

**Tech Stack:** Java 17, Spring Boot/JPA/JDBC/Flyway, PostgreSQL 15, JavaParser, React/TypeScript, JUnit/Mockito/Vitest.

**Spec:** `docs/superpowers/specs/2026-10-24-incremental-generation-resume-design.md` (approved 2026-10-24).

## Global constraints

- Preserve BR → TP → TC → UT and current public APIs; add only the approved resume/status/conflict API.
- Do not overwrite or delete user-reviewed artifacts; checkpoint success and output commit must be atomic.
- Validate cache output against current input/dependencies/revisions; no success for incomplete/failed output.
- Do not hold a database transaction/connection during LLM calls. Expired workers cannot commit.
- No RAG implementation. Store versioned provenance and dependency manifests with checkpoint/cache entries.
- All Spring integration tests MUST set `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024` and `LLM_PROVIDER=mock` in the command environment.
- Primary DB backup: workspace `.greytest-generation-verification/backups/greytest-pre-v27-20261024.dump`. Never reset/delete the primary database or restored historical rows.
- Work in the current dirty checkout so existing uncommitted pipeline dependencies are preserved. Save task-specific baselines for review; no reset/clean/automatic commits.

## Task 1: Baseline benchmark and shared analysis

**Files:** new `src/test/java/com/greytest/service/IncrementalPipelineBenchmarkTest.java` and fixture; modify `GenerationJobContext`, `SourceDiffService`, `SnapshotAnalysisLoader`, `GenerationContextBuilder`; new generation analysis service/tests as needed.

**Interfaces:** Existing `generateIncrementalStage(projectId, updateId, stage, user)` remains callable. Shared immutable source objects use `GenerationJobContext.memoize`; mutable draft input is rebuilt.

- [ ] Write a deterministic integration benchmark using real parser/context/persistence/validation, a recording local test LLM provider, and fixtures with multiple methods/artifacts.
- [ ] Run before production changes; record raw wall-clock samples, LLM calls, analysis parse events, artifact counts/digests for cold and unchanged-repeat workloads. Label the provider as test, not production LLM.
- [ ] Regression: spy parser `scanProject(candidate)` must be called once per diff, and shared snapshot loads across batches once per run.
- [ ] Implement sharing and remove the duplicate candidate scan; preserve parser failure checks.
- [ ] Persist immutable analysis under revision content/parser-version identity; remap current DB IDs separately.
- [ ] Run focused tests and capture before/after evidence before subsequent integration.

## Task 2: Additive V27 and durable stores

**Files:** `src/main/resources/db/migration/V27__incremental_generation_checkpoint_and_cache.sql`; `SourceUpdate`, `SourceUpdateItem`; new `service/generation/GenerationCheckpointStore`, `PersistentLlmResponseStore`; migration/store integration tests.

**Interfaces:**
- `GenerationCheckpointStore.claim(projectId, updateId, Duration)` returns `Lease(projectId, updateId, owner, fence)`.
- `heartbeat(Lease, Duration)`, `assertLease(Lease)`, `release(Lease, state, metricsJson)` fence all writes.
- `find(updateId, stage, targetKey)`, `list(updateId)`, `complete(Lease, stage, key, fingerprint, provenanceJson, outputJson, outputDigest)`, `mark(...)` persist unit status.
- `PersistentLlmResponseStore.get(projectId, stage, fingerprint)` and `putValidated(projectId, stage, fingerprint, response)` use independent transactions and response digests.

- [ ] Confirm primary schema V26 and backup restore into the dedicated test database.
- [ ] Add only new tables and version/user_revision columns; preserve old data.
- [ ] Test actual PostgreSQL migration, unique claim, concurrent claim, lease expiry/fencing, rollback, cache persistence across store instances, and corrupt cache handling.
- [ ] Do not run Maven integration tests without the explicit test DB environment override.
- [ ] Do not migrate primary until the new migration and application integration tests pass.

## Task 3: Fingerprint and dependency index

**Files:** new `service/generation/GenerationFingerprintService`, dependency helper/index and regression tests; `PromptManager`/configuration integration if necessary.

**Interface:** `fingerprint(String stage, Object context, Path sourceRoot)` returns `Fingerprint(String value, String provenance, boolean reusable)`. Provenance is versioned JSON with target, upstream inputs, source/dependency digests and config/prompt/validator identities, without secrets.

- [ ] Tests first: canonical ordering, source unchanged, unrelated method unchanged, target/helper/dependency method change, DTO/build change, overload/module distinction, prompt/config change and own generated output exclusion.
- [ ] Use target method slices and resolved transitive dependencies; never invalidate the whole project for a local method change.
- [ ] Include effective draft BR/TP/TC content; exclude timestamps, line numbers, project status/progress and the artifact's own generated output.
- [ ] Unknown dependency evidence must be explicit and not falsely reusable.
- [ ] Reuse parsed/indexed immutable objects through shared run inputs; no per-artifact full-project reparse.

## Task 4: Resume orchestration, protected draft overlays, safe cache

**Files:** `IncrementalGenerationService`, `AIAgentService`, `LlmResponseCache`, `GenerationContextBuilder`, `SourceUpdateService`, `ImpactAnalysisService`, stage validation, generation services/tests.

- [ ] Prepare context from candidate source plus the current draft overlay; do not update active method source before Apply.
- [ ] Compute stable work-unit/artifact fingerprints and check output digest/revision/completeness before skipping.
- [ ] Claim update and work units with fencing; run LLM without an open transaction; atomically persist validated output plus checkpoint.
- [ ] Persist each successful independent batch even when another fails; resume missing work only.
- [ ] Write validated LLM cache independently, revalidate every cache hit, include provider/model/config/prompt version in identity.
- [ ] Protect human revisions and legacy reviewed items; persist conflicts separately and block Apply until resolved/revalidated.
- [ ] Re-analysis/apply/cancel must coordinate with running generation and preserve user decisions.
- [ ] Dependency changes must select dependent service artifacts even when the service body itself is unchanged.
- [ ] Record concurrency-safe counters and stage/total timings; count actual provider attempts.
- [ ] Regression: retry after failure, partial outputs, changed inputs, concurrent requests, stale worker, user edit/review races, invalid cache and cross-project isolation.

## Task 5: Resume/status/conflict UI

**Files:** `SourceUpdateController`, DTO/mapper, FE source-update API/types and `SourceUpdateModal.tsx`, Vitest tests.

- [ ] Add server-orchestrated generate-all and generation summary while keeping existing per-stage API working.
- [ ] Rehydrate status from checkpoints on modal reopen; do not trust local completedStages to skip validation.
- [ ] Show skip/generated/conflict counts and explicit keep-user/use-proposal conflict choices with revision checks.
- [ ] Never automatically override an existing user decision in Apply or retry.
- [ ] Test unchanged repeat, reopen, failed stage resume, conflicts and stale response handling.

## Task 6: Verification, benchmark and migration report

**Files:** regression suites, benchmark harness, `docs/reports/2026-10-24-incremental-generation-optimization.md`.

- [ ] Run all stage regressions plus connected existing tests against the dedicated test database.
- [ ] Run unchanged/cold/one-method/dependency/retry/concurrent benchmarks with the same fixture and test provider before/after; report raw samples and median/p95, calls/hits/skips/artifact counts.
- [ ] Keep production-provider measurements separate; do not infer them from mock latency or Maven duration.
- [ ] Read-only reviewer checks correctness, data preservation, concurrency and coverage; resolve material findings.
- [ ] Verify backup and schema compatibility again before any primary V27 execution; record exactly which database received which migration.
- [ ] Report files changed, migrations run, test results, measured tradeoffs and remaining limits. Do not claim full completion with required items outstanding.
