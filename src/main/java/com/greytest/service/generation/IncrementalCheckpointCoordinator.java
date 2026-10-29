package com.greytest.service.generation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.*;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.GenerationJobService;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.LlmResponseException;
import lombok.extern.slf4j.Slf4j;

/** Điều phối lease, fingerprint và checkpoint; không giữ transaction trong lúc chờ AI. */
@Slf4j
@Service
public class IncrementalCheckpointCoordinator {
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final String RUN_KEY = IncrementalCheckpointCoordinator.class.getName();
    private final GenerationCheckpointStore store;
    private final GenerationFingerprintService fingerprints;
    private final SourceUpdateItemRepository items;
    private final AIAgentService agent;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final GenerationJobService jobs;
    private org.springframework.jdbc.core.JdbcTemplate artifactJdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.SourceUpdateRepository updates;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.SourceRevisionRepository revisions;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.BusinessRuleRepository rules;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.TestPlanRepository plans;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.TestCaseRepository cases;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.UnitTestRepository unitTests;
    @org.springframework.beans.factory.annotation.Autowired
    private com.greytest.repository.TestPlanCoveredRuleRepository coveredRules;
    private record Run(GenerationCheckpointStore.Lease lease,
            Map<GenerationProgressStage,Set<Long>> targets,Set<GenerationProgressStage> generated) {
        Run(GenerationCheckpointStore.Lease lease) { this(lease,new ConcurrentHashMap<>(),ConcurrentHashMap.newKeySet()); }
    }
    public IncrementalCheckpointCoordinator(GenerationCheckpointStore store, GenerationFingerprintService fingerprints,
            SourceUpdateItemRepository items, AIAgentService agent, ObjectMapper mapper,
            PlatformTransactionManager transactions, GenerationJobService jobs) {
        this.store=store; this.fingerprints=fingerprints; this.items=items; this.agent=agent; this.mapper=mapper;
        this.transaction=new TransactionTemplate(transactions); this.jobs=jobs;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IncrementalCheckpointCoordinator(GenerationCheckpointStore store, GenerationFingerprintService fingerprints,
            SourceUpdateItemRepository items, AIAgentService agent, ObjectMapper mapper,
            PlatformTransactionManager transactions, GenerationJobService jobs, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this(store,fingerprints,items,agent,mapper,transactions,jobs);
        this.artifactJdbc=jdbc;
    }

    public <T> T withRun(Long projectId, Long updateId, Long actor, Supplier<T> task) {
        return jobs.executeMutation(projectId, () -> {
            try (var scope = GenerationJobContext.openScope(actor)) {
                GenerationJobContext.setSourceUpdateId(updateId);
                var lease = store.claim(projectId,updateId,TTL);
                var scheduler=Executors.newSingleThreadScheduledExecutor(r -> {
                    var thread=new Thread(r,"incremental-lease-"+updateId); thread.setDaemon(true); return thread;
                });
                var heartbeat=scheduler.scheduleAtFixedRate(() -> {
                    try { store.heartbeat(lease,TTL); }
                    catch(RuntimeException lost) { log.warn("Generation lease heartbeat failed update={}",updateId,lost); }
                },20,20,TimeUnit.SECONDS);
                long started=System.nanoTime(); String state="SUCCEEDED";
                try { return GenerationJobContext.withValue(RUN_KEY,new Run(lease),task); }
                catch (RuntimeException failure) { state="FAILED"; throw failure; }
                finally {
                    heartbeat.cancel(true); scheduler.shutdownNow();
                    GenerationJobContext.recordDuration("pipeline",System.nanoTime()-started);
                    try { store.release(lease,state,json(GenerationJobContext.metrics())); }
                    catch(RuntimeException lost) { log.warn("Cannot release expired generation lease update={}",updateId,lost); }
                    log.info("Incremental generation update={} metrics={}",updateId,GenerationJobContext.metrics());
                }
            }
        });
    }

    public void beginStage(Long updateId,GenerationProgressStage stage) {
        Run run=GenerationJobContext.scopedValue(RUN_KEY);
        if(stage.ordinal()>0) {
            var previous=GenerationProgressStage.values()[stage.ordinal()-1];
            if(store.find(updateId,previous.name(),"__stage__").filter(c->"SUCCEEDED".equals(c.state())).isEmpty())
                throw new IllegalArgumentException("Hãy hoàn tất "+previous+" trước "+stage);
        }
        run.targets().put(stage,ConcurrentHashMap.newKeySet());
        store.mark(run.lease(),stage.name(),"__stage__","BLOCKED","v1","{\"formatVersion\":1}",null,"Stage đang được xác thực");
    }

    public void completeStage(Long updateId,GenerationProgressStage stage) {
        Run run=GenerationJobContext.scopedValue(RUN_KEY);
        var targets=run.targets().getOrDefault(stage,Set.of()).stream().sorted().map(Object::toString).toList();
        if(!run.generated().contains(stage)) GenerationJobContext.increment("stage.skip");
        transaction.executeWithoutResult(status->store.complete(run.lease(),stage.name(),"__stage__","v1", "{\"formatVersion\":1}",
            json(Map.of("targets",targets)),"validated"));
    }

    public com.greytest.entity.SourceUpdate changeStatus(Long updateId,com.greytest.entity.enums.SourceUpdateStatus state) {
        Run run=GenerationJobContext.scopedValue(RUN_KEY);
        return transaction.execute(status->{
            store.assertLease(run.lease());
            var update=updates.findById(updateId).orElseThrow(); update.setStatus(state);
            return updates.saveAndFlush(update);
        });
    }

    public <T> T mutate(Supplier<T> action) {
        Run run=GenerationJobContext.scopedValue(RUN_KEY);
        return transaction.execute(status->{store.assertLease(run.lease()); return action.get();});
    }

    public String activeSnapshot(SourceUpdateItem item) {
        if(item.getTargetId()==null) return null;
        Object active=switch(item.getTargetType()) {
            case BUSINESS_RULE -> rules.findById(item.getTargetId()).orElse(null);
            case TEST_PLAN -> plans.findById(item.getTargetId()).map(plan->Map.of("plan",plan,"coveredRuleIds",
                coveredRules.findByTestPlanId(plan.getId()).stream().map(r->r.getBusinessRuleId()).sorted().toList())).orElse(null);
            case TEST_CASE -> cases.findById(item.getTargetId()).orElse(null);
            case UNIT_TEST -> unitTests.findById(item.getTargetId()).orElse(null);
            default -> null;
        };
        return active==null ? null : json(active);
    }

    public void assertActiveArtifactUnchanged(SourceUpdateItem item) {
        if(item.getAction()==com.greytest.entity.enums.SourceUpdateAction.CREATE && item.getTargetId()!=null
                && item.getTargetType()!=com.greytest.entity.enums.SourceUpdateTargetType.METHOD && item.getBeforeData()==null)
            throw new IllegalArgumentException("Artifact tạm thiếu snapshot ban đầu; hãy tiếp tục sinh để giải quyết xung đột "+item.getId());
        String current=activeSnapshot(item);
        boolean temporary=item.getAction()==com.greytest.entity.enums.SourceUpdateAction.CREATE;
        boolean same=Objects.equals(artifactContent(item.getBeforeData(),temporary),artifactContent(current,temporary));
        if(item.getBeforeData()!=null && !same)
            throw new IllegalArgumentException("Artifact đang hoạt động đã được chỉnh sửa sau khi tạo đề xuất "+item.getId()+"; cần giải quyết xung đột trước Apply");
    }

    private JsonNode artifactContent(String snapshot,boolean temporary) {
        if(snapshot==null) return null;
        try {
            var content=mapper.readTree(snapshot);
            // Timestamp VM có thể nhiều chữ số hơn PostgreSQL; provenance so nội dung và trạng thái thực tế.
            if(temporary && content instanceof com.fasterxml.jackson.databind.node.ObjectNode row) {
                row.remove(List.of("createdAt","updatedAt"));
                if(row.path("plan") instanceof com.fasterxml.jackson.databind.node.ObjectNode plan) plan.remove(List.of("createdAt","updatedAt"));
            }
            return content;
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Snapshot artifact tạm không hợp lệ",invalid); }
    }

    /** Khóa ngắn khi kiểm tra; transaction gọi AI nằm ngoài phạm vi này. */
    public void assertTemporaryArtifactsUnchanged(Long updateId) {
        transaction.executeWithoutResult(status -> {
            var temporary=items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i->i.getAction()==com.greytest.entity.enums.SourceUpdateAction.CREATE && i.getTargetId()!=null
                    && i.getTargetType()!=com.greytest.entity.enums.SourceUpdateTargetType.METHOD).toList();
            for(var type:List.of(com.greytest.entity.enums.SourceUpdateTargetType.BUSINESS_RULE,
                    com.greytest.entity.enums.SourceUpdateTargetType.TEST_PLAN,com.greytest.entity.enums.SourceUpdateTargetType.TEST_CASE,
                    com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST)) {
                String sql=switch(type) {
                    case BUSINESS_RULE -> "SELECT id FROM business_rule WHERE id=? FOR UPDATE";
                    case TEST_PLAN -> "SELECT id FROM test_plan WHERE id=? FOR UPDATE";
                    case TEST_CASE -> "SELECT id FROM test_case WHERE id=? FOR UPDATE";
                    case UNIT_TEST -> "SELECT id FROM unit_test WHERE id=? FOR UPDATE";
                    default -> throw new IllegalArgumentException("Unsupported temporary artifact");
                };
                if(artifactJdbc!=null) for(Long id:temporary.stream().filter(i->i.getTargetType()==type)
                        .map(SourceUpdateItem::getTargetId).distinct().sorted().toList()) artifactJdbc.queryForList(sql,id);
            }
            for(var item:temporary) {
                try { assertActiveArtifactUnchanged(item); }
                catch(IllegalArgumentException changed) {
                    var proposal=mapper.valueToTree(item);
                    ((com.fasterxml.jackson.databind.node.ObjectNode)proposal).put("activeSnapshot",activeSnapshot(item));
                    throw new GenerationConflictException(item.getId(),proposal.toString());
                }
            }
        });
    }

    private String conflictEnvelope(GenerationConflictException conflict) throws java.io.IOException {
        var proposal=mapper.readTree(conflict.proposal());
        var envelope=mapper.createObjectNode(); envelope.put("itemId",conflict.itemId());
        envelope.put("proposedAfterData",proposal.path("afterData").asText());
        envelope.put("proposedAction",proposal.path("action").asText());
        if(proposal.has("activeSnapshot")) envelope.set("activeSnapshot",proposal.get("activeSnapshot"));
        return envelope.toString();
    }

    private void checkTemporaryInputs(Run run,Long updateId) {
        try { assertTemporaryArtifactsUnchanged(updateId); }
        catch(GenerationConflictException conflict) {
            try {
                var item=items.findById(conflict.itemId()).orElseThrow();
                var stage=GenerationProgressStage.valueOf(item.getTargetType().name());
                var output=mapper.readTree(item.getAfterData());
                Set<String> targets=new LinkedHashSet<>();
                String field=switch(stage) { case BUSINESS_RULE->"method_id"; case TEST_PLAN->"rule_id";
                    case TEST_CASE->"plan_id"; case UNIT_TEST->"case_id"; };
                if(output.hasNonNull(field)) targets.add(output.path(field).asText());
                if(stage==GenerationProgressStage.TEST_PLAN) output.path("covered_rule_ids").forEach(id->targets.add(id.asText()));
                for(String target:targets) {
                    var previous=store.find(updateId,stage.name(),target);
                    store.mark(run.lease(),stage.name(),target,"CONFLICT",previous.map(GenerationCheckpointStore.Checkpoint::fingerprint).orElse(null),
                        previous.map(GenerationCheckpointStore.Checkpoint::provenance).orElse(null),conflictEnvelope(conflict),conflict.getMessage());
                }
            } catch(Exception recording) { conflict.addSuppressed(recording); }
            throw conflict;
        }
    }

    public boolean isRecordedOutput(SourceUpdateItem item) {
        return store.list(item.getSourceUpdateId()).stream().filter(c->"SUCCEEDED".equals(c.state()))
            .anyMatch(c->{
                try { for(var id:mapper.readTree(c.outputJson()).path("itemIds")) if(id.asLong()==item.getId()) return true; }
                catch(Exception invalid) { return false; } return false;
            });
    }

    /** Apply kiểm tra lại input và output hiện thời; cache không được xem như quyết định duyệt. */
    public void assertReadyForApply(Long updateId) {
        com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(() -> {
            assertReadyWithInputs(updateId);
            return null;
        });
    }

    private void assertReadyWithInputs(Long updateId) {
        var update=updates.findById(updateId).orElseThrow();
        GenerationImpactValidator.parse(mapper,update.getImpactSummary());
        assertTemporaryArtifactsUnchanged(updateId);
        Path source=revisions.findById(update.getCandidateRevisionId()).map(r->Path.of(r.getStoragePath())).orElseThrow();
        try(var scope=GenerationJobContext.openScope(null)) {
            GenerationJobContext.setSourceUpdateId(updateId);
            var current=items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
            for(var stage:GenerationProgressStage.values()) {
                var marker=store.find(updateId,stage.name(),"__stage__").filter(c->"SUCCEEDED".equals(c.state()))
                    .orElseThrow(()->new IllegalArgumentException("Chưa hoàn tất stage "+stage));
                try {
                    Set<Long> targets=new LinkedHashSet<>();
                    mapper.readTree(marker.outputJson()).path("targets").forEach(target->targets.add(target.asLong()));
                    for(Long id:targets) {
                        var checkpoint=store.find(updateId,stage.name(),id.toString()).filter(c->"SUCCEEDED".equals(c.state())).orElseThrow();
                        Object context=targetContext(stage,update.getProjectId(),id,targets,source);
                        var fp=fingerprints.fingerprint(prompt(stage),context,source);
                        if(!fp.value().equals(checkpoint.fingerprint())) throw new IllegalArgumentException("Input đã thay đổi ở "+stage+" "+id+"; hãy tiếp tục sinh");
                        Object output=responseFromItems(stage,current.stream().filter(i->belongs(stage,i,id)).toList());
                        var outputFp=fingerprints.outputFingerprint(output,source,context);
                        var envelope=mapper.readTree(checkpoint.outputJson());
                        if(!outputFp.reusable() || !outputFp.value().equals(envelope.path("outputFingerprint").asText()))
                            throw new IllegalArgumentException("Dependency của output đã thay đổi ở "+stage+" "+id+"; hãy tiếp tục sinh");
                        agent.validateSnapshotOutput(stage,update.getProjectId(),context,output,source);
                    }
                } catch(IllegalArgumentException invalid) { throw invalid; }
                catch(Exception invalid) { throw new IllegalArgumentException("Checkpoint chưa hợp lệ cho "+stage+"; hãy tiếp tục sinh",invalid); }
            }
        }
    }

    /** Mỗi input giữ checkpoint riêng, nên thay đổi một method không buộc sinh lại cả batch. */
    public <R> R executeBatch(Long projectId,Long updateId,GenerationProgressStage stage,Set<Long> ids,Path source,
            Class<R> responseType,Function<Set<Long>,R> generate,BiConsumer<Set<Long>,R> persist) {
        return com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(
            ()->executeBatchWithInputs(projectId,updateId,stage,ids,source,responseType,generate,persist));
    }

    private <R> R executeBatchWithInputs(Long projectId,Long updateId,GenerationProgressStage stage,Set<Long> ids,Path source,
            Class<R> responseType,Function<Set<Long>,R> generate,BiConsumer<Set<Long>,R> persist) {
        Run run=GenerationJobContext.scopedValue(RUN_KEY);
        if(run==null) throw new IllegalStateException("Generation run is not bound");
        checkTemporaryInputs(run,updateId);
        run.targets().computeIfAbsent(stage,ignored->ConcurrentHashMap.newKeySet()).addAll(ids);
        long started=System.nanoTime();
        Map<Long,GenerationFingerprintService.Fingerprint> inputs=new LinkedHashMap<>();
        Map<Long,Object> contexts=new LinkedHashMap<>();
        Set<Long> pending=new LinkedHashSet<>();
        for(Long id:ids.stream().sorted().toList()) {
            Object context=targetContext(stage,projectId,id,ids,source);
            contexts.put(id,context);
            var fp=fingerprints.fingerprint(prompt(stage),context,source); inputs.put(id,fp);
            var saved=store.find(updateId,stage.name(),id.toString());
            boolean reusable=false;
            if(fp.reusable() && saved.isPresent() && ("SUCCEEDED".equals(saved.get().state()) || "BLOCKED".equals(saved.get().state()))
                    && fp.value().equals(saved.get().fingerprint())) {
                try {
                    JsonNode envelope=mapper.readTree(saved.get().outputJson());
                    List<Long> expected=new ArrayList<>(); envelope.path("itemIds").forEach(n->expected.add(n.asLong()));
                    var current=items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                        .filter(i->expected.contains(i.getId())).toList();
                    boolean resolved="BLOCKED".equals(saved.get().state()) && envelope.hasNonNull("resolution");
                    if(resolved) current=items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream().filter(i->belongs(stage,i,id)).toList();
                    boolean exact=outputDigest(mapper,current).equals(saved.get().outputDigest());
                    var outputFp=fingerprints.outputFingerprint(envelope.path("response"),source,context);
                    exact=exact && outputFp.reusable() && outputFp.value().equals(envelope.path("outputFingerprint").asText());
                    boolean reviewed=!current.isEmpty() && current.stream().allMatch(i-> {
                        long oldRevision=envelope.path("revisions").path(i.getId().toString()).asLong(Long.MAX_VALUE);
                        return i.getUserRevision()!=null && (i.getUserRevision()>oldRevision
                            || (i.getUserRevision()==oldRevision && outputDigest(mapper,List.of(i))
                                .equals(envelope.path("itemDigests").path(i.getId().toString()).asText())));
                    });
                    reviewed=reviewed && current.stream().anyMatch(i->i.getUserRevision()!=null
                        && i.getUserRevision()>envelope.path("revisions").path(i.getId().toString()).asLong(Long.MAX_VALUE));
                    if(!expected.isEmpty() && current.size()==expected.size() && exact) {
                        // Digest đã validate, revision và validator-version không đổi: không ghi lại checkpoint.
                        reusable=true;
                    } else if((!expected.isEmpty() && current.size()==expected.size() && reviewed) || (resolved && !current.isEmpty())) {
                        R output=responseType.cast(responseFromItems(stage,current));
                        agent.validateSnapshotOutput(stage,projectId,context,output,source);
                        if(!fingerprints.outputFingerprint(output,source,context).reusable()) throw new LlmResponseException("Output dependencies remain unresolved");
                        var validatedItems=current;
                        transaction.executeWithoutResult(status->store.complete(run.lease(),stage.name(),id.toString(),fp.value(),fp.provenance(),
                            envelope(output,validatedItems,source,context),outputDigest(mapper,validatedItems)));
                        reusable=true;
                    }
                } catch(Exception invalid) { GenerationJobContext.increment("checkpoint.invalid"); }
            }
            if(!reusable) pending.add(id);
        }
        if(stage==GenerationProgressStage.TEST_PLAN && !pending.isEmpty()) {
            Set<Long> pendingMethods=new HashSet<>();
            for(Long id:pending) pendingMethods.add(rules.findById(id).orElseThrow().getMethodId());
            for(Long id:ids) if(pendingMethods.contains(rules.findById(id).orElseThrow().getMethodId())) pending.add(id);
        }
        for(Long id:ids) if(!pending.contains(id)) GenerationJobContext.increment("checkpoint.skip");
        if(pending.isEmpty()) {
            GenerationJobContext.increment("batch.skip");
            GenerationJobContext.recordDuration(stage.name(),System.nanoTime()-started); return null;
        }
        Set<Long> committed=new HashSet<>();
        Set<Long> producedArtifactIds=new HashSet<>();
        try {
            R response=generate.apply(pending);
            if(response==null) throw new LlmResponseException("AI output is missing");
            R validatedResponse=response;
            Set<Long> eligible=new LinkedHashSet<>(pending);
            boolean blocked=response instanceof UnitTestResponseDto unit && unit.blockedCases()!=null && !unit.blockedCases().isEmpty();
            if(blocked) {
                var unit=(UnitTestResponseDto)response;
                var blockedIds=unit.blockedCases().stream().map(c->c.caseId()).collect(java.util.stream.Collectors.toSet());
                eligible.removeAll(blockedIds);
                validatedResponse=responseType.cast(new UnitTestResponseDto(unit.unitTests()));
            }
            final R successfulResponse=validatedResponse;
            Set<Long> unresolvedOutputs=new LinkedHashSet<>();
            // Chỉ công nhận thành công sau validation đầy đủ và commit cùng các artifact.
            if(!eligible.isEmpty()) {
            Object context=agent.snapshotContext(stage,projectId,eligible,source);
            agent.validateSnapshotOutput(stage,projectId,context,successfulResponse,source);
            transaction.executeWithoutResult(status->{
                store.assertLease(run.lease());
                assertTemporaryArtifactsUnchanged(updateId);
                com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(()-> {
                for(Long id:eligible) {
                    var fresh=fingerprints.fingerprint(prompt(stage),targetContext(stage,projectId,id,ids,source),source);
                    if(!fresh.value().equals(inputs.get(id).value())) throw new LlmResponseException("Generation input changed while AI was running; retry required");
                }
                return null;
                });
                persist.accept(eligible,successfulResponse);
                var savedItems=items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
                for(Long id:eligible) {
                    Object subset=subset(stage,successfulResponse,id);
                    var produced=savedItems.stream().filter(i->belongs(stage,i,id)).toList();
                    produced.stream().filter(item->responseContains(subset,item)).map(SourceUpdateItem::getId)
                        .filter(Objects::nonNull).forEach(producedArtifactIds::add);
                    if(produced.isEmpty() || emptyOutput(subset))
                        throw new LlmResponseException("Output incomplete for "+stage+" target "+id);
                    var fp=inputs.get(id);
                    var outputFp=fingerprints.outputFingerprint(subset,source,contexts.get(id));
                    if(outputFp.reusable()) {
                        store.complete(run.lease(),stage.name(),id.toString(),fp.value(),fp.provenance(),
                            envelope(subset,produced,source,contexts.get(id)),outputDigest(mapper,produced));
                    } else {
                        unresolvedOutputs.add(id);
                        store.mark(run.lease(),stage.name(),id.toString(),"BLOCKED",fp.value(),fp.provenance(),
                            envelope(subset,produced,source,contexts.get(id)),"Chưa xác minh được dependency của output: "+outputFp.provenance());
                    }
                }
            });
            committed.addAll(eligible);
            producedArtifactIds.forEach(ignored->GenerationJobContext.increment("artifact.regenerated"));
            run.generated().add(stage);
            }
            if(!unresolvedOutputs.isEmpty()) throw new com.greytest.service.agent.TestFeasibilityBlockedException(
                "Đã giữ bản nháp nhưng chưa xác minh được dependency của output "+stage+": "+unresolvedOutputs);
            if(blocked) throw new com.greytest.service.agent.TestFeasibilityBlockedException("Còn Test Case bị chặn: "+((UnitTestResponseDto)response).blockedCases());
            return response;
        } catch(RuntimeException failure) {
            for(Long id:pending) {
                if(committed.contains(id)) continue;
                var fp=inputs.get(id);
                try {
                    String state="FAILED", proposal=null;
                    if(failure instanceof com.greytest.service.agent.TestFeasibilityBlockedException) state="BLOCKED";
                    if(failure instanceof GenerationConflictException conflict
                            && conflictApplies(stage,id,items.findById(conflict.itemId()).orElseThrow())) {
                        proposal=conflictEnvelope(conflict); state="CONFLICT";
                    }
                    store.mark(run.lease(),stage.name(),id.toString(),state,fp.value(),fp.provenance(),proposal,failure.getMessage());
                }
                catch(RuntimeException lost) { failure.addSuppressed(lost); }
                catch(java.io.IOException invalid) { failure.addSuppressed(invalid); }
            }
            throw failure;
        } finally { GenerationJobContext.recordDuration(stage.name(),System.nanoTime()-started); }
    }

    private boolean conflictApplies(GenerationProgressStage stage,Long id,SourceUpdateItem item) {
        if(belongs(stage,item,id)) return true;
        try {
            JsonNode value=item.getAfterData()==null ? mapper.createObjectNode() : mapper.readTree(item.getAfterData());
            Long caseId = null;
            if (value.hasNonNull("case_id")) caseId = value.path("case_id").asLong();
            else if (item.getTargetKey() != null && item.getTargetKey().startsWith("case:"))
                caseId = Long.valueOf(item.getTargetKey().substring(5));
            // Đề xuất REMOVE có thể không còn afterData/targetKey; lần theo artifact để nhận đúng xung đột của case cha.
            if (caseId == null && item.getTargetType() == com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST
                    && item.getTargetId() != null && unitTests != null) {
                caseId = unitTests.findById(item.getTargetId()).map(com.greytest.entity.UnitTest::getTestCaseId).orElse(null);
            }
            if(stage==GenerationProgressStage.TEST_CASE) {
                Long targetCase=caseId!=null ? caseId : item.getTargetType()==com.greytest.entity.enums.SourceUpdateTargetType.TEST_CASE ? item.getTargetId() : null;
                return targetCase!=null && cases.findById(targetCase).map(c->Objects.equals(c.getTestPlanId(),id)).orElse(false);
            }
            if(stage==GenerationProgressStage.UNIT_TEST) return Objects.equals(caseId,id);
            if(stage==GenerationProgressStage.BUSINESS_RULE)
                return item.getTargetId()!=null && rules.findById(item.getTargetId()).map(r->Objects.equals(r.getMethodId(),id)).orElse(false);
            return item.getTargetId()!=null && plans.findById(item.getTargetId())
                .flatMap(p->rules.findById(p.getBusinessRuleId())).map(r->rules.findById(id)
                    .map(input->Objects.equals(input.getMethodId(),r.getMethodId())).orElse(false)).orElse(false);
        } catch(Exception invalid) { return false; }
    }

    private boolean responseContains(Object response,SourceUpdateItem item) {
        if(item.getAfterData()==null) return false;
        try {
            var output=mapper.readTree(mapper.writeValueAsString(response));
            var persisted=mapper.readTree(item.getAfterData());
            for(var field:output) if(field.isArray()) for(var value:field) if(value.equals(persisted)) return true;
            return false;
        } catch(java.io.IOException invalid) { return false; }
    }

    private String envelope(Object response,List<SourceUpdateItem> produced,Path source,Object context) {
        Map<String,Long> revisions=new TreeMap<>();
        Map<String,String> digests=new TreeMap<>();
        produced.forEach(item->revisions.put(item.getId().toString(),item.getUserRevision()==null ? 0L : item.getUserRevision()));
        produced.forEach(item->digests.put(item.getId().toString(),outputDigest(mapper,List.of(item))));
        var outputFp=fingerprints.outputFingerprint(response,source,context);
        return json(Map.of("response",response,"itemIds",produced.stream().map(SourceUpdateItem::getId).toList(),"revisions",revisions,"itemDigests",digests,
            "outputFingerprint",outputFp.value(),"outputProvenance",outputFp.provenance(),"outputReusable",outputFp.reusable()));
    }

    private Object responseFromItems(GenerationProgressStage stage,List<SourceUpdateItem> current) {
        if(current.isEmpty()) throw new LlmResponseException("Persisted output is missing");
        try {
            var array=mapper.createArrayNode();
            for(var item:current) {
                var value=effectiveOutput(item);
                if(value!=null) array.add(value);
            }
            var response=mapper.createObjectNode();
            String field=switch(stage) { case BUSINESS_RULE->"rules"; case TEST_PLAN->"plans"; case TEST_CASE->"cases"; case UNIT_TEST->"unit_tests"; };
            response.set(field,array);
            return agent.parseSnapshotOutput(stage,response.toString());
        } catch(java.io.IOException invalid) { throw new LlmResponseException("Persisted output is invalid",invalid); }
    }

    private JsonNode effectiveOutput(SourceUpdateItem item) throws java.io.IOException {
        boolean kept=item.getAction()==com.greytest.entity.enums.SourceUpdateAction.KEEP
            || item.getReviewStatus()==com.greytest.entity.enums.SourceUpdateReviewStatus.REJECTED;
        if(!kept) return mapper.readTree(item.getAfterData());
        if(item.getAction()==com.greytest.entity.enums.SourceUpdateAction.CREATE) return null;
        if(item.getTargetId()==null) throw new LlmResponseException("Kept artifact has no active identity");
        Object output=switch(item.getTargetType()) {
            case BUSINESS_RULE -> rules.findById(item.getTargetId()).map(r->new GeneratedBusinessRuleDto(r.getMethodId(),r.getDescription(),
                "BUSINESS_LOGIC",com.greytest.service.SourceBranchReference.read(r.getReviewNote()))).orElseThrow();
            case TEST_PLAN -> plans.findById(item.getTargetId()).map(p->new GeneratedTestPlanDto(rules.findById(p.getBusinessRuleId()).orElseThrow().getMethodId(),
                p.getBusinessRuleId(),coveredRules.findByTestPlanId(p.getId()).stream().map(r->r.getBusinessRuleId()).sorted().toList(),
                p.getTitle(),p.getDescription(),p.getTestType().name())).orElseThrow();
            case TEST_CASE -> cases.findById(item.getTargetId()).map(c->new GeneratedTestCaseDto(c.getTestPlanId(),c.getTestType().name(),c.getDescription(),
                c.getPreconditions(),c.getTestData(),c.getExpectedResult(),c.getPriority().name(),c.getTraceSource())).orElseThrow();
            case UNIT_TEST -> unitTests.findById(item.getTargetId()).map(u->new GeneratedUnitTestDto(u.getTestCaseId(),u.getTestClassName(),u.getTestMethodName(),
                u.getPackageName(),u.getGenerationType(),u.getSourceCode())).orElseThrow();
            default -> throw new LlmResponseException("Unsupported kept artifact");
        };
        return mapper.valueToTree(output);
    }

    /** Checkpoint TP giữ trọn nhóm BR của method để cùng phạm vi với plan phủ nhiều rule. */
    private Object targetContext(GenerationProgressStage stage,Long projectId,Long id,Set<Long> targets,Path source) {
        Set<Long> selected=Set.of(id);
        if(stage==GenerationProgressStage.TEST_PLAN) {
            Long methodId=rules.findById(id).orElseThrow().getMethodId();
            selected=targets.stream().filter(target->Objects.equals(methodId,rules.findById(target).orElseThrow().getMethodId()))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        return agent.snapshotContext(stage,projectId,selected,source);
    }

    private boolean belongs(GenerationProgressStage stage,SourceUpdateItem item,Long id) {
        if(item.getTargetType()==null || !item.getTargetType().name().equals(stage.name()) || item.getAfterData()==null) return false;
        try {
            var n=mapper.readTree(item.getAfterData());
            return switch(stage) {
                case BUSINESS_RULE -> n.path("method_id").asLong()==id;
                case TEST_PLAN -> rules.findById(id).map(rule->n.path("method_id").asLong()==rule.getMethodId()).orElse(false);
                case TEST_CASE -> n.path("plan_id").asLong()==id;
                case UNIT_TEST -> n.path("case_id").asLong()==id;
            };
        } catch(Exception e) { return false; }
    }
    private static boolean contains(JsonNode array,Long id) {
        for(var item:array) if(item.asLong()==id) return true; return false;
    }
    private Object subset(GenerationProgressStage stage,Object output,Long id) {
        return switch(stage) {
            case BUSINESS_RULE -> new BusinessRuleResponseDto(((BusinessRuleResponseDto)output).rules().stream().filter(r->id.equals(r.methodId())).toList());
            case TEST_PLAN -> new TestPlanResponseDto(((TestPlanResponseDto)output).plans().stream()
                .filter(p->rules.findById(id).map(rule->Objects.equals(rule.getMethodId(),p.methodId())).orElse(false)).toList());
            case TEST_CASE -> new TestCaseResponseDto(((TestCaseResponseDto)output).cases().stream().filter(c->id.equals(c.planId())).toList());
            case UNIT_TEST -> new UnitTestResponseDto(((UnitTestResponseDto)output).unitTests().stream().filter(u->id.equals(u.caseId())).toList());
        };
    }
    private static boolean emptyOutput(Object response) {
        if(response instanceof BusinessRuleResponseDto r) return r.rules().isEmpty();
        if(response instanceof TestPlanResponseDto r) return r.plans().isEmpty();
        if(response instanceof TestCaseResponseDto r) return r.cases().isEmpty();
        return response instanceof UnitTestResponseDto r && r.unitTests().isEmpty();
    }
    public static String prompt(GenerationProgressStage stage) {
        return switch(stage) { case BUSINESS_RULE->"business-rule"; case TEST_PLAN->"test-plan";
            case TEST_CASE->"test-case"; case UNIT_TEST->"unit-test"; };
    }
    public static String outputDigest(ObjectMapper mapper,List<SourceUpdateItem> items) {
        try {
            var rows=items.stream().sorted(Comparator.comparing(SourceUpdateItem::getId)).map(i-> {
                Map<String,Object> row=new TreeMap<>(); row.put("id",i.getId()); row.put("targetId",i.getTargetId());
                row.put("key",i.getTargetKey()); row.put("action",i.getAction()); row.put("after",i.getAfterData());
                row.put("review",i.getReviewStatus()); row.put("revision",i.getUserRevision()); return row;
            }).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(rows)));
        } catch(Exception exception) { throw new IllegalStateException("Cannot digest artifact output",exception); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("Cannot serialize generation checkpoint",e); }
    }
}
