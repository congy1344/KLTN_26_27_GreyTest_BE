package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.AuthUser;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.entity.enums.UserTier;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.analysis.AnalysisService;

class IncrementalBusinessRuleBatchTest {

    @Test
    void generatesProposalsForChangesInTwoServiceClasses() throws Exception {
        List<SourceUpdateItem> proposals = generate(List.of(
                serviceClass(1L, "EmailServiceImpl", List.of(method(101L, "sendCustom"))),
                serviceClass(2L, "NotificationServiceImpl", List.of(method(102L, "sendCustomNotification")))));

        assertThat(proposals).extracting(SourceUpdateItem::getAfterData)
                .anySatisfy(json -> assertThat(json).contains("101"))
                .anySatisfy(json -> assertThat(json).contains("102"));
        assertThat(proposals).hasSize(2);
    }

    @Test
    void splitsMoreThanThreeChangedMethodsWithoutLosingAnyProposal() throws Exception {
        List<SourceUpdateItem> proposals = generate(List.of(serviceClass(1L, "NotificationServiceImpl", List.of(
                method(101L, "first"), method(102L, "second"), method(103L, "third"), method(104L, "fourth")))));

        assertThat(proposals).hasSize(4);
        List<Long> methodIds = new ArrayList<>();
        for (SourceUpdateItem item : proposals) {
            methodIds.add(new ObjectMapper().readTree(item.getAfterData()).get("method_id").asLong());
        }
        assertThat(methodIds).containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
    }

    private List<SourceUpdateItem> generate(List<JavaClassDto> classes) throws Exception {
        return generate(classes, null, 1);
    }

