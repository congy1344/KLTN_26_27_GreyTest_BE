package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.ExistingTestDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.RelevantAnnotationDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleReviewContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;
import com.greytest.service.analysis.ExistingTestService;

class GenerationContextBuilderTest {
    @Test void nestedUpstreamReadsInsideOneSnapshotDoNotMutateComputeIfAbsent() {
        mockCommonInputs(); stubApprovedUnitTestCase(11L);
        var context=GenerationContextBuilder.withInputSnapshot(()->builder.buildUnitTestContext(1L,Set.of(30L)));
        assertThat(context.approvedTestCases()).hasSize(1);
        assertThat(context.approvedTestPlans()).hasSize(1);
        assertThat(context.approvedBusinessRules()).hasSize(1);
    }
    @Test
    void downstreamContextReadsFreshDraftOverlaysAndRejectsCrossProjectUpdate() throws Exception {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        var updates = mock(com.greytest.repository.SourceUpdateRepository.class);
        var proposals = mock(com.greytest.repository.SourceUpdateItemRepository.class);
        var update = new com.greytest.entity.SourceUpdate(); update.setId(100L); update.setProjectId(1L);
        when(updates.findByIdAndProjectId(100L, 1L)).thenReturn(java.util.Optional.of(update));
        var br = draft(com.greytest.entity.enums.SourceUpdateTargetType.BUSINESS_RULE, 7L,
                "{\"method_id\":11,\"description\":\"Draft business rule\",\"category\":\"VALIDATION\"}");
        var plan = draft(com.greytest.entity.enums.SourceUpdateTargetType.TEST_PLAN, 20L,
                "{\"method_id\":11,\"rule_id\":7,\"covered_rule_ids\":[7],\"title\":\"Draft plan\",\"description\":\"Draft plan detail\",\"test_type\":\"BOUNDARY\"}");
        var testCase = draft(com.greytest.entity.enums.SourceUpdateTargetType.TEST_CASE, 30L,
                "{\"plan_id\":20,\"test_type\":\"BOUNDARY\",\"description\":\"Draft case\",\"preconditions\":\"draft setup\",\"test_data\":{\"input\":{},\"mocks\":{}},\"expected_result\":\"Draft outcome\",\"priority\":\"HIGH\",\"trace_source\":\"BR-001 -> TP-001\"}");
        when(proposals.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(100L)).thenReturn(List.of(br, plan, testCase));
        builder.configureIncrementalInputs(null, updates, proposals, objectMapper);
        try (var scope = com.greytest.service.GenerationJobContext.openScope(42L)) {
            com.greytest.service.GenerationJobContext.setSourceUpdateId(100L);
            var context = builder.buildUnitTestContext(1L, Set.of(30L));
            assertThat(context.approvedBusinessRules().get(0).description()).isEqualTo("Draft business rule");
            assertThat(context.approvedTestPlans().get(0).title()).isEqualTo("Draft plan");
            assertThat(context.approvedTestCases().get(0).expectedResult()).isEqualTo("Draft outcome");
            br.setAfterData(br.getAfterData().replace("Draft business rule", "Edited by user"));
            assertThat(builder.buildTestPlanContext(1L, Set.of(7L)).approvedBusinessRules().get(0).description()).isEqualTo("Edited by user");
            br.setReviewStatus(com.greytest.entity.enums.SourceUpdateReviewStatus.REJECTED);
            assertThat(builder.buildTestPlanContext(1L, Set.of(7L)).approvedBusinessRules().get(0).description()).isNotEqualTo("Edited by user");
            br.setReviewStatus(com.greytest.entity.enums.SourceUpdateReviewStatus.ACCEPTED);
            br.setAction(com.greytest.entity.enums.SourceUpdateAction.KEEP);
            assertThat(builder.buildTestPlanContext(1L, Set.of(7L)).approvedBusinessRules().get(0).description()).isNotEqualTo("Edited by user");
            when(updates.findByIdAndProjectId(100L, 1L)).thenReturn(java.util.Optional.empty());
            assertThatThrownBy(() -> builder.buildTestPlanContext(1L, Set.of(7L))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void pendingInputsAreAvailableOnlyInsideTheirIncrementalDraftScope() {
        mockCommonInputs(); stubApprovedUnitTestCase(11L);
        businessRuleRepository.findByProjectId(1L).get(0).setStatus(ReviewStatus.PENDING_REVIEW);
        testPlanRepository.findByProjectId(1L).get(0).setStatus(ReviewStatus.PENDING_REVIEW);
        testCaseRepository.findByTestPlanId(20L).get(0).setStatus(ReviewStatus.PENDING_REVIEW);
        var updates = mock(com.greytest.repository.SourceUpdateRepository.class);
        var proposals = mock(com.greytest.repository.SourceUpdateItemRepository.class);
        var update = new com.greytest.entity.SourceUpdate(); update.setId(100L); update.setProjectId(1L);
        when(updates.findByIdAndProjectId(100L, 1L)).thenReturn(java.util.Optional.of(update));
        builder.configureIncrementalInputs(null, updates, proposals, objectMapper);

        assertThatThrownBy(() -> builder.buildTestPlanContext(1L, Set.of(7L)))
                .isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class);
        try (var scope = com.greytest.service.GenerationJobContext.openScope(42L)) {
            com.greytest.service.GenerationJobContext.setSourceUpdateId(100L);
            var context = builder.buildUnitTestContext(1L, Set.of(30L));
            assertThat(context.approvedBusinessRules()).extracting("id").containsExactly(7L);
            assertThat(context.approvedTestPlans()).extracting("id").containsExactly(20L);
            assertThat(context.approvedTestCases()).extracting("id").containsExactly(30L);
        }
        assertThatThrownBy(() -> builder.buildTestPlanContext(1L, Set.of(7L)))
                .isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class);
    }

    @Test void inputSnapshotReadsUpstreamOnceAndNestedScopeReadsFresh() {
        mockCommonInputs(); stubApprovedUnitTestCase(11L);
        var first=builder.buildTestPlanContext(1L,Set.of(7L)).approvedBusinessRules().get(0).description();
        GenerationContextBuilder.withInputSnapshot(()-> {
            assertThat(builder.buildTestPlanContext(1L,Set.of(7L)).approvedBusinessRules().get(0).description()).isEqualTo(first);
            var changed=new BusinessRule(); changed.setId(7L); changed.setMethodId(11L); changed.setRuleCode("BR-001");
            changed.setStatus(ReviewStatus.APPROVED); changed.setDescription("fresh upstream revision");
            when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(changed));
            assertThat(builder.buildTestPlanContext(1L,Set.of(7L)).approvedBusinessRules().get(0).description()).isEqualTo(first);
            GenerationContextBuilder.withInputSnapshot(()-> {
                assertThat(builder.buildTestPlanContext(1L,Set.of(7L)).approvedBusinessRules().get(0).description()).isEqualTo("fresh upstream revision");
                return null;
            });
            assertThat(builder.buildTestPlanContext(1L,Set.of(7L)).approvedBusinessRules().get(0).description()).isEqualTo(first);
            return null;
        });
        assertThat(builder.buildTestPlanContext(1L,Set.of(7L)).approvedBusinessRules().get(0).description()).isEqualTo("fresh upstream revision");
    }