    @ParameterizedTest
    @CsvSource({"FREE,2", "PLUS,3", "PRO,3"})
    void generatesAllIncrementalBrProposalsWithTierLimit(UserTier tier, int expectedPeak) throws Exception {
        List<SourceUpdateItem> proposals = generate(List.of(serviceClass(1L, "PriceService", List.of(
                method(101L, "m1"), method(102L, "m2"), method(103L, "m3"), method(104L, "m4"),
                method(105L, "m5"), method(106L, "m6"), method(107L, "m7")))), tier, expectedPeak);

        List<Long> methodIds = new ArrayList<>();
        for (SourceUpdateItem item : proposals) {
            methodIds.add(new ObjectMapper().readTree(item.getAfterData()).get("method_id").asLong());
        }
        assertThat(methodIds).containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 105L, 106L, 107L);
    }

    private List<SourceUpdateItem> generate(List<JavaClassDto> classes, UserTier tier, int expectedPeak) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProjectRepository projects = mock(ProjectRepository.class);
        SourceUpdateRepository updates = mock(SourceUpdateRepository.class);
        SourceUpdateItemRepository items = mock(SourceUpdateItemRepository.class);
        JavaClassRepository classRepository = mock(JavaClassRepository.class);
        JavaMethodRepository methods = mock(JavaMethodRepository.class);
        AnalysisService analysis = mock(AnalysisService.class);
        AIAgentService agent = mock(AIAgentService.class);
        GenerationContextBuilder contexts = new GenerationContextBuilder(analysis, null,
                mock(com.greytest.service.analysis.ExistingTestService.class), null, null, null, null, null, projects);
        Project project = new Project();
        project.setId(248L);
        when(projects.findById(248L)).thenReturn(Optional.of(project));
        List<MethodDiffItem> changes = classes.stream().flatMap(c -> c.methods().stream().map(m -> new MethodDiffItem(
                c.className(), c.qualifiedName(), m.methodName(), m.methodName() + "()",
                c.qualifiedName() + "#" + m.methodName() + "()", MethodDiffType.MODIFIED, "Logic changed",
                "public void " + m.methodName() + "() {}", m.sourceCode(), List.of(), true))).toList();
        SourceUpdate update = new SourceUpdate();
        update.setId(76L);
        update.setProjectId(248L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        update.setImpactSummary(mapper.writeValueAsString(new ImpactSummaryDto(
                changes.size(), 0, changes.size(), 0, changes, List.of(), List.of(), List.of(), List.of(), List.of())));
        when(updates.findByIdAndProjectId(76L, 248L)).thenReturn(Optional.of(update));
        when(updates.save(any())).thenAnswer(call -> call.getArgument(0));
        List<JavaClass> storedClasses = new ArrayList<>();
        for (JavaClassDto c : classes) {
            JavaClass stored = new JavaClass();
            stored.setId(c.id());
            stored.setProjectId(248L);
            stored.setClassType(ClassType.SERVICE);
            stored.setQualifiedName(c.qualifiedName());
            storedClasses.add(stored);
            List<JavaMethod> storedMethods = c.methods().stream().map(m -> {
                JavaMethod result = new JavaMethod();
                result.setId(m.id());
                result.setClassId(c.id());
                result.setMethodName(m.methodName());
                result.setSourceCode(m.sourceCode());
                result.setParameters(List.of());
                return result;
            }).toList();
            when(methods.findByClassId(c.id())).thenReturn(storedMethods);
        }
        when(classRepository.findByProjectId(248L)).thenReturn(storedClasses);
        when(analysis.getAnalysisResult(248L)).thenReturn(new AnalysisResultDto(
                248L, "piggymetrics", "ANALYZED", classes.size(), changes.size(), 0, 0, 0, 0,
                classes.size(), classes.size(), 0, List.of(), classes, List.of(), List.of()));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(expectedPeak);
        when(agent.generateBusinessRules(eq(248L), any())).thenAnswer(call -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstWave.countDown();
            try {
                assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
                Set<Long> ids = call.getArgument(1);
                var context = contexts.buildBusinessRuleGenerationContext(248L, ids);
                assertThat(ids).hasSizeLessThanOrEqualTo(3);
                return new BusinessRuleResponseDto(context.classes().stream().flatMap(c -> c.methods().stream())
                        .map(m -> new GeneratedBusinessRuleDto(m.id(), "Rule for " + m.methodName(), "BUSINESS_LOGIC")).toList());
            } finally {
                active.decrementAndGet();
            }
        });
        List<SourceUpdateItem> proposals = java.util.Collections.synchronizedList(new ArrayList<>());
        when(items.save(any())).thenAnswer(call -> {
            SourceUpdateItem item = call.getArgument(0);
            proposals.add(item);
            return item;
        });
        when(items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(76L)).thenAnswer(call -> proposals);
        AuthUser actor = new AuthUser();
        actor.setId(42L);
        actor.setTier(tier == null ? UserTier.FREE : tier);
        AuthUserRepository users = mock(AuthUserRepository.class);
        when(users.findById(42L)).thenReturn(Optional.of(actor));
        LlmBatchExecutor executor = tier == null ? null : new LlmBatchExecutor(3, users);
        IncrementalGenerationService service = new IncrementalGenerationService(projects, updates, items,
                classRepository, methods, mock(UnitTestRepository.class), null, null, null,
                mock(SourceUpdateService.class), agent, new SourceUpdateMapper(), mapper, null, executor);
        try {
            assertThat(service.generateIncrementalStage(248L, 76L, GenerationProgressStage.BUSINESS_RULE, actor).status())
                    .isEqualTo(SourceUpdateStatus.READY_TO_APPLY);
            assertThat(peak.get()).isEqualTo(expectedPeak);
            assertThat(GenerationJobContext.actorUserId()).isNull();
        } finally {
            if (executor != null) executor.shutdown();
            GenerationJobContext.clear();
        }
        return proposals;
    }

    private JavaClassDto serviceClass(Long id, String name, List<JavaMethodDto> methods) {
        return new JavaClassDto(id, "demo", name, "demo." + name, "SERVICE",
                "notification-service/src/main/java/demo/" + name + ".java", "class " + name + " {}", List.of(), methods);
    }

    private JavaMethodDto method(Long id, String name) {
        return new JavaMethodDto(id, name, "void", List.of(), List.of(), "PUBLIC",
                "public void " + name + "() { System.out.println(1); }", 1, 1, List.of(), List.of());
    }
}