    private com.greytest.entity.SourceUpdateItem draft(com.greytest.entity.enums.SourceUpdateTargetType type, long id, String data) {
        var item = new com.greytest.entity.SourceUpdateItem(); item.setSourceUpdateId(100L);
        item.setTargetType(type); item.setTargetId(id); item.setAfterData(data);
        item.setAction(com.greytest.entity.enums.SourceUpdateAction.UPDATE);
        item.setReviewStatus(com.greytest.entity.enums.SourceUpdateReviewStatus.ACCEPTED);
        return item;
    }

    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final AnalysisManifestService manifestService = mock(AnalysisManifestService.class);
    private final ExistingTestService existingTestService = mock(ExistingTestService.class);
    private final BusinessRuleRepository businessRuleRepository = mock(BusinessRuleRepository.class);
    private final TestPlanRepository testPlanRepository = mock(TestPlanRepository.class);
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository = mock(TestPlanCoveredRuleRepository.class);
    private final TestCaseRepository testCaseRepository = mock(TestCaseRepository.class);
    private final UnitTestRepository unitTestRepository = mock(UnitTestRepository.class);
    private final com.greytest.repository.ProjectRepository projectRepository = mock(com.greytest.repository.ProjectRepository.class);
    private final GenerationContextBuilder builder = new GenerationContextBuilder(
            analysisService,
            manifestService,
            existingTestService,
            businessRuleRepository,
            testPlanRepository,
            testPlanCoveredRuleRepository,
            testCaseRepository,
            unitTestRepository,
            projectRepository);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void carriesRelatedResourcesThroughPlanCaseAndUnitContexts(@TempDir Path root) throws Exception {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        writeSnapshotSource(root, "src/main/java/demo/UserService.java",
                "package demo; class UserService { User createUser(String email) { return null; } }");
        writeSnapshotSource(root, "src/main/resources/mapper.xml",
                "<mapper namespace=\"demo.UserService\"><select id=\"find\">select 1</select></mapper>");
        var project = new com.greytest.entity.Project(); project.setStoragePath(root.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));
        for (Object context : List.of(builder.buildTestPlanContext(1L, Set.of(7L)),
                builder.buildTestCaseContext(1L, Set.of(20L)), builder.buildUnitTestContext(1L, Set.of(30L)))) {
            var json = objectMapper.valueToTree(context);
            assertThat(json.has("supportingResources")).isTrue();
            assertThat(json.get("supportingResources").toString()).contains("mapper.xml", "select 1");
        }
    }

    @Test
    void candidateContextsReadCandidateResourcesAndKeepMissingEvidenceWarnings(@TempDir Path root) throws Exception {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        Path active = root.resolve("active");
        Path candidate = root.resolve("candidate");
        String javaSource = "package demo; class UserService { User createUser(String email) { return null; } }";
        writeSnapshotSource(active, "src/main/java/demo/UserService.java", javaSource);
        writeSnapshotSource(candidate, "src/main/java/demo/UserService.java", javaSource);
        writeSnapshotSource(active, "src/main/resources/mapper.xml",
                "<mapper namespace=\"demo.UserService\"><select id=\"find\">active only</select></mapper>");
        writeSnapshotSource(candidate, "src/main/resources/mapper.xml",
                "<mapper namespace=\"demo.UserService\"><select id=\"find\">candidate only<include refid=\"missing\"/></select></mapper>");
        var project = new com.greytest.entity.Project();
        project.setStoragePath(active.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));

        for (Object context : List.of(builder.buildSnapshotTestPlanContext(1L, Set.of(7L), candidate),
                builder.buildSnapshotTestCaseContext(1L, Set.of(20L), candidate),
                builder.buildSnapshotUnitTestContext(1L, Set.of(30L), candidate))) {
            var json = objectMapper.valueToTree(context);
            assertThat(json.path("supportingResources").toString()).contains("candidate only").doesNotContain("active only");
            assertThat(json.path("contextWarnings").toString()).contains("missing");
        }
    }

    @Test
    void reusesStoredAnalysisOnlyWithinOneGenerationJob() {
        mockCommonInputs();
        com.greytest.service.GenerationJobContext.bind(42L, ignored -> { });
        try {
            builder.buildBusinessRuleGenerationContext(1L, Set.of(11L));
            builder.buildBusinessRuleGenerationContext(1L, Set.of(11L));
            org.mockito.Mockito.verify(analysisService).getAnalysisResult(1L);
        } finally {
            com.greytest.service.GenerationJobContext.clear();
        }
        builder.buildBusinessRuleGenerationContext(1L, Set.of(11L));
        org.mockito.Mockito.verify(analysisService, org.mockito.Mockito.times(2)).getAnalysisResult(1L);
    }

    @Test
    void sourceCacheSeparatesSnapshotsAndRefreshesNextJob(@TempDir Path root) throws Exception {
        mockCommonInputs();
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        String relative = "src/main/java/demo/UserService.java";
        String original = "package demo; class UserService { User createUser(String email) { throw new IllegalArgumentException(\"original\"); } }";
        String changed = original.replace("original", "changed");
        writeSnapshotSource(first, relative, original);
        writeSnapshotSource(second, relative, changed);
        com.greytest.service.GenerationJobContext.bind(42L, ignored -> { });
        try {
            assertThat(builder.buildSnapshotTestPlanContext(1L, Set.of(7L), first).classes())
                    .anySatisfy(c -> assertThat(c.sourceCode()).contains("original"));
            writeSnapshotSource(first, relative, changed);
            assertThat(builder.buildSnapshotTestPlanContext(1L, Set.of(7L), first).classes())
                    .anySatisfy(c -> assertThat(c.sourceCode()).contains("changed"));
            assertThat(builder.buildSnapshotTestPlanContext(1L, Set.of(7L), second).classes())
                    .anySatisfy(c -> assertThat(c.sourceCode()).contains("changed"));
            var updatedRule = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
            updatedRule.setDescription("Updated rule within job");
            when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(updatedRule));
            assertThat(builder.buildSnapshotTestPlanContext(1L, Set.of(7L), first).approvedBusinessRules())
                    .extracting("description").containsExactly("Updated rule within job");
        } finally {
            com.greytest.service.GenerationJobContext.clear();
        }
        assertThat(builder.buildSnapshotTestPlanContext(1L, Set.of(7L), first).classes())
                .anySatisfy(c -> assertThat(c.sourceCode()).contains("changed"));
    }

    @Test
    void cachedActiveAnalysisCannotBypassStrictSnapshotValidation(@TempDir Path root) throws Exception {
        mockCommonInputs();
        writeSnapshotSource(root, "src/main/java/demo/UserService.java",
                "package demo; class UserService { User createUser(String email) { return null; } }");
        writeSnapshotSource(root, "src/main/java/demo/Broken.java", "class Broken { invalid java");
        var project = new com.greytest.entity.Project();
        project.setStoragePath(root.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));
        com.greytest.service.GenerationJobContext.bind(42L, ignored -> { });
        try {
            builder.buildTestPlanContext(1L, Set.of(7L));
            assertThatThrownBy(() -> builder.buildSnapshotTestPlanContext(1L, Set.of(7L), root))
                    .isInstanceOf(LlmResponseException.class);
        } finally {
            com.greytest.service.GenerationJobContext.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-package", "wildcard"})
    void businessRuleDependenciesAndResourcesPreferTheTargetModule(String layout, @TempDir Path root) throws Exception {
        mockCommonInputs();
        var project = new com.greytest.entity.Project(); project.setStoragePath(root.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));
        var base = analysis();
        var old = base.classes().get(1);
        String repoPackage = layout.equals("wildcard") ? "demo.model" : "demo";
        String repoType = repoPackage + ".Repo";
        String repoPath = repoPackage.replace('.', '/') + "/Repo.java";
        var service = new JavaClassDto(10L, "demo", "UserService", "demo.UserService", "SERVICE",
                "module-b/src/main/java/demo/UserService.java", "package demo; import " + repoPackage
                        + ".*; class UserService { Repo repo; }", List.of(), old.methods());
        var wrong = new JavaClassDto(80L, repoPackage, "Repo", repoType, "OTHER", "module-a/src/main/java/" + repoPath,
                "package " + repoPackage + "; class Repo { @org.springframework.beans.factory.annotation.Value(\"${limit}\") int limit; String wrong; }", List.of(), List.of());
        var correct = new JavaClassDto(81L, repoPackage, "Repo", repoType, "OTHER", "module-b/src/main/java/" + repoPath,
                "package " + repoPackage + "; class Repo { @org.springframework.beans.factory.annotation.Value(\"${limit}\") int limit; String correct; }", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(base.projectId(), base.projectName(), base.status(),
                3, base.totalMethods(), 0, 0, 0, 0, 3, 3, 0, List.of(), List.of(service, wrong, correct), List.of(), List.of()));
        for (String module : List.of("module-a", "module-b")) Files.createDirectories(root.resolve(module + "/src/main/resources"));
        Files.writeString(root.resolve("module-a/src/main/resources/application.properties"), "limit=111\n");
        Files.writeString(root.resolve("module-b/src/main/resources/application.properties"), "limit=222\n");

        var context = builder.buildBusinessRuleGenerationContext(1L, Set.of(11L));

        assertThat(context.classes()).filteredOn(type -> type.qualifiedName().equals(repoType))
                .singleElement().satisfies(type -> assertThat(type.sourceCode()).contains("correct").doesNotContain("wrong"));
        assertThat(objectMapper.valueToTree(context).path("supportingResources").toString()).contains("222").doesNotContain("111");
    }

    @ParameterizedTest
    @ValueSource(strings = {"module-b", "common"})
    void businessRulesResolveImplementationImportsAcrossFolders(String contractModule) {
        mockCommonInputs();
        var base = analysisWithServiceSource("package demo; import demo.contract.Lookup; class UserService { Lookup lookup; }");
        var previous = base.classes().get(1);
        var service = new JavaClassDto(previous.id(), previous.packageName(), previous.className(), previous.qualifiedName(),
                previous.classType(), "module-b/src/main/java/demo/UserService.java", previous.sourceCode(), previous.annotations(), previous.methods());
        var contract = new JavaClassDto(80L, "demo.contract", "Lookup", "demo.contract.Lookup", "OTHER",
                contractModule + "/src/main/java/demo/contract/Lookup.java", "package demo.contract; interface Lookup {}", List.of(), List.of());
        var implementation = new JavaClassDto(81L, "demo.persistence", "JdbcLookup", "demo.persistence.JdbcLookup", "OTHER",
                "module-b/src/main/java/demo/persistence/JdbcLookup.java", "package demo.persistence; import demo.contract.*; class JdbcLookup implements Lookup {}", List.of(), List.of());
        var other = new JavaClassDto(82L, "demo.persistence", "JdbcLookup", "demo.persistence.JdbcLookup", "OTHER",
                "module-a/src/main/java/demo/persistence/JdbcLookup.java", implementation.sourceCode(), List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(base.projectId(), base.projectName(), base.status(),
                4, base.totalMethods(), 0, 0, 0, 0, 4, 4, 0, List.of(), List.of(service, contract, other, implementation), List.of(), List.of()));

        assertThat(builder.buildBusinessRuleGenerationContext(1L, Set.of(11L)).classes())
                .filteredOn(type -> type.qualifiedName().equals("demo.persistence.JdbcLookup"))
                .singleElement().satisfies(type -> assertThat(type.filePath()).startsWith("module-b/"));
    }

    @Test
    void businessRulesReceiveRelatedImplementationsResourcesAndExistingTests(@TempDir Path root) throws Exception {
        mockCommonInputs();
        var project = new com.greytest.entity.Project();
        project.setStoragePath(root.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));
        var base = analysis();
        var method = new JavaMethodDto(11L, "createUser", "void", List.of(), List.of(), "PUBLIC",
                "void createUser() { lookup.find(); }", 1, 1, List.of(), List.of());
        var service = new JavaClassDto(10L, "demo", "UserService", "demo.UserService", "SERVICE",
                "src/main/java/demo/UserService.java",
                "package demo; import demo.repo.Lookup; class UserService { Lookup lookup; "
                        + "@org.springframework.beans.factory.annotation.Value(\"${article.limit:10}\") int limit; "
                        + method.sourceCode() + " }", List.of(), List.of(method));
        var repository = new JavaClassDto(81L, "demo.repo", "Lookup", "demo.repo.Lookup", "OTHER",
                "src/main/java/demo/repo/Lookup.java", "package demo.repo; interface Lookup { void find(); }", List.of(), List.of());
        var implementation = new JavaClassDto(82L, "demo.repo", "JdbcLookup", "demo.repo.JdbcLookup", "OTHER",
                "src/main/java/demo/repo/JdbcLookup.java",
                "package demo.repo; class JdbcLookup implements Lookup { ArticleMapper mapper; public void find() { mapper.find(); } }",
                List.of(), List.of());
        var mapper = new JavaClassDto(83L, "demo.repo", "ArticleMapper", "demo.repo.ArticleMapper", "OTHER",
                "src/main/java/demo/repo/ArticleMapper.java", "package demo.repo; interface ArticleMapper { void find(); }", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 4, 1, 0, 0, 0, 1, 4, 4, 0,
                List.of(), List.of(service, repository, implementation, mapper), List.of(), List.of()));
        Path resources = root.resolve("src/main/resources");
        Files.createDirectories(resources.resolve("mapper"));
        Files.writeString(resources.resolve("mapper/ArticleMapper.xml"),
                "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                        + "<mapper namespace=\"demo.repo.ArticleMapper\"><select id=\"find\">select coalesce(count(*), 0) from articles</select></mapper>");
        Files.writeString(resources.resolve("mapper/Unrelated.xml"), "<mapper namespace=\"other.Mapper\"><select id=\"find\">unrelated-query</select></mapper>");
        Files.writeString(resources.resolve("application.yml"), "article:\n  limit: 12\nspring:\n  datasource:\n    password: do-not-send\nunrelated: unrelated-value\n");
        Files.writeString(resources.resolve("application-test.properties"), "article.limit=20\napi.secret=do-not-send\n");
        var unrelated = new ExistingTestDto(90L, 1L, "src/test/java/other/OtherTest.java", "other", "OtherTest",
                99L, null, List.of(), List.of(), "class OtherTest {}", null);
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest(), unrelated));

        var context = builder.buildBusinessRuleGenerationContext(1L, Set.of(11L));
        var json = objectMapper.valueToTree(context);

        assertThat(context.classes()).extracting("qualifiedName")
                .contains("demo.repo.Lookup", "demo.repo.JdbcLookup", "demo.repo.ArticleMapper");
        assertThat(context.classes().stream().flatMap(type -> type.methods().stream()).map(item -> item.id()))
                .containsExactly(11L);
        assertThat(context.existingTests()).extracting("testClassName").containsExactly("UserServiceTest");
        assertThat(json.path("supportingResources").toString()).contains("coalesce(count(*)", "article.limit", "12", "20")
                .doesNotContain("do-not-send", "unrelated-query", "unrelated-value");
        assertThat(json.path("supportingResources").toString()).contains("mapper/ArticleMapper.xml", "application.yml", "application-test.properties");
    }

    @Test
    void businessRuleResourcesStayInTheSelectedModuleAndDoNotExpandEntities(@TempDir Path root) throws Exception {
        mockCommonInputs();
        var project = new com.greytest.entity.Project(); project.setStoragePath(root.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));
        var base = analysisWithServiceSource("package demo; class UserService { @org.springframework.beans.factory.annotation.Value(\"${limit:5}\") int limit; }");
        var previous = base.classes().get(1);
        var service = new JavaClassDto(previous.id(), previous.packageName(), previous.className(), previous.qualifiedName(),
                previous.classType(), "orders/src/main/java/demo/UserService.java", previous.sourceCode(), previous.annotations(), previous.methods());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 1, 2, 0, 0, 0, 0, 1, 1, 0,
                List.of(), List.of(service), List.of(), List.of()));
        Path resources = root.resolve("orders/src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.properties"), "limit=7\n");
        Path other = root.resolve("payments/src/main/resources"); Files.createDirectories(other);
        Files.writeString(other.resolve("application.properties"), "limit=9999\n");
        Files.writeString(resources.resolve("evil.xml"), "<!DOCTYPE mapper [<!ENTITY leak SYSTEM \"file:///not-allowed\">]>"
                + "<mapper namespace=\"demo.UserService\"><select id=\"find\">&leak;</select></mapper>");

        var json = objectMapper.valueToTree(builder.buildBusinessRuleGenerationContext(1L, Set.of(11L)));

        assertThat(json.path("supportingResources").toString()).contains("limit", "7").doesNotContain("9999", "file:///not-allowed");
        assertThat(json.path("contextWarnings").toString()).contains("evil.xml");
    }

    @ParameterizedTest
    @CsvSource({"int, get", "Integer, get", "long, getOrDefault", "boolean, get"})
    void everyGenerationStageReceivesTheSameDeclaredTypesAndReachedHelpers(String fieldType, String lookup) {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        BusinessRule rule = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        rule.setIsModified(true);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(rule));
        String helper = "private void enrich(Payload item) { item.setCount(counts." + lookup
                + "(item.getId()" + (lookup.equals("getOrDefault") ? ", 0L" : "") + ")); }";
        String serviceSource = "package demo; import demo.model.Payload; import java.util.Map; "
                + "class UserService { Map<String, " + (fieldType.equals("boolean") ? "Boolean" : fieldType.equals("long") ? "Long" : "Integer")
                + "> counts; void createUser(Payload item) { enrich(item); } " + helper + " }";
        AnalysisResultDto base = analysisWithServiceSource(serviceSource);
        JavaClassDto payload = new JavaClassDto(80L, "demo.model", "Payload", "demo.model.Payload", "OTHER",
                "src/main/java/demo/model/Payload.java",
                "package demo.model; @lombok.Data class Payload extends BasePayload { private " + fieldType + " count; }",
                List.of(), List.of());
        JavaClassDto parent = new JavaClassDto(81L, "demo.model", "BasePayload", "demo.model.BasePayload", "OTHER",
                "src/main/java/demo/model/BasePayload.java",
                "package demo.model; class BasePayload { String id; public String getId() { return id; } }",
                List.of(), List.of());
        JavaClassDto unrelated = new JavaClassDto(82L, "other", "Payload", "other.Payload", "OTHER",
                "src/main/java/other/Payload.java", "package other; class Payload {}", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 5, base.totalMethods(), 0, 0, 0, 0, 5, 5, 0,
                List.of(), List.of(base.classes().get(0), base.classes().get(1), payload, parent, unrelated), List.of(), List.of()));

        var stages = List.of(
                builder.buildBusinessRuleGenerationContext(1L, Set.of(11L)).classes(),
                builder.buildBusinessRuleReviewContext(1L, Set.of(7L)).classes(),
                builder.buildTestPlanContext(1L, Set.of(7L)).classes(),
                builder.buildTestCaseContext(1L, Set.of(20L)).classes(),
                builder.buildUnitTestContext(1L, Set.of(30L)).classes());

        for (var classes : stages) {
            assertThat(classes).extracting("qualifiedName")
                    .containsExactly("demo.UserService", "demo.model.Payload", "demo.model.BasePayload");
            assertThat(classes.get(0).sourceCode()).contains(helper);
            assertThat(classes.get(1).sourceCode()).contains("private " + fieldType + " count;");
            assertThat(classes.stream().flatMap(type -> type.methods().stream()).map(method -> method.id()))
                    .containsExactly(11L);
        }
    }

    @Test
    void buildsDeterministicBusinessRuleGenerationContext() throws Exception {
        mockCommonInputs();

        BusinessRuleGenerationContextDto context = builder.buildBusinessRuleGenerationContext(1L);
        String first = objectMapper.writeValueAsString(context);
        String second = objectMapper.writeValueAsString(builder.buildBusinessRuleGenerationContext(1L));

        assertThat(second).isEqualTo(first);
        assertThat(first.length()).isLessThan(10_000);
        assertThat(context.analysis().totalClasses()).isEqualTo(2);
        assertThat(context.existingTests()).extracting("testClassName").containsExactly("UserServiceTest");
        assertThat(context.serviceRepositoryRelations()).extracting("serviceQualifiedName")
                .containsExactly("demo.UserService");
        assertThat(context.controllerServiceRelations()).isEmpty();
        assertThat(context.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.collaboratorName()).isEqualTo("repository");
            assertThat(call.collaboratorType()).isEqualTo("UserRepository");
            assertThat(call.calleeMethodName()).isEqualTo("save");
        });
        assertThat(context.classes()).extracting("qualifiedName").containsExactly("demo.UserService");
        assertThat(context.classes().get(0).methods()).extracting("methodName").containsExactly("updateUser");
    }

    @Test
    void sendsUpToThreeBusinessRuleMethodsPerRequest() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceMethods(21));
        when(manifestService.exportManifest(1L)).thenReturn(manifest());
        when(existingTestService.list(1L)).thenReturn(List.of());
        List<BusinessRule> coveredRules = LongStream.rangeClosed(1, 5)
                .mapToObj(id -> ruleEntity(id, id, ReviewStatus.APPROVED))
                .toList();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(), coveredRules);

        BusinessRuleGenerationContextDto first = builder.buildBusinessRuleGenerationContext(1L);
        BusinessRuleGenerationContextDto second = builder.buildBusinessRuleGenerationContext(1L);

        assertThat(first.classes().get(0).methods()).extracting("id")
                .containsExactly(1L, 2L, 3L);
        assertThat(second.classes().get(0).methods()).extracting("id")
                .containsExactly(6L, 7L, 8L);
    }
    @Test
    void resolvesClientCallAndEndpointForSelectedMethod() {
        mockCommonInputs();
        AnalysisResultDto base = analysis();
        JavaMethodDto updateMethod = new JavaMethodDto(
                12L, "updateUser", "User", List.of(), List.of(), "PUBLIC",
                "void updateUser() { this.statisticsClient.updateStatistics(); }",
                14, 16, List.of(), List.of());
        JavaClassDto service = new JavaClassDto(
                10L, "demo", "UserService", "demo.UserService", "SERVICE",
                "src/main/java/demo/UserService.java",
                "class UserService { private StatisticsServiceClient statisticsClient; }",
                List.of(), List.of(updateMethod));
        JavaMethodDto clientMethod = new JavaMethodDto(
                80L, "updateStatistics", "void", List.of(), List.of(), "PUBLIC",
                "void updateStatistics() {}",
                4, 5, List.of(),
                List.of(new EndpointDto(81L, "PUT", "/statistics/{accountName}", null, null, "updateStatistics")));
        JavaClassDto client = new JavaClassDto(
                80L, "demo.client", "StatisticsServiceClient",
                "demo.client.StatisticsServiceClient", "OTHER",
                "src/main/java/demo/client/StatisticsServiceClient.java",
                "interface StatisticsServiceClient { void updateStatistics(); }",
                List.of(), List.of(clientMethod));
        AnalysisResultDto enriched = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 1, base.totalMethods() + 1, base.totalEndpoints() + 1,
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles(), base.parsedProductionFiles(),
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), service, client),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(enriched);

        BusinessRuleGenerationContextDto context = builder.buildBusinessRuleGenerationContext(1L);

        assertThat(context.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.calleeQualifiedName()).isEqualTo("demo.client.StatisticsServiceClient");
            assertThat(call.calleeMethodName()).isEqualTo("updateStatistics");
            assertThat(call.httpMethod()).isEqualTo("PUT");
            assertThat(call.endpointPath()).isEqualTo("/statistics/{accountName}");
            assertThat(call.calleeServiceSourceCode()).isNull();
        });
    }

    @Test
    void includesResolvedServiceSourceForCrossServiceInvariant() {
        AnalysisResultDto base = analysis();
        JavaMethodDto orderMethod = new JavaMethodDto(
                90L, "placeOrder", "void", List.of(), List.of(), "PUBLIC",
                "void placeOrder() { try { paymentService.charge(); } catch (PaymentFailedException exception) { rollbackInventory(); throw exception; } }",
                10, 12, List.of(), List.of());
        JavaClassDto orderService = new JavaClassDto(
                90L, "demo.order", "OrderService", "demo.order.OrderService", "SERVICE",
                "order-service/src/main/java/demo/order/OrderService.java",
                "class OrderService { private PaymentService paymentService; }",
                List.of(), List.of(orderMethod));
        JavaMethodDto paymentMethod = new JavaMethodDto(
                91L, "charge", "void", List.of(), List.of("PaymentFailedException"), "PUBLIC",
                "void charge() { if (gateway.isRejected()) throw new PaymentFailedException(); }",
                8, 10, List.of(), List.of());
        JavaClassDto paymentService = new JavaClassDto(
                91L, "demo.payment", "PaymentService", "demo.payment.PaymentService", "SERVICE",
                "payment-service/src/main/java/demo/payment/PaymentService.java",
                "class PaymentService { void charge() {} }",
                List.of(), List.of(paymentMethod));
        AnalysisResultDto enriched = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 2, base.totalMethods() + 2, base.totalEndpoints(),
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles() + 2, base.parsedProductionFiles() + 2,
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), orderService, paymentService),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(enriched);

        BusinessRuleGenerationContextDto context =
                builder.buildBusinessRuleGenerationContext(1L, Set.of(90L));

        assertThat(context.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.calleeQualifiedName()).isEqualTo("demo.payment.PaymentService");
            assertThat(call.calleeMethodName()).isEqualTo("charge");
            assertThat(call.calleeServiceSourceCode()).contains("gateway.isRejected()")
                    .contains("PaymentFailedException");
        });

        JavaMethodDto longPaymentMethod = new JavaMethodDto(
                92L, "charge", "void", List.of(), List.of(), "PUBLIC",
                "void charge() { String payload = \"" + "x".repeat(4_100) + "\"; }",
                8, 10, List.of(), List.of());
        JavaClassDto longPaymentService = new JavaClassDto(
                92L, "demo.payment", "PaymentService", "demo.payment.PaymentService", "SERVICE",
                "payment-service/src/main/java/demo/payment/PaymentService.java",
                "class PaymentService { void charge() {} }",
                List.of(), List.of(longPaymentMethod));
        AnalysisResultDto withLongCalleeSource = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 2, base.totalMethods() + 2, base.totalEndpoints(),
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles() + 2, base.parsedProductionFiles() + 2,
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), orderService, longPaymentService),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(withLongCalleeSource);

        BusinessRuleGenerationContextDto longSourceContext =
                builder.buildBusinessRuleGenerationContext(1L, Set.of(90L));

        assertThat(longSourceContext.dependencyCalls()).singleElement()
                .satisfies(call -> assertThat(call.calleeServiceSourceCode()).isNull());

        JavaClassDto duplicatePaymentService = new JavaClassDto(
                93L, "demo.legacy", "PaymentService", "demo.legacy.PaymentService", "SERVICE",
                "legacy-service/src/main/java/demo/legacy/PaymentService.java",
                "class PaymentService { void charge() {} }",
                List.of(), List.of(paymentMethod));
        AnalysisResultDto withAmbiguousCallee = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 3, base.totalMethods() + 3, base.totalEndpoints(),
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles() + 3, base.parsedProductionFiles() + 3,
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), orderService, paymentService, duplicatePaymentService),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(withAmbiguousCallee);

        BusinessRuleGenerationContextDto ambiguousContext =
                builder.buildBusinessRuleGenerationContext(1L, Set.of(90L));

        assertThat(ambiguousContext.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.calleeQualifiedName()).isNull();
            assertThat(call.calleeServiceSourceCode()).isNull();
        });
    }

    @Test
    void reviewContextContainsOnlyDirtyRulesAndSameMethodReferences() {
        mockCommonInputs();
        BusinessRule dirty = ruleEntity(7L, 11L, ReviewStatus.PENDING_REVIEW);
        dirty.setIsModified(true);
        BusinessRule related = ruleEntity(8L, 11L, ReviewStatus.PENDING_REVIEW);
        BusinessRule unrelated = ruleEntity(9L, 12L, ReviewStatus.PENDING_REVIEW);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(dirty, related, unrelated));

        BusinessRuleReviewContextDto context = builder.buildBusinessRuleReviewContext(1L);

        assertThat(context.businessRules()).extracting("id").containsExactly(7L);
        assertThat(context.relatedBusinessRules()).extracting("id").containsExactly(8L);
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
    }

    @Test
    void planAndCaseContextsUseFullActiveSourceWithoutChangingIds(@TempDir Path snapshot) throws Exception {
        mockCommonInputs();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(testPlan(20L, 7L, "TP-001")));
        var project = new com.greytest.entity.Project(); project.setStoragePath(snapshot.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));
        Path source = snapshot.resolve("src/main/java/demo/UserService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; import demo.crypto.Hasher; class UserService { "
                + "private static final Hasher encoder = new Hasher(); private UserRepository repository; "
                + "User createUser(String email) { return null; } User updateUser() { return null; } }");

        var plan = builder.buildTestPlanContext(1L, Set.of(7L));
        var cases = builder.buildTestCaseContext(1L, Set.of(20L));

        for (var classes : List.of(plan.classes(), cases.classes())) {
            assertThat(classes).singleElement().satisfies(service -> {
                assertThat(service.id()).isEqualTo(10L);
                assertThat(service.methods()).extracting("id").containsExactly(11L);
                assertThat(service.sourceCode()).contains("import demo.crypto.Hasher", "static final Hasher encoder");
            });
        }
        assertThat(analysisService.getAnalysisResult(1L).classes().stream()
                .filter(sourceClass -> sourceClass.qualifiedName().equals("demo.UserService")).findFirst().orElseThrow().sourceCode())
                .doesNotContain("Hasher");
    }

    @Test
    void stagedAddedMethodUsesCandidateForPlanAndCaseBeforeApply(@TempDir Path workspace) throws Exception {
        mockCommonInputs();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(testPlan(20L, 7L, "TP-001")));
        Path active = workspace.resolve("active"), candidate = workspace.resolve("candidate");
        for (Path root : List.of(active, candidate)) Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(active.resolve("src/main/java/demo/UserService.java"), "package demo; class UserService {} ");
        Files.writeString(candidate.resolve("src/main/java/demo/UserService.java"),
                "package demo; class UserService { private final Hasher encoder = new Hasher(); User createUser(String email) { return null; } }");
        var project = new com.greytest.entity.Project(); project.setStoragePath(active.toString());
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project));

        var plan = builder.buildSnapshotTestPlanContext(1L, Set.of(7L), candidate);
        var cases = builder.buildSnapshotTestCaseContext(1L, Set.of(20L), candidate);
        for (var classes : List.of(plan.classes(), cases.classes())) {
            assertThat(classes).singleElement().satisfies(service -> {
                assertThat(service.sourceCode()).contains("final Hasher encoder", "createUser");
                assertThat(service.methods()).extracting("id").containsExactly(11L);
            });
        }
    }

    @Test
    void testPlanContextUsesOnlyApprovedRuleMethods() {
        mockCommonInputs();
        BusinessRule approved = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        approved.setReviewNote("SOURCE_BRANCH:IF-1-FALSE\nLegacy data.");
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(approved));

        TestPlanContextDto context = builder.buildTestPlanContext(1L);

        assertThat(context.approvedBusinessRules()).extracting("ruleCode").containsExactly("BR-001");
        assertThat(context.approvedBusinessRules().get(0).sourceBranchId()).isEqualTo("IF-1");
        assertThat(context.classes()).hasSize(1);
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
    }

    @Test
    void testPlanContextUsesOnlyRequestedRules() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceMethods(7));
        when(manifestService.exportManifest(1L)).thenReturn(manifest());
        when(existingTestService.list(1L)).thenReturn(List.of());
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(LongStream.rangeClosed(1, 7)
                        .mapToObj(id -> ruleEntity(id, id, ReviewStatus.APPROVED))
                        .toList());

        TestPlanContextDto context = builder.buildTestPlanContext(1L, Set.of(1L, 2L, 3L, 4L, 5L, 6L));

        assertThat(context.approvedBusinessRules()).extracting("id")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(context.classes().get(0).methods()).extracting("id")
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void testCaseContextUsesOnlyRequestedPlansAndRelevantExistingTests() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysis());
        BusinessRule firstRule = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        BusinessRule secondRule = ruleEntity(8L, 12L, ReviewStatus.APPROVED);
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(firstRule, secondRule));
        TestPlan firstPlan = testPlan(20L, 7L, "TP-001");
        TestPlan secondPlan = testPlan(21L, 8L, "TP-002");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(firstPlan, secondPlan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L, 21L)))
                .thenReturn(List.of(coveredRule(20L, 7L), coveredRule(21L, 8L)));
        ExistingTestDto unrelatedTest = new ExistingTestDto(
                51L, 1L, "src/test/java/demo/UserControllerTest.java", "demo", "UserControllerTest",
                30L, null, List.of(), List.of(), null, null);
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest(), unrelatedTest));

        TestCaseContextDto context = builder.buildTestCaseContext(1L, Set.of(20L));

        assertThat(context.approvedTestPlans()).extracting("id").containsExactly(20L);
        assertThat(context.approvedBusinessRules()).extracting("id").containsExactly(7L);
        assertThat(context.classes()).extracting("className").containsExactly("UserService");
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
        assertThat(context.classes().get(0).sourceCode()).contains("private UserRepository repository");
        assertThat(context.existingTests()).extracting("testClassName").containsExactly("UserServiceTest");
    }

    @Test
    void unitTestContextIncludesApprovedArtifactsAndExistingTestSource() {
        mockCommonInputs();
        ExistingTestDto unrelatedTest = new ExistingTestDto(
                51L, 1L, "src/test/java/demo/UserControllerTest.java", "demo", "UserControllerTest",
                30L, null, List.of(), List.of(), "class UserControllerTest {}", null);
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest(), unrelatedTest));
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED), ruleEntity(8L, 11L, ReviewStatus.APPROVED)));
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setPlanCode("TP-001");
        plan.setTitle("Happy path");
        plan.setDescription("Input hop le");
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.APPROVED);
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setPriority(Priority.HIGH);
        testCase.setStatus(ReviewStatus.APPROVED);
        TestCase previousCase = new TestCase();
        previousCase.setId(29L);
        previousCase.setTestPlanId(20L);
        previousCase.setCaseCode("TC-000");
        previousCase.setPriority(Priority.HIGH);
        previousCase.setStatus(ReviewStatus.APPROVED);
        UnitTest previousUnit = new UnitTest();
        previousUnit.setTestCaseId(29L);
        previousUnit.setTestClassName("UserServiceTest");
        previousUnit.setTestMethodName("createUser_existing");
        previousUnit.setPackageName("demo");
        previousUnit.setFilePath("src/test/java/demo/UserServiceTest.java");
        previousUnit.setSourceCode("class UserServiceTest { void createUser_existing() {} }");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L), coveredRule(20L, 8L)));
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(previousCase, testCase));
        when(unitTestRepository.findByTestCaseId(29L)).thenReturn(previousUnit);

        var context = builder.buildUnitTestContext(1L);

        assertThat(context.approvedTestPlans()).extracting("planCode").containsExactly("TP-001");
        assertThat(context.approvedTestPlans().get(0).coveredRuleIds()).containsExactly(7L, 8L);
        assertThat(context.approvedTestCases()).extracting("caseCode").containsExactly("TC-000", "TC-001");
        assertThat(context.existingTests()).extracting("sourceCode").containsExactly("class UserServiceTest {}");
        assertThat(context.classes().get(0).sourceCode()).contains("private UserRepository repository");

        var refinement = builder.buildUnitTestContext(1L, Set.of(30L));
        assertThat(refinement.approvedTestCases()).extracting("caseCode").containsExactly("TC-001");
        assertThat(refinement.existingApprovedTestCases()).extracting("caseCode").containsExactly("TC-000");
        assertThat(refinement.previousGeneratedUnitTests()).extracting("testMethodName")
                .containsExactly("createUser_existing");
    }

    @Test
    void unitTestBatchContextStaysBoundedForOneLargePlan() {
        mockCommonInputs();
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        TestPlan plan = testPlan(20L, 7L, "TP-001");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        List<TestCase> testCases = LongStream.rangeClosed(1, 31).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setCaseCode("TC-" + id);
            testCase.setPriority(Priority.HIGH);
            testCase.setStatus(ReviewStatus.APPROVED);
            return testCase;
        }).toList();
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(testCases);
        ExistingTestDto largeExistingTest = new ExistingTestDto(
                50L, 1L, "src/test/java/demo/UserServiceTest.java", "demo", "UserServiceTest",
                10L, null, List.of(), List.of(), "x".repeat(10_000), null);
        when(existingTestService.list(1L)).thenReturn(List.of(largeExistingTest));

        var context = builder.buildUnitTestContext(1L, Set.of(31L));

        assertThat(context.approvedTestCases()).extracting("id").containsExactly(31L);
        assertThat(context.existingApprovedTestCases()).hasSize(10);
        assertThat(context.existingTests()).singleElement()
                .extracting("sourceCode").asString().hasSizeLessThan(5_000);
    }

    @Test
    void unitTestContextKeepsCompleteTargetSourcePastFourThousandCharacters() throws Exception {
        mockCommonInputs();
        String source = "package demo; class UserService { /*" + "x".repeat(4_100)
                + "*/ public User createUser(String email) { return null; } }";
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceSource(source));
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        TestPlan plan = testPlan(20L, 7L, "TP-001");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setPriority(Priority.HIGH);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));

        var context = builder.buildUnitTestContext(1L, Set.of(30L));

        assertThat(context.classes().get(0).sourceCode()).isEqualTo(source);
        assertThat(context.classes().get(0).validationSourceCode()).isEqualTo(source);
        assertThat(objectMapper.writeValueAsString(context)).contains("x".repeat(4_100));
        assertThat(GeneratedUnitTestSemanticValidator.validateContext(context)).isEmpty();
        String generated = "package demo; class UserServiceTest { UserService userService; "
                + "@org.junit.jupiter.api.Test void testCase() { userService.complete(1L); } }";
        var response = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                30L, "UserServiceTest", "testCase", "demo", "NEW_TEST", generated)));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response))
                .hasValueSatisfying(message -> assertThat(message).contains("complete", "does not exist"));
    }

    @Test
    void unitTestContextIncludesTransitiveGenericTypesWithoutUnrelatedClasses() {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        AnalysisResultDto base = analysisWithServiceSource(
                "class UserService { demo.domain.Recipient createUser(String email) { return null; } }");
        JavaClassDto recipient = new JavaClassDto(81L, "demo.domain", "Recipient", "demo.domain.Recipient",
                "ENTITY", "src/main/java/demo/domain/Recipient.java",
                "class Recipient { java.util.Map<String, NotificationSettings> settings; }", List.of(), List.of());
        JavaClassDto settings = new JavaClassDto(82L, "demo.domain", "NotificationSettings",
                "demo.domain.NotificationSettings", "OTHER", "src/main/java/demo/domain/NotificationSettings.java",
                "class NotificationSettings { Recipient owner; ScheduleMode mode; }", List.of(), List.of());
        JavaClassDto mode = new JavaClassDto(83L, "demo.domain", "ScheduleMode", "demo.domain.ScheduleMode",
                "ENUM", "src/main/java/demo/domain/ScheduleMode.java", "enum ScheduleMode { DAILY }",
                List.of(), List.of());
        JavaClassDto unrelated = new JavaClassDto(84L, "demo", "UnrelatedService", "demo.UnrelatedService",
                "SERVICE", "src/main/java/demo/UnrelatedService.java", "class UnrelatedService {}",
                List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 5, base.totalMethods(), 0, 0, 0, 0, 5, 5, 0,
                List.of(), List.of(base.classes().get(1), recipient, settings, mode, unrelated), List.of(), List.of()));

        var context = builder.buildUnitTestContext(1L, Set.of(30L));

        assertThat(context.classes()).extracting("qualifiedName").containsExactly(
                "demo.UserService", "demo.domain.Recipient", "demo.domain.NotificationSettings", "demo.domain.ScheduleMode");
        assertThat(context.classes().get(2).sourceCode()).contains("ScheduleMode mode");
    }

    @Test
    void unitTestContextUsesWildcardImportsBeforeApplicationPackageFallback() {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        AnalysisResultDto base = analysisWithServiceSource(
                "package demo; import demo.model.*; class UserService { Recipient recipient; }");
        JavaClassDto recipient = new JavaClassDto(81L, "demo.model", "Recipient", "demo.model.Recipient", "ENTITY",
                "src/main/java/demo/model/Recipient.java", "class Recipient {}", List.of(), List.of());
        JavaClassDto lookalike = new JavaClassDto(82L, "demo.other", "Recipient", "demo.other.Recipient", "ENTITY",
                "src/main/java/demo/other/Recipient.java", "class Recipient {}", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 3, base.totalMethods(), 0, 0, 0, 0, 3, 3, 0,
                List.of(), List.of(base.classes().get(1), recipient, lookalike), List.of(), List.of()));

        assertThat(builder.buildUnitTestContext(1L, Set.of(30L)).classes()).extracting("qualifiedName")
                .containsExactly("demo.UserService", "demo.model.Recipient");
    }

    @Test
    void unitTestContextPrefersSamePackageOverWildcardImportedLookalikes() {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        AnalysisResultDto base = analysisWithServiceSource(
                "package demo; import demo.model.*; class UserService { Recipient recipient; }");
        JavaClassDto recipient = new JavaClassDto(81L, "demo", "Recipient", "demo.Recipient", "ENTITY",
                "src/main/java/demo/Recipient.java", "class Recipient {}", List.of(), List.of());
        JavaClassDto lookalike = new JavaClassDto(82L, "demo.model", "Recipient", "demo.model.Recipient", "ENTITY",
                "src/main/java/demo/model/Recipient.java", "class Recipient {}", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 3, base.totalMethods(), 0, 0, 0, 0, 3, 3, 0,
                List.of(), List.of(base.classes().get(1), recipient, lookalike), List.of(), List.of()));

        assertThat(builder.buildUnitTestContext(1L, Set.of(30L)).classes()).extracting("qualifiedName")
                .containsExactly("demo.UserService", "demo.Recipient");
    }

    @Test
    void unitTestContextIgnoresNameCollisionsOutsideTheApplicationPackage() {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        AnalysisResultDto base = analysisWithServiceSource("class UserService { Recipient recipient; }");
        JavaClassDto recipient = new JavaClassDto(81L, "demo.model", "Recipient", "demo.model.Recipient",
                "ENTITY", "src/main/java/demo/model/Recipient.java", "class Recipient {}", List.of(), List.of());
        JavaClassDto unrelated = new JavaClassDto(82L, "unrelated.app", "Recipient", "unrelated.app.Recipient",
                "OTHER", "src/main/java/unrelated/app/Recipient.java", "class Recipient {}", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 3, base.totalMethods(), 0, 0, 0, 0, 3, 3, 0,
                List.of(), List.of(base.classes().get(1), recipient, unrelated), List.of(), List.of()));

        assertThat(builder.buildUnitTestContext(1L, Set.of(30L)).classes()).extracting("qualifiedName")
                .containsExactly("demo.UserService", "demo.model.Recipient");
    }

    @Test
    void unitTestContextRespectsExplicitImportsOverSamePackageLookalikes() {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        AnalysisResultDto base = analysisWithServiceSource(
                "package demo; import demo.api.Account; import java.util.Date; "
                        + "class UserService { Account account; Date date; }");
        JavaClassDto account = new JavaClassDto(81L, "demo.api", "Account", "demo.api.Account", "OTHER",
                "src/main/java/demo/api/Account.java", "class Account {}", List.of(), List.of());
        JavaClassDto lookalike = new JavaClassDto(82L, "demo", "Account", "demo.Account", "OTHER",
                "src/main/java/demo/Account.java", "class Account {}", List.of(), List.of());
        JavaClassDto date = new JavaClassDto(83L, "demo.model", "Date", "demo.model.Date", "OTHER",
                "src/main/java/demo/model/Date.java", "class Date {}", List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 4, base.totalMethods(), 0, 0, 0, 0, 4, 4, 0,
                List.of(), List.of(base.classes().get(1), account, lookalike, date), List.of(), List.of()));

        assertThat(builder.buildUnitTestContext(1L, Set.of(30L)).classes()).extracting("qualifiedName")
                .containsExactly("demo.UserService", "demo.api.Account");
    }

    @Test
    void smallDependencyGraphIsNotRejectedBecauseItContainsMoreThan25Types() {
        stubDependencyGraph(0);
        assertThat(builder.buildUnitTestContext(1L, Set.of(30L)).classes()).hasSize(27);
        assertThat(builder.buildTestPlanContext(1L, Set.of(7L)).classes()).hasSize(27);
        assertThat(builder.buildTestCaseContext(1L, Set.of(20L)).classes()).hasSize(27);
    }

    @Test
    void oversizedDependencySourceIsReportedWithoutSilentlyOmittingTypes() {
        stubDependencyGraph(5_000);
        assertThatThrownBy(() -> builder.buildUnitTestContext(1L, Set.of(30L)))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("characters");
    }

    private void stubDependencyGraph(int commentSize) {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        var classes = new java.util.ArrayList<JavaClassDto>();
        StringBuilder fields = new StringBuilder();
        for (int index = 0; index < 26; index++) {
            fields.append(" Dep").append(index).append(" field").append(index).append(";");
            classes.add(new JavaClassDto(100L + index, "demo", "Dep" + index, "demo.Dep" + index,
                    "OTHER", "src/main/java/demo/Dep" + index + ".java", "class Dep" + index + " { /*" + "x".repeat(commentSize) + "*/ }",
                    List.of(), List.of()));
        }
        AnalysisResultDto base = analysisWithServiceSource("class UserService {" + fields + "}");
        classes.add(base.classes().get(1));
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(), 27, base.totalMethods(), 0, 0, 0, 0, 27, 27, 0,
                List.of(), classes, List.of(), List.of()));

    }

    @Test
    void unitTestContextIncludesImportedProductionTypeDeclarations() {
        JavaMethodDto serviceMethod = new JavaMethodDto(
                11L, "createAppointment", "Appointment", List.of(), List.of(), "PUBLIC",
                "Appointment createAppointment() { return null; }", 10, 12, List.of(), List.of());
        JavaClassDto service = new JavaClassDto(
                10L, "demo", "AppointmentService", "demo.AppointmentService", "SERVICE",
                "src/main/java/demo/AppointmentService.java",
                "package demo; import demo.common.ResourceNotFoundException; import demo.model.Appointment; "
                        + "class AppointmentService { Appointment createAppointment() { return null; } }",
                List.of(), List.of(serviceMethod));
        JavaClassDto appointment = new JavaClassDto(
                20L, "demo.model", "Appointment", "demo.model.Appointment", "ENTITY",
                "src/main/java/demo/model/Appointment.java",
                "package demo.model; public class Appointment { private java.time.LocalTime appointmentTime; "
                        + "public void setAppointmentTime(java.time.LocalTime value) {} }",
                List.of(), List.of());
        JavaClassDto exception = new JavaClassDto(
                21L, "demo.common", "ResourceNotFoundException", "demo.common.ResourceNotFoundException", "OTHER",
                "src/main/java/demo/common/ResourceNotFoundException.java",
                "package demo.common; public class ResourceNotFoundException extends RuntimeException {}",
                List.of(), List.of());
        JavaClassDto defaultPackageType = new JavaClassDto(
                22L, "", "LegacyType", "LegacyType", "OTHER",
                "src/main/java/LegacyType.java", "class LegacyType {}", List.of(), List.of());
        AnalysisResultDto enriched = new AnalysisResultDto(
                1L, "demo", "ANALYZED", 4, 1, 0, 0, 0, 0, 4, 4, 0,
                List.of(), List.of(service, appointment, exception, defaultPackageType), List.of(), List.of());
        when(analysisService.getAnalysisResult(1L)).thenReturn(enriched);
        when(existingTestService.list(1L)).thenReturn(List.of());
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(testPlan(20L, 7L, "TP-001")));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));

        var context = builder.buildUnitTestContext(1L, Set.of(30L));

        assertThat(context.classes()).extracting("qualifiedName")
                .contains("demo.AppointmentService", "demo.model.Appointment", "demo.common.ResourceNotFoundException");
        assertThat(context.classes().stream()
                .filter(javaClass -> "demo.model.Appointment".equals(javaClass.qualifiedName()))
                .findFirst().orElseThrow().sourceCode())
                .contains("setAppointmentTime", "LocalTime");
    }

    @Test
    void normalContextsExcludePendingRulesPlansAndCases() {
        mockCommonInputs();
        JavaMethodDto transfer = new JavaMethodDto(
                11L, "transfer", "boolean", List.of(new MethodParamDto("balance", "int"),
                new MethodParamDto("amount", "int")), List.of(), "PUBLIC",
                "public boolean transfer(int balance, int amount) { return balance > amount; }",
                1, 1, List.of(), List.of());
        JavaClassDto service = new JavaClassDto(
                10L, "demo", "TransferService", "demo.TransferService", "SERVICE",
                "src/main/java/demo/TransferService.java",
                "class TransferService { public boolean transfer(int balance, int amount) { return balance > amount; } }",
                List.of(), List.of(transfer));
        when(analysisService.getAnalysisResult(1L)).thenReturn(new AnalysisResultDto(
                1L, "demo", "ANALYZED", 1, 1, 0, 0, 0, 0, 1, 1, 0,
                List.of(), List.of(service), List.of(), List.of()));
        BusinessRule approved = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        approved.setDescription("Allow transfer when balance >= amount");
        BusinessRule pending = ruleEntity(8L, 12L, ReviewStatus.PENDING_REVIEW);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(approved, pending));
        TestPlan approvedPlan = testPlan(20L, 7L, "TP-001");
        TestPlan pendingPlan = testPlan(21L, 7L, "TP-002");
        pendingPlan.setStatus(ReviewStatus.PENDING_REVIEW);
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(approvedPlan, pendingPlan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        TestCase approvedCase = new TestCase();
        approvedCase.setId(30L);
        approvedCase.setTestPlanId(20L);
        approvedCase.setCaseCode("TC-001");
        approvedCase.setStatus(ReviewStatus.APPROVED);
        approvedCase.setTestData(Map.of("balance", 100, "amount", 100));
        approvedCase.setExpectedResult("Transfer succeeds when balance equals amount");
        TestCase pendingCase = new TestCase();
        pendingCase.setId(31L);
        pendingCase.setTestPlanId(20L);
        pendingCase.setCaseCode("TC-002");
        pendingCase.setStatus(ReviewStatus.PENDING_REVIEW);
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(approvedCase, pendingCase));

        assertThat(builder.buildTestPlanContext(1L).approvedBusinessRules()).extracting("id").containsExactly(7L);
        var caseContext = builder.buildTestCaseContext(1L);
        assertThat(caseContext.approvedBusinessRules()).extracting("id").containsExactly(7L);
        assertThat(caseContext.approvedTestPlans()).extracting("id").containsExactly(20L);
        var unitContext = builder.buildUnitTestContext(1L, Set.of(30L));
        assertThat(unitContext.approvedBusinessRules()).extracting("id").containsExactly(7L);
        assertThat(unitContext.approvedTestPlans()).extracting("id").containsExactly(20L);
        assertThat(unitContext.approvedTestCases()).extracting("id").containsExactly(30L);
        assertThat(unitContext.existingApprovedTestCases()).isEmpty();
        assertThat(unitContext.classes().get(0).methods().get(0).sourceCode()).contains("balance > amount");
        assertThat(unitContext.approvedTestCases().get(0).testData()).isEqualTo(Map.of("balance", 100, "amount", 100));
        assertThat(unitContext.approvedBusinessRules().get(0).description()).contains("balance >= amount");
        assertThat(unitContext.approvedTestCases().get(0).expectedResult()).isEqualTo(approvedCase.getExpectedResult());
        org.mockito.Mockito.verify(testCaseRepository, org.mockito.Mockito.never()).findByTestPlanId(21L);
    }

    @Test
    void contextRejectsApprovedDescendantsWhenRequiredRuleIsPending() {
        mockCommonInputs();
        BusinessRule pending = ruleEntity(7L, 11L, ReviewStatus.PENDING_REVIEW);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(pending));
        TestPlan plan = testPlan(20L, 7L, "TP-001");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.buildTestCaseContext(1L, Set.of(20L)))
                .isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.buildUnitTestContext(1L, Set.of(30L)))
                .isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.buildTestPlanContext(1L, Set.of(7L)))
                .isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class);
    }

    @Test
    void unitTestContextUsesCandidateSourceForChangedMethod() {
        mockCommonInputs();
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceSource(
                "package demo; class UserService { User createUser(String email) { return null; } }"));
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        TestPlan plan = testPlan(20L, 7L, "TP-001");
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setPriority(Priority.HIGH);
        testCase.setStatus(ReviewStatus.APPROVED);
        testCase.setExpectedResult("Approved result remains unchanged");
        testCase.setTestData(Map.of("email", "approved@example.com"));
        testCase.setPreconditions("Approved setup");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));

        MethodDiffItem changedMethod = new MethodDiffItem(
                "UserService", "demo.UserService", "createUser", "createUser(String)",
                "demo.UserService#createUser(String)", MethodDiffType.MODIFIED, "Logic changed",
                "User createUser(String email) { return null; }",
                "User createUser(String email) { return repository.save(new User(email)); }",
                List.of(), true);

        var context = builder.buildUnitTestContext(1L, Set.of(30L), List.of(changedMethod));

        assertThat(context.classes().get(0).methods().get(0).sourceCode())
                .contains("repository.save(new User(email))");
        assertThat(context.approvedTestCases().get(0).expectedResult()).isEqualTo(testCase.getExpectedResult());
        assertThat(context.approvedTestCases().get(0).testData()).isEqualTo(testCase.getTestData());
        assertThat(context.approvedTestCases().get(0).preconditions()).isEqualTo(testCase.getPreconditions());
    }

    @Test
    void unitTestContextOmitsDeletedMethod() {
        mockCommonInputs();
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 12L, ReviewStatus.APPROVED)));
        TestPlan plan = testPlan(20L, 7L, "TP-001");
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setPriority(Priority.HIGH);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));

        MethodDiffItem deletedMethod = new MethodDiffItem(
                "UserService", "demo.UserService", "updateUser", "updateUser()",
                "demo.UserService#updateUser()", MethodDiffType.DELETED, "Method deleted",
                "User updateUser() { return null; }", null, List.of(), true);

        var context = builder.buildUnitTestContext(1L, Set.of(30L), List.of(deletedMethod));

        assertThat(context.classes().get(0).methods()).extracting("methodName")
                .doesNotContain("updateUser");
    }

    @Test
    void deletedMethodDoesNotHideRemainingMethodFromValidation() {
        mockCommonInputs();
        String deleted = "User updateUser() { return null; }";
        String source = "package demo; class UserService { "
                + "User createUser(String email) { return null; } " + deleted + " }";
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceSource(source));
        stubApprovedUnitTestCase(11L);
        MethodDiffItem deletedMethod = new MethodDiffItem("UserService", "demo.UserService", "updateUser",
                "updateUser()", "demo.UserService#updateUser()", MethodDiffType.DELETED, "Method deleted",
                deleted, null, List.of(), true);

        var context = builder.buildUnitTestContext(1L, Set.of(30L), List.of(deletedMethod));

        assertThat(context.classes().get(0).validationSourceCode()).contains("createUser").doesNotContain("updateUser");
        String generated = "package demo; class UserServiceTest { UserService userService; "
                + "@org.junit.jupiter.api.Test void testCase() { userService.updateUser(); } }";
        var response = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                30L, "UserServiceTest", "testCase", "demo", "NEW_TEST", generated)));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response))
                .hasValueSatisfying(message -> assertThat(message).contains("updateUser", "does not exist"));
    }

    @Test
    void changedMethodWithStaleSourceFailsBeforeGeneration() {
        mockCommonInputs();
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceSource(
                "package demo; class UserService { User createUser(String email) { return null; } }"));
        stubApprovedUnitTestCase(11L);
        MethodDiffItem changedMethod = new MethodDiffItem(
                "UserService", "demo.UserService", "createUser", "createUser(String)",
                "demo.UserService#createUser(String)", MethodDiffType.MODIFIED, "Logic changed",
                "User createUser(String email) { throw new IllegalStateException(); }",
                "User createUser(String email) { return repository.save(new User(email)); }",
                List.of(), true);

        assertThatThrownBy(() -> builder.buildUnitTestContext(1L, Set.of(30L), List.of(changedMethod)))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("Cannot verify current source");
    }

    @Test
    void addedMethodIsIncludedInValidationSource() {
        mockCommonInputs();
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceSource(
                "package demo; class UserService { User createUser(String email) { return null; } }"));
        stubApprovedUnitTestCase(11L);
        String added = "User updateUser() { return null; }";
        MethodDiffItem addedMethod = new MethodDiffItem(
                "UserService", "demo.UserService", "updateUser", "updateUser()",
                "demo.UserService#updateUser()", MethodDiffType.ADDED, "Method added",
                null, added, List.of(), true);

        var context = builder.buildUnitTestContext(1L, Set.of(30L), List.of(addedMethod));

        assertThat(context.classes().get(0).validationSourceCode()).contains("createUser", "updateUser()", "return null");
        String generated = "package demo; class UserServiceTest { UserService userService; "
                + "@org.junit.jupiter.api.Test void testCase() { userService.updateUser(); } }";
        var response = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                30L, "UserServiceTest", "testCase", "demo", "NEW_TEST", generated)));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response)).isEmpty();
    }

    @Test
    void newServiceClassWithoutSourceFailsBeforeGeneration() {
        mockCommonInputs();
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceSource(null));
        stubApprovedUnitTestCase(11L);
        MethodDiffItem addedMethod = new MethodDiffItem(
                "UserService", "demo.UserService", "createUser", "createUser(String)",
                "demo.UserService#createUser(String)", MethodDiffType.ADDED, "Method added",
                null, "User createUser(String email) { return null; }", List.of(), true);

        assertThatThrownBy(() -> builder.buildUnitTestContext(1L, Set.of(30L), List.of(addedMethod)))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("Cannot verify current source");
    }

    @Test
    void snapshotContextRestoresImportsAndNewDependenciesWithoutChangingStoredIds(@TempDir Path snapshot) throws Exception {
        mockCommonInputs();
        AnalysisResultDto stored = analysisWithServiceSource("class UserService {}");
        when(analysisService.getAnalysisResult(1L)).thenReturn(stored);
        stubApprovedUnitTestCase(11L);
        writeSnapshotSource(snapshot, "src/main/java/demo/UserService.java", """
                package demo;
                import demo.dto.NewState;
                public class UserService {
                    private NewState state;
                    public User createUser(String email) {
                        if (email == null) throw new IllegalArgumentException("email required");
                        return new User(email);
                    }
                }
                """);
        writeSnapshotSource(snapshot, "src/main/java/demo/User.java",
                "package demo; public class User { public User(String email) {} }");
        writeSnapshotSource(snapshot, "src/main/java/demo/dto/NewState.java",
                "package demo.dto; public enum NewState { ACTIVE }");

        var context = builder.buildSnapshotUnitTestContext(1L, Set.of(30L), snapshot);

        var service = context.classes().stream().filter(c -> c.qualifiedName().equals("demo.UserService")).findFirst().orElseThrow();
        assertThat(service.id()).isEqualTo(10L);
        assertThat(service.validationSourceCode()).contains("package demo;", "import demo.dto.NewState;", "email required");
        assertThat(service.sourceCode()).isEqualTo(service.validationSourceCode());
        assertThat(service.methods()).singleElement().satisfies(method -> {
            assertThat(method.id()).isEqualTo(11L);
            assertThat(method.sourceCode()).contains("new User(email)");
            assertThat(method.lineStart()).isEqualTo(5);
        });
        assertThat(context.classes()).extracting("qualifiedName").contains("demo.User", "demo.dto.NewState");
        assertThat(context.approvedBusinessRules()).extracting("id").containsExactly(7L);
        assertThat(context.approvedTestPlans()).extracting("id").containsExactly(20L);
        assertThat(context.approvedTestCases()).extracting("id").containsExactly(30L);
        assertThat(stored.classes().get(1).sourceCode()).isEqualTo("class UserService {}");
    }

    @Test
    void snapshotContextRejectsMissingTargetInsteadOfUsingOldDatabaseSource(@TempDir Path snapshot) throws Exception {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        writeSnapshotSource(snapshot, "src/main/java/demo/User.java", "package demo; public class User {}");

        assertThatThrownBy(() -> builder.buildSnapshotUnitTestContext(1L, Set.of(30L), snapshot))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("target method");
    }

    @Test
    void snapshotContextRejectsAmbiguousClassAcrossModules(@TempDir Path snapshot) throws Exception {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        writeSnapshotSource(snapshot, "module-a/src/main/java/demo/UserService.java",
                "package demo; class UserService { User createUser(String email) { return null; } }");
        writeSnapshotSource(snapshot, "module-b/src/main/java/demo/UserService.java",
                "package demo; class UserService { User createUser(String email) { throw new IllegalStateException(); } }");

        assertThatThrownBy(() -> builder.buildSnapshotUnitTestContext(1L, Set.of(30L), snapshot))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("Ambiguous");
    }

    private void writeSnapshotSource(Path snapshot, String relativePath, String source) throws Exception {
        Path file = snapshot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void snapshotContextRejectsAmbiguousNewSupportingTypes(@TempDir Path snapshot) throws Exception {
        mockCommonInputs();
        stubApprovedUnitTestCase(11L);
        writeSnapshotSource(snapshot, "src/main/java/demo/UserService.java",
                "package demo; import demo.dto.NewState; class UserService { NewState state; "
                        + "User createUser(String email) { return null; } }");
        writeSnapshotSource(snapshot, "module-a/src/main/java/demo/dto/NewState.java",
                "package demo.dto; public enum NewState { ACTIVE }");
        writeSnapshotSource(snapshot, "module-b/src/main/java/demo/dto/NewState.java",
                "package demo.dto; public enum NewState { INACTIVE }");

        assertThatThrownBy(() -> builder.buildSnapshotUnitTestContext(1L, Set.of(30L), snapshot))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("Ambiguous", "demo.dto.NewState");
    }

    @Test
    void refinementContextContainsOnlyGapMethodsAndTheirPlans() {
        mockCommonInputs();
        BusinessRule approved = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(approved));
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setPlanCode("TP-001");
        plan.setStatus(ReviewStatus.APPROVED);
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));

        var gap = new CoverageGapDto(11L, "UserService", "createUser",
                java.math.BigDecimal.valueOf(40), java.math.BigDecimal.valueOf(50),
                List.of(11), List.of(11), "HIGH", "cover branch", true);
        var context = builder.buildCoverageRefinementContext(1L, 2, List.of(gap));

        assertThat(context.round()).isEqualTo(2);
        assertThat(context.coverageGaps()).containsExactly(gap);
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
        assertThat(context.approvedTestPlans()).extracting("id").containsExactly(20L);
    }

    private void mockCommonInputs() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysis());
        when(manifestService.exportManifest(1L)).thenReturn(manifest());
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest()));
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
    }

    private AnalysisResultDto analysis() {
        JavaMethodDto serviceMethod = new JavaMethodDto(
                11L,
                "createUser",
                "User",
                List.of(new MethodParamDto("email", "String")),
                List.of(),
                "PUBLIC",
                "User createUser(String email) { return null; }",
                10,
                12,
                List.of(new RelevantAnnotationDto(20L, "METHOD", "TRANSACTION", "Transactional", "@Transactional")),
                List.of());
        JavaClassDto service = new JavaClassDto(
                10L,
                "demo",
                "UserService",
                "demo.UserService",
                "SERVICE",
                "src/main/java/demo/UserService.java",
                "class UserService { private UserRepository repository; }",
                List.of(),
                List.of(serviceMethod, new JavaMethodDto(
                        12L,
                        "updateUser",
                        "User",
                        List.of(),
                        List.of(),
                        "PUBLIC",
                        "User updateUser() { String note = \"repository.fake(\"; /* repository.bad( */ return repository.save(new User()); }",
                        14,
                        16,
                        List.of(),
                        List.of())));
        JavaMethodDto controllerMethod = new JavaMethodDto(
                31L,
                "create",
                "User",
                List.of(),
                List.of(),
                "PUBLIC",
                "User create() { return null; }",
                3,
                5,
                List.of(),
                List.of(new EndpointDto(40L, "POST", "/users", null, null, "create")));
        JavaClassDto controller = new JavaClassDto(
                30L,
                "demo",
                "UserController",
                "demo.UserController",
                "CONTROLLER",
                "src/main/java/demo/UserController.java",
                null,
                List.of(),
                List.of(controllerMethod));
        return new AnalysisResultDto(
                1L,
                "demo",
                "ANALYZED",
                2,
                2,
                1,
                1,
                1,
                1,
                2,
                2,
                0,
                List.of(),
                List.of(controller, service),
                List.of(new ServiceRelationDto(
                        60L,
                        "UserService",
                        "demo.UserService",
                        "UserRepository",
                        "demo.UserRepository")),
                List.of(new ControllerServiceRelationDto(
                        70L,
                        "UserController",
                        "demo.UserController",
                        "create",
                        "UserService",
                        "demo.UserService",
                        "createUser",
                        "userService",
                        "UserService")));
    }

    private AnalysisResultDto analysisWithServiceSource(String source) {
        AnalysisResultDto base = analysis();
        JavaClassDto previous = base.classes().get(1);
        JavaClassDto service = new JavaClassDto(previous.id(), previous.packageName(), previous.className(),
                previous.qualifiedName(), previous.classType(), previous.filePath(), source,
                previous.annotations(), previous.methods());
        return new AnalysisResultDto(base.projectId(), base.projectName(), base.status(), base.totalClasses(),
                base.totalMethods(), base.totalEndpoints(), base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles(), base.parsedProductionFiles(),
                base.failedParseFiles(), base.failedParseFilePaths(), List.of(base.classes().get(0), service),
                base.relations(), base.controllerServiceRelations());
    }

    private void stubApprovedUnitTestCase(Long methodId) {
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, methodId, ReviewStatus.APPROVED)));
        when(testPlanRepository.findByProjectId(1L))
                .thenReturn(List.of(testPlan(20L, 7L, "TP-001")));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setPriority(Priority.HIGH);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));
    }

    private AnalysisResultDto analysisWithServiceMethods(int count) {
        List<JavaMethodDto> methods = LongStream.rangeClosed(1, count)
                .mapToObj(id -> new JavaMethodDto(
                        id, "method" + id, "void", List.of(), List.of(), "PUBLIC", "void method() {}",
                        1, 1, List.of(), List.of()))
                .toList();
        JavaClassDto service = new JavaClassDto(
                1L, "demo", "BatchService", "demo.BatchService", "SERVICE",
                "src/main/java/demo/BatchService.java", null, List.of(), methods);
        return new AnalysisResultDto(
                1L, "demo", "ANALYZED", 1, count, 0, 0, 0, 0, 1, 1, 0,
                List.of(), List.of(service), List.of(), List.of());
    }

    private AnalysisManifestDto manifest() {
        return new AnalysisManifestDto(
                1L,
                "demo",
                "1.1",
                List.of("demo.UserController", "demo.UserService"),
                List.of("demo.UserService#createUser(String):User"),
                List.of("POST /users -> demo.UserController#create():User"),
                List.of(),
                List.of("demo.UserService -> demo.UserRepository"),
                List.of("demo.UserController#create -> demo.UserService#createUser via userService"));
    }

    private ExistingTestDto existingTest() {
        return new ExistingTestDto(
                50L,
                1L,
                "src/test/java/demo/UserServiceTest.java",
                "demo",
                "UserServiceTest",
                10L,
                null,
                List.of(Map.of("name", "createUser_success", "assertions", List.of("assertEquals"))),
                List.of("org.junit.jupiter.api.Test"),
                "class UserServiceTest {}",
                null);
    }

    private BusinessRule ruleEntity(Long id, Long methodId, ReviewStatus status) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(1L);
        rule.setMethodId(methodId);
        rule.setRuleCode("BR-001");
        rule.setDescription("Email phai hop le truoc khi tao user.");
        rule.setReviewNote("OK");
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(status);
        rule.setIsModified(false);
        return rule;
    }

    private TestPlan testPlan(Long id, Long ruleId, String code) {
        TestPlan plan = new TestPlan();
        plan.setId(id);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(ruleId);
        plan.setPlanCode(code);
        plan.setTitle("Plan " + code);
        plan.setDescription("Description " + code);
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.APPROVED);
        return plan;
    }

    private TestPlanCoveredRule coveredRule(Long planId, Long ruleId) {
        TestPlanCoveredRule link = new TestPlanCoveredRule();
        link.setTestPlanId(planId);
        link.setBusinessRuleId(ruleId);
        return link;
    }
}
