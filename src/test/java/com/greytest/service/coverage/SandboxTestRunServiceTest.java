package com.greytest.service.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.greytest.entity.Project;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.UnitTestService;
import com.greytest.service.agent.ProjectJavaVersionDetector;
import com.greytest.service.coverage.SandboxTestRunService.Status;

class SandboxTestRunServiceTest {
    @TempDir Path sourceRoot;
    private final UnitTestService units = mock(UnitTestService.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CoverageService coverage = mock(CoverageService.class);
    private final GenerationJobService jobs = mock(GenerationJobService.class);
    private final ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);
    private final List<List<String>> commands = new ArrayList<>();
    /** Kết quả giả của container: exit code của lệnh start và có sinh jacoco.xml hay không. */
    private int startExit;
    private boolean producesReport;
    private boolean generatedTestReachedContainer;
    /** Các testcase không đạt của từng lượt chạy (lượt sau cùng lặp lại), và số lượt đầu còn sinh được báo cáo. */
    private List<String> casesPerRun = List.of(errorCase("rejectsEmptyCart"));
    private int reportRuns = Integer.MAX_VALUE;
    private int runs;
    private List<String> logsPerRun = List.of("[ERROR] COMPILATION ERROR\n");
    private int firstReportRun = 1;
    private boolean throwsOnRun;
    private int timeoutRun;
    private SandboxTestRunService service;
    private final List<String> progress = new ArrayList<>();
    private int warmExit;
    private boolean throwsOnWarm;
    private int warms;
    private boolean dockerUnavailable;
    private int createExit;
    private java.util.concurrent.CountDownLatch executionEntered;
    private java.util.concurrent.CountDownLatch executionRelease;
    private final java.util.Set<String> containers = new java.util.HashSet<>();
    private int maxContainers;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void serializesManualAndAutomaticSandboxesAcrossProjects(boolean sameProject, boolean manualFirst) throws Exception {
        Long manualProject = sameProject ? 1L : 2L;
        Project second = new Project(); second.setId(2L); second.setStoragePath(sourceRoot.toString());
        when(projects.findById(2L)).thenReturn(Optional.of(second));
        var archive = units.exportArchive(1L, null);
        when(units.exportArchive(eq(2L), any())).thenReturn(archive);
        when(jobs.executeMutation(eq(2L), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        producesReport = true;
        casesPerRun = List.of("");
        executionEntered = new java.util.concurrent.CountDownLatch(1);
        executionRelease = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var saveTests = new java.util.concurrent.CountDownLatch(manualFirst ? 0 : 1);
        try {
            service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, executor, this::fakeDocker);
            if (manualFirst) service.start(manualProject, null);
            var automatic = java.util.concurrent.CompletableFuture.runAsync(() -> service.generateAndVerify(1L, null, () -> {
                try {assertThat(saveTests.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){throw new IllegalStateException(failure);}
                return true;
            }));
            assertThat(executionEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if (!manualFirst) service.start(manualProject, null);
            saveTests.countDown();
            executionRelease.countDown();
            automatic.get(5, java.util.concurrent.TimeUnit.SECONDS);
            executor.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(maxContainers).isEqualTo(1);
            assertThat(containers).isEmpty();
            assertThat(commands.stream().filter(c -> c.get(0).equals("create")).count()).isBetween(2L,3L);
        } finally { saveTests.countDown(); executionRelease.countDown(); executor.shutdownNow(); }
    }

    @Test
    void unusedStubFailuresCarryTheExportedSourceLineMockitoPointsTo() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("module/src/test/java/demo/FooTest.java"));
            zip.write("package demo;\nclass FooTest {\n    when(repo.find(1L)).thenReturn(item);\n}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String stack = "org.mockito.exceptions.misusing.UnnecessaryStubbingException: \n"
                + "  1. -> at demo.FooTest_Fixture2Test.works(FooTest.java:3)\n";
        assertThat(SandboxTestRunService.unusedStubLines(stack, bytes.toByteArray()))
                .isEqualTo(SandboxTestRunService.UNUSED_STUB_LINE + "when(repo.find(1L)).thenReturn(item);\n");
        assertThat(SandboxTestRunService.unusedStubLines("java.lang.NullPointerException", bytes.toByteArray())).isEmpty();
    }

    @Test
    void quotaExhaustionStopsRepairAndKeepsRuntimeFailures() throws Exception {
        producesReport = true;
        when(jobs.executeAiMutation(eq(1L), eq(7L), any(), any(Supplier.class)))
                .thenThrow(new com.greytest.exception.UsageQuotaExceededException("quota"));
        automatic(true);
        assertThat(runs).isEqualTo(1);
        assertThat(progress.get(progress.size() - 1)).contains("hết quota LLM", "Giữ lại 1 test");
        verify(units, never()).removeUncompilable(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void partialRepairWithExhaustedQuotaRerunsButDoesNotStartAnotherRepairRound(boolean improved) throws Exception {
        producesReport=true;
        String initial = errorCase("first") + errorCase("second");
        casesPerRun=List.of(initial, improved ? errorCase("second") : initial);
        when(units.repairFailing(eq(1L),any())).thenAnswer(call->{
            com.greytest.service.GenerationJobContext.markRepairQuotaExhausted();
            return List.of(new UnitTest());
        });
        automatic(true);
        assertThat(runs).isEqualTo(2);
        verify(units).repairFailing(eq(1L),any());
        assertThat(progress.get(progress.size()-1)).contains("hết quota LLM", "Giữ lại " + (improved ? 1 : 2) + " test");
    }

    @Test
    void infrastructureFailureAfterRepairOnlyWarns() throws Exception {
        producesReport=true;
        timeoutRun=2;
        when(units.repairFailing(eq(1L),any())).thenReturn(List.of(new UnitTest()));
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            service=new SandboxTestRunService(units,projects,coverage,jobs,javaVersions,executor,this::fakeDocker);
            com.greytest.service.GenerationJobContext.bind(7L,progress::add);
            service.generateAndVerify(1L,null,()->true);
        } finally {com.greytest.service.GenerationJobContext.clear();executor.shutdownNow();}
        assertThat(progress.get(progress.size()-1)).contains("CHƯA được xác minh", "giữ lại");
        assertThat(service.state(1L).logTail()).contains("COMPILATION ERROR");
    }

    @Test
    void rejectedCoverageUploadDoesNotDiscardTheVerificationResult() throws Exception {
        // Module chưa đủ điều kiện nhận coverage (ví dụ còn Test Plan thiếu Test Case) vẫn phải nhận kết quả chạy test.
        producesReport = true;
        when(coverage.upload(any(), any(), any())).thenThrow(new IllegalStateException("module chưa đủ điều kiện"));
        automatic(true);
        assertThat(progress.get(progress.size() - 1)).contains("Đã chạy", "Chưa cập nhật coverage: module chưa đủ điều kiện")
                .doesNotContain("CHƯA được xác minh");
    }

    @Test
    void generationFailureAlwaysRemovesThePrewarmedContainer() throws Exception {
        executionEntered=new java.util.concurrent.CountDownLatch(1);
        executionRelease=new java.util.concurrent.CountDownLatch(1);
        executionRelease.countDown();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            service=new SandboxTestRunService(units,projects,coverage,jobs,javaVersions,executor,this::fakeDocker);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->service.generateAndVerify(1L,null,()->{
                try {assertThat(executionEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){throw new IllegalStateException(failure);}
                throw new IllegalStateException("generation failed");
            })).hasMessage("generation failed");
            assertThat(containers).isEmpty();
            assertThat(commands.get(commands.size()-1)).startsWith("rm","-f");
            verify(units,never()).exportArchive(any(),any());
        } finally {executor.shutdownNow();}
    }

    @Test
    void rejectedSandboxTaskStillSavesGenerationAndWarns() {
        service=new SandboxTestRunService(units,projects,coverage,jobs,javaVersions,
                task->{throw new java.util.concurrent.RejectedExecutionException("executor stopped");},this::fakeDocker);
        var saved=new java.util.concurrent.atomic.AtomicInteger();
        com.greytest.service.GenerationJobContext.bind(7L,progress::add);
        try {service.generateAndVerify(1L,null,()->{saved.incrementAndGet();return true;});}
        finally {com.greytest.service.GenerationJobContext.clear();}
        assertThat(saved).hasValue(1);
        assertThat(commands).isEmpty();
        assertThat(progress).singleElement().asString().contains("CHƯA được xác minh", "executor stopped");
    }

    private void automatic(boolean hasTests) throws Exception {
        automatic(hasTests, "");
    }

    private void automatic(boolean hasTests, String mavenArgs) throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, executor, this::fakeDocker);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "mavenArgs", mavenArgs);
            var warmed=new java.util.concurrent.CountDownLatch(1);
            com.greytest.service.GenerationJobContext.bind(7L, message->{
                progress.add(message);
                if(message.startsWith("Sandbox đã sẵn sàng")||message.startsWith("Không chuẩn bị trước")) warmed.countDown();
            });
            try { service.generateAndVerify(1L, null, () -> {
                if(hasTests) try {assertThat(warmed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){throw new IllegalStateException(failure);}
                return hasTests;
            }); }
            finally { com.greytest.service.GenerationJobContext.clear(); }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void automaticallyRepairsAndLogsBeforeReturning() throws Exception {
        producesReport = true;
        casesPerRun = List.of(errorCase("first"), "");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        automatic(true);
        assertThat(runs).isEqualTo(2);
        assertThat(progress).anyMatch(line -> line.contains("Đang chuẩn bị sandbox"))
                .anyMatch(line -> line.contains("Đang biên dịch và chạy"))
                .anyMatch(line -> line.contains("AI đang sửa 1") && line.contains("vòng 1/2"));
        assertThat(progress.get(progress.size() - 1)).isEqualTo("AI đã sửa 1 lượt test qua 1 vòng. Đã chạy 2 test: 2 đạt, 0 không đạt. Coverage đã được cập nhật.");
        verify(jobs).executeAiMutation(eq(1L), eq(7L), any(), any(Supplier.class));
    }

    @Test
    void passesConfiguredMavenArgumentsToWarmCompileAndEveryTestAttempt() throws Exception {
        producesReport = true;
        casesPerRun = List.of(errorCase("first"), "");
        String args = "-Dskip.npm=true -Dskip.installnodenpm=true";
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        automatic(true, args);

        assertThat(warms).isEqualTo(1);
        assertThat(runs).isEqualTo(2);
        assertThat(commands.stream().filter(command -> command.get(0).equals("exec") && !command.contains("rm")))
                .allSatisfy(command -> assertThat(command).containsSubsequence("-e", "MAVEN_ARGS=" + args));
    }

    @Test
    void removesOnlyAttributedCompilerFailuresAndConfirmsWithOneFastAttempt() throws Exception {
        compilationArchive();
        logsPerRun = List.of(compileError(8, "bad stub"), "ok");
        producesReport = true;
        firstReportRun = 2;
        casesPerRun = List.of("");
        when(units.removeUncompilable(eq(1L), any())).thenReturn(1);
        automatic(true);
        verify(units).removeUncompilable(eq(1L), org.mockito.ArgumentMatchers.argThat(failures ->
                failures.size() == 1 && failures.get(0).methodName().equals("second")));
        assertThat(runs).isEqualTo(2);
        assertThat(progress).contains("Đã loại 1 Unit Test không biên dịch được. Đang xác minh lại phần còn lại...");
        assertThat(service.state(1L).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void confirmsEmptyPackageAfterAllGeneratedTestsAreRemoved() throws Exception {
        compilationArchive();
        logsPerRun=List.of(compileError(3,"missing fixture"),"ok");
        producesReport=true;
        firstReportRun=2;
        casesPerRun=List.of("");
        when(units.removeUncompilable(eq(1L),any())).thenReturn(2);
        when(units.exportSandboxArchive(eq(1L),any())).thenReturn(new UnitTestService.ExportArchive(
                new com.greytest.service.UnitTestFileService().createEmptySandboxArchive(),List.of()));
        automatic(true);
        verify(units).exportSandboxArchive(1L,null);
        assertThat(runs).isEqualTo(2);
        assertThat(service.state(1L).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(progress.get(progress.size()-1)).contains("Đã loại 2 Unit Test");
        assertThat(commands.stream().filter(c->c.contains("rm")&&c.get(0).equals("exec")))
                .singleElement().satisfies(c->assertThat(c).contains("/work/src/test/java/demo/ShopTest.java"));
    }

    @Test
    void boundsRemovalToTwoPassesAndOneConfirmingAttempt() throws Exception {
        compilationArchive();
        logsPerRun = List.of(compileError(8, "bad stub"));
        when(units.removeUncompilable(eq(1L), any())).thenReturn(1);
        automatic(true);
        verify(units, org.mockito.Mockito.times(2)).removeUncompilable(eq(1L), any());
        assertThat(runs).isEqualTo(2);
        assertThat(progress.get(progress.size() - 1)).contains("chưa được xác minh lại");
    }

    @Test
    void leavesRuntimeTechnicalFailuresStored() throws Exception {
        producesReport = true;
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        automatic(true);
        verify(units, never()).removeUncompilable(any(), any());
        assertThat(progress.get(progress.size() - 1)).contains("Giữ lại 1 test còn lỗi kỹ thuật khi chạy");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"docker", "image", "timeout", "exception", "project"})
    void infrastructureFailureWarnsWithoutRemovingTests(String failure) throws Exception {
        dockerUnavailable = failure.equals("docker");
        createExit = failure.equals("image") ? 125 : 0;
        timeoutRun = failure.equals("timeout") ? 1 : 0;
        throwsOnRun = failure.equals("exception");
        if (failure.equals("project")) logsPerRun = List.of("[ERROR] /work/src/main/java/demo/Shop.java:[1,1] broken\n");
        automatic(true);
        assertThat(progress).filteredOn(line -> line.startsWith("Cảnh báo:")).hasSize(1);
        assertThat(progress.get(progress.size() - 1)).contains("Unit Test CHƯA được xác minh");
        verify(units, never()).removeUncompilable(any(), any());
        verify(units, never()).repairFailing(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"docker","image","timeout","exception"})
    void generationJobSucceedsAndWarnsOnceWhenVerificationInfrastructureFails(String failure) throws Exception {
        dockerUnavailable=failure.equals("docker");
        createExit=failure.equals("image")?125:0;
        timeoutRun=failure.equals("timeout")?1:0;
        throwsOnRun=failure.equals("exception");
        var jobProgress=new com.greytest.service.GenerationProgressService();
        var realJobs=new GenerationJobService(Runnable::run,jobProgress,null,null,null);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            service=new SandboxTestRunService(units,projects,coverage,realJobs,javaVersions,executor,this::fakeDocker);
            realJobs.submit(1L,7L,com.greytest.dto.GenerationProgressStage.UNIT_TEST,()->
                    com.greytest.service.GenerationProgressService.deferCompletion(()->service.generateAndVerify(1L,null,()->{
                        jobProgress.start(1L,com.greytest.dto.GenerationProgressStage.UNIT_TEST,1,"Đang sinh.");
                        jobProgress.completeAfterCommit(1L,com.greytest.dto.GenerationProgressStage.UNIT_TEST,"Hoàn tất: Đã lưu 2 Unit Test.");
                        return true;
                    })));
            var result=jobProgress.get(1L,com.greytest.dto.GenerationProgressStage.UNIT_TEST);
            assertThat(result.status()).isEqualTo(com.greytest.dto.GenerationProgressStatus.COMPLETED);
            assertThat(result.logs()).filteredOn(line->line.message().contains("Cảnh báo:")).hasSize(1);
            assertThat(result.logs().get(result.logs().size()-1).message()).contains("CHƯA được xác minh", "giữ lại");
            verify(units,never()).removeUncompilable(any(),any());
        } finally {executor.shutdownNow();}
    }

    @Test
    void noTestsSkipsVerificationAndCleansAnyWarmContainer() throws Exception {
        automatic(false);
        assertThat(runs).isZero();
        verify(units, never()).exportArchive(any(), any());
        assertThat(commands.stream().filter(c -> c.get(0).equals("create")).count())
                .isEqualTo(commands.stream().filter(c -> c.get(0).equals("rm")).count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unitStageCountsAiWaitFromGenerationAndRepairOnly(boolean needsRepair) {
        producesReport = true;
        casesPerRun = needsRepair ? List.of(errorCase("technicalError"), "") : List.of("");
        var lastAiWait = new java.util.concurrent.atomic.AtomicLong();
        when(units.repairFailing(eq(1L), any())).thenAnswer(call -> {
            Runnable finishWait = GenerationJobContext.startLlmWait();
            try { Thread.sleep(10); }
            finally { finishWait.run(); }
            long afterRepair = (long) GenerationJobContext.metrics().get("llmHttpWaitNanos");
            assertThat(afterRepair).isGreaterThanOrEqualTo(lastAiWait.get() + 10_000_000);
            lastAiWait.set(afterRepair);
            return List.of(new UnitTest());
        });
        var jobProgress = new GenerationProgressService();
        var realJobs = new GenerationJobService(Runnable::run, jobProgress, null, null, null);
        var stage = GenerationProgressStage.UNIT_TEST;
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            service = new SandboxTestRunService(units, projects, coverage, realJobs, javaVersions,
                    executor, this::fakeDocker);
            realJobs.submit(1L, 7L, stage, () -> GenerationProgressService.deferCompletion(() ->
                    service.generateAndVerify(1L, null, () -> {
                        jobProgress.start(1L, stage, 1, "Generating");
                        Runnable finishWait = GenerationJobContext.startLlmWait();
                        try { Thread.sleep(10); }
                        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        finally { finishWait.run(); }
                        lastAiWait.set((long) GenerationJobContext.metrics().get("llmHttpWaitNanos"));
                        jobProgress.completeAfterCommit(1L, stage, "Generated");
                        return true;
                    })));
            var result = jobProgress.get(1L, stage);
            assertThat(result.status()).isEqualTo(GenerationProgressStatus.COMPLETED);
            assertThat(runs).isEqualTo(needsRepair ? 2 : 1);
            assertThat(result.runMetrics().llmWaitMillis()).isPositive().isEqualTo(lastAiWait.get() / 1_000_000);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void removesAnAlreadyWarmedContainerWhenGenerationReturnsNoTests() throws Exception {
        var warmed=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            service=new SandboxTestRunService(units,projects,coverage,jobs,javaVersions,executor,this::fakeDocker);
            com.greytest.service.GenerationJobContext.bind(7L,message->{if(message.startsWith("Sandbox đã sẵn sàng"))warmed.countDown();});
            service.generateAndVerify(1L,null,()->{
                try {assertThat(warmed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){throw new IllegalStateException(failure);}
                return false;
            });
            assertThat(warms).isEqualTo(1);
            assertThat(runs).isZero();
            assertThat(containers).isEmpty();
            assertThat(commands.get(commands.size()-1)).startsWith("rm","-f");
            verify(units,never()).exportArchive(any(),any());
        } finally {com.greytest.service.GenerationJobContext.clear();executor.shutdownNow();}
    }

    @Test
    void gradleSkipsPrewarmAndUsesTheNormalOnlineFirstAttempt() throws Exception {
        Files.delete(sourceRoot.resolve("pom.xml"));
        Files.writeString(sourceRoot.resolve("build.gradle"), "plugins { id 'java' }");
        producesReport=true;
        casesPerRun=List.of("");
        automatic(true);
        assertThat(warms).isZero();
        assertThat(commands.stream().filter(c->c.contains("./run-greytest-coverage.sh"))).singleElement()
                .satisfies(c->assertThat(c).doesNotContain("GREYTEST_FAST_RERUN=1","-o"));
    }

    @Test
    void reusesWarmContainerFastButOnlineThenRepairsOffline() throws Exception {
        producesReport = true;
        casesPerRun = List.of(errorCase("first"), "");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        automatic(true);
        assertThat(warms).isEqualTo(1);
        assertThat(commands.stream().filter(c -> c.get(0).equals("create"))).hasSize(1);
        var attempts = commands.stream().filter(c -> c.contains("./run-greytest-coverage.sh")).toList();
        assertThat(attempts.get(0)).contains("GREYTEST_FAST_RERUN=1").doesNotContain("-o");
        assertThat(attempts.get(attempts.size() - 1)).contains("GREYTEST_FAST_RERUN=1", "-o");
        assertThat(commands.get(commands.size() - 1)).startsWith("rm", "-f");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"exit","timeout","exception"})
    void failedWarmCompileCleansContainerAndFallsBackToNormalOnlineAttempt(String failure) throws Exception {
        warmExit = failure.equals("timeout") ? -1 : 1;
        throwsOnWarm=failure.equals("exception");
        producesReport = true;
        casesPerRun = List.of("");
        automatic(true);
        assertThat(commands.stream().filter(c -> c.get(0).equals("create"))).hasSize(2);
        assertThat(commands.stream().filter(c -> c.get(0).equals("rm"))).hasSize(2);
        assertThat(commands.stream().filter(c -> c.contains("./run-greytest-coverage.sh"))).singleElement()
                .satisfies(c -> assertThat(c).doesNotContain("GREYTEST_FAST_RERUN=1", "-o"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "WantedButNotInvoked", "ArgumentsAreDifferent", "opentest4j.ArgumentsAreDifferent",
            "junit.ArgumentsAreDifferent", "TooManyActualInvocations", "NoInteractionsWanted", "NeverWantedButInvoked"})
    void repairsMockitoVerificationFailuresButKeepsValueAssertions(String type) {
        producesReport = true;
        casesPerRun = List.of(assertionCase("value") + "<testcase name=\"verify\" classname=\"demo.ShopTest\">"
                + "<failure type=\"org.mockito.exceptions.verification." + type + "\">wanted</failure></testcase>",
                assertionCase("value"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        verify(units).repairFailing(eq(1L), org.mockito.ArgumentMatchers.argThat(failures ->
                failures.size() == 1 && failures.get(0).methodName().equals("verify") && !failures.get(0).assertion()));
        assertThat(service.state(1L).message()).contains("ShopTest#value", "AI không tự sửa");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"java.lang.AssertionError","org.opentest4j.AssertionFailedError","junit.framework.ComparisonFailure"})
    void neverRepairsValueAssertionFailures(String type) {
        producesReport=true;
        casesPerRun=List.of(assertionCase("value").replace("org.opentest4j.AssertionFailedError",type));
        service.start(1L,null,7L);
        verify(units,never()).repairFailing(any(),any());
        verify(units,never()).removeUncompilable(any(),any());
        assertThat(service.state(1L).message()).contains("ShopTest#value", "AI không tự sửa");
    }

    @Test
    void repairsMockitoExceptionWrappedByJUnitButKeepsDomainExceptionsAndOracleFailures() {
        producesReport = true;
        String wrapped = "<testcase name=\"fixture\" classname=\"demo.ShopTest\">"
                + "<failure type=\"org.opentest4j.AssertionFailedError\" message=\"Unexpected exception thrown: org.mockito.exceptions.misusing.PotentialStubbingProblem\">"
                + "at org.junit.jupiter.api.AssertDoesNotThrow.assertDoesNotThrow(AssertDoesNotThrow.java:51)\n"
                + "Caused by: org.mockito.exceptions.misusing.PotentialStubbingProblem"
                + "</failure></testcase>";
        String domain = wrapped.replace("fixture", "domain")
                .replace("org.mockito.exceptions.misusing.PotentialStubbingProblem", "demo.DomainConflict");
        casesPerRun = List.of(wrapped + domain + assertionCase("value"), domain + assertionCase("value"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        service.start(1L, null, 7L);
        verify(units).repairFailing(eq(1L), org.mockito.ArgumentMatchers.argThat(failures ->
                failures.size() == 1 && failures.get(0).methodName().equals("fixture") && !failures.get(0).assertion()));
        assertThat(service.state(1L).message()).contains("ShopTest#value", "ShopTest#domain", "AI không tự sửa");
    }

    @Test
    void repairsMockitoWantedButNotInvokedWhenSurefireReportsItsTitleAsType() {
        producesReport = true;
        String failure = "<testcase name=\"verify\" classname=\"demo.ShopTest\">"
                + "<failure type=\"Wanted but not invoked\" message=\"Wanted but not invoked: cache.get(n); "
                + "However, there was exactly 1 interaction with this mock:\">wanted</failure></testcase>";
        casesPerRun = List.of(failure + assertionCase("value"), assertionCase("value"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        service.start(1L, null, 7L);
        verify(units).repairFailing(eq(1L), org.mockito.ArgumentMatchers.argThat(failures ->
                failures.size() == 1 && failures.get(0).methodName().equals("verify") && !failures.get(0).assertion()));
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(sourceRoot.resolve("pom.xml"), "<project/>");
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.TEST_GENERATED);
        project.setStoragePath(sourceRoot.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(javaVersions.detect(1L)).thenReturn(Optional.of(new ProjectJavaVersionDetector.JavaVersionInfo("21", "pom.xml")));
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(
                zip("src/test/java/demo/ShopTest.java", "class ShopTest {}"), List.of()));
        when(units.exportSandboxArchive(eq(1L), any())).thenAnswer(call -> units.exportArchive(1L, call.getArgument(1)));
        when(jobs.executeMutation(eq(1L), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(jobs.executeAiMutation(eq(1L), eq(7L), any(), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(3).get());
        org.mockito.Mockito.doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(jobs).executeMutation(eq(1L), any(Runnable.class));
        service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, Runnable::run, this::fakeDocker);
    }

    @Test
    void buildsTheSandboxImageFromTheBundledDockerfileWhenItIsMissing() {
        imageMissing = true;
        producesReport = true;
        casesPerRun = List.of("");

        service.start(1L, null);

        assertThat(service.state(1L).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(commands).anyMatch(c -> c.get(0).equals("build") && c.contains("greytest-sandbox:21") && c.contains("JAVA_VERSION=21"));
    }

    @Test
    void runsGeneratedTestsInAnIsolatedContainerAndStoresTheCoverage() {
        startExit = 3; // script báo: có coverage nhưng có test fail
        producesReport = true;

        service.start(1L, null);

        var state = service.state(1L);
        assertThat(state.status()).isEqualTo(Status.TESTS_FAILED);
        assertThat(state.tests()).isEqualTo(3);
        assertThat(state.failed()).isEqualTo(1);
        assertThat(generatedTestReachedContainer).isTrue();
        verify(coverage).upload(eq(1L), eq(null), any());
        List<String> create = commands.stream().filter(c -> c.get(0).equals("create")).findFirst().orElseThrow();
        assertThat(create).contains("create", "--memory", "--pids-limit");
        assertThat(create).anyMatch(arg -> arg.endsWith("21"));
        assertThat(create).contains("--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--read-only", "--user", "1000:1000");
        assertThat(create).contains("--network");
        assertThat(create).contains("-v", "greytest_sandbox_m2_p1:/home/greytest/.m2");
        // Container luôn bị xóa, không để lại rác trên máy chạy demo.
        assertThat(commands.get(commands.size() - 1)).startsWith("rm", "-f");
    }

    @Test
    void reportsTheBuildLogWhenNoCoverageWasProduced() {
        startExit = 1;
        producesReport = false;

        service.start(1L, null);

        var state = service.state(1L);
        assertThat(state.status()).isEqualTo(Status.FAILED);
        assertThat(state.logTail()).contains("COMPILATION ERROR");
        verify(coverage, never()).upload(any(), any(), any());
        assertThat(commands.get(commands.size() - 1)).startsWith("rm", "-f");
    }


    @Test
    void letsAiRepairTheFailingTestsAndRunsThemAgain() {
        startExit = 3;
        producesReport = true;
        casesPerRun = List.of(errorCase("rejectsEmptyCart"), ""); // lượt chạy lại sau khi AI sửa thì đạt
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnitTestService.TestFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(units).repairFailing(eq(1L), failures.capture());
        assertThat(failures.getValue()).singleElement().satisfies(failure -> {
            assertThat(failure.className()).isEqualTo("demo.ShopTest");
            assertThat(failure.methodName()).isEqualTo("rejectsEmptyCart");
            assertThat(failure.detail()).contains("NullPointerException", "Shop.java:12");
            assertThat(failure.assertion()).isFalse();
        });
        var state = service.state(1L);
        assertThat(state.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(state.message()).contains("AI đã sửa 1");
        verify(units, never()).restore(any(), any());
    }

    @Test
    void restoresThePreviousTestsWhenTheRepairedOnesBreakTheBuild() {
        startExit = 3;
        producesReport = true;
        reportRuns = 1; // bản AI sửa làm hỏng build: lượt chạy lại không có báo cáo
        List<UnitTest> previous = List.of(new UnitTest());
        when(units.repairFailing(eq(1L), any())).thenReturn(previous);

        service.start(1L, null, 7L);

        verify(units).restore(1L, previous);
        var state = service.state(1L);
        assertThat(state.status()).isEqualTo(Status.TESTS_FAILED);
        assertThat(state.message()).contains("khôi phục");
    }


    @Test
    void stopsRepairingWhenARoundDoesNotReduceTheFailures() {
        startExit = 3;
        producesReport = true; // mọi lượt chạy vẫn còn 1 test fail
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        // Không giảm được số test fail thì không gọi AI thêm vòng nữa để khỏi tốn quota.
        verify(units, org.mockito.Mockito.times(1)).repairFailing(eq(1L), any());
        assertThat(service.state(1L).status()).isEqualTo(Status.TESTS_FAILED);
        assertThat(service.state(1L).message()).contains("Số lỗi kỹ thuật không giảm");
    }

    @Test
    void restoresThePreviousTestsWhenARepairAddsTechnicalErrors() {
        startExit = 3;
        producesReport = true;
        // Bản sửa 1 test làm 3 test khác cùng lớp lỗi theo: trả lại bản cũ và chạy lại để kết quả cuối không tệ hơn.
        casesPerRun = List.of(errorCase("a"), errorCase("a") + errorCase("b") + errorCase("c"), errorCase("a"));
        List<UnitTest> previous = List.of(new UnitTest());
        when(units.repairFailing(eq(1L), any())).thenReturn(previous);

        service.start(1L, null, 7L);

        verify(units).restore(1L, previous);
        verify(units, org.mockito.Mockito.times(1)).repairFailing(eq(1L), any());
        assertThat(runs).isEqualTo(3);
        assertThat(service.state(1L).message()).contains("làm tăng lỗi kỹ thuật", "khôi phục").doesNotContain("AI đã sửa");
    }

    @Test
    void capsRepairAtTwoRoundsEvenWhenTechnicalErrorsKeepDropping() {
        startExit = 3;
        producesReport = true;
        casesPerRun = List.of(errorCase("a") + errorCase("b") + errorCase("c"),
                errorCase("a") + errorCase("b"), errorCase("a"), "");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        verify(units, org.mockito.Mockito.times(2)).repairFailing(eq(1L), any());
        assertThat(runs).isEqualTo(3);
        assertThat(service.state(1L).status()).isEqualTo(Status.TESTS_FAILED);
        assertThat(service.state(1L).message()).contains("Đã đạt giới hạn 2 vòng");
    }

    @Test
    void replaysSavedNexusFailuresWithoutCallingAiOrDocker() throws Exception {
        // Fixture rút từ Surefire của lượt NEXUS chẩn đoán ngày 19/11, chỉ giữ tên và lỗi.
        String report = Files.readString(Path.of(getClass().getResource("/fixtures/nexus-debug-failures.xml").toURI()));
        String cases = report.replaceFirst("(?s)^<testsuite[^>]*>", "").replace("</testsuite>", "");
        startExit = 3;
        producesReport = true;
        casesPerRun = List.of(cases);
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnitTestService.TestFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(units).repairFailing(eq(1L), failures.capture());
        assertThat(failures.getValue()).hasSize(11).noneMatch(UnitTestService.TestFailure::assertion);
        assertThat(runs).isEqualTo(2);
        assertThat(service.state(1L).message()).contains("Số lỗi kỹ thuật không giảm", "19 test cho kết quả khác mong đợi");
    }
    @Test
    void doesNotCallAiUnlessTheUserAskedForRepair() {
        startExit = 3;
        producesReport = true;

        service.start(1L, null);

        verify(units, never()).repairFailing(any(), any());
    }
    @Test
    void sendsOnlyTechnicalErrorsToTheAiAndNeverTouchesAssertionFailures() {
        startExit = 3;
        producesReport = true;
        // Testcase có cả <failure> lẫn <error> được coi là lỗi kỹ thuật.
        casesPerRun = List.of(assertionCase("chargesFullPrice") + errorCase("rejectsEmptyCart") + bothCase("appliesCoupon"),
                assertionCase("chargesFullPrice"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest(), new UnitTest()));

        service.start(1L, null, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnitTestService.TestFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(units).repairFailing(eq(1L), failures.capture());
        assertThat(failures.getValue()).extracting(UnitTestService.TestFailure::methodName)
                .containsExactly("rejectsEmptyCart", "appliesCoupon");
        assertThat(failures.getValue()).noneMatch(UnitTestService.TestFailure::assertion);
        var state = service.state(1L);
        assertThat(state.status()).isEqualTo(Status.TESTS_FAILED);
        assertThat(state.message()).contains("AI đã sửa 2", "1 test cho kết quả khác mong đợi", "ShopTest#chargesFullPrice",
                "Hãy kiểm tra lại", "AI không tự sửa");
        assertThat(state.message()).doesNotContain("ShopTest#rejectsEmptyCart", "ShopTest#appliesCoupon");
    }

    @Test
    void doesNotStartARepairRoundWhenEveryFailureIsAnAssertion() {
        startExit = 3;
        producesReport = true;
        casesPerRun = List.of(assertionCase("chargesFullPrice") + assertionCase("keepsStock"));

        service.start(1L, null, 7L);

        verify(units, never()).repairFailing(any(), any());
        String message = service.state(1L).message();
        assertThat(message).contains("Không có lỗi kỹ thuật nào để AI sửa", "2 test cho kết quả khác mong đợi",
                "ShopTest#chargesFullPrice", "ShopTest#keepsStock");
        assertThat(message).doesNotContain("AI chưa sửa được");
    }

    @Test
    void keepsRepairingWhileTechnicalErrorsDropEvenIfAssertionFailuresStay() {
        startExit = 3;
        producesReport = true;
        String assertion = assertionCase("chargesFullPrice");
        casesPerRun = List.of(errorCase("a") + errorCase("b") + assertion, errorCase("a") + assertion, assertion);
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnitTestService.TestFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(units, org.mockito.Mockito.times(2)).repairFailing(eq(1L), failures.capture());
        assertThat(failures.getAllValues()).allSatisfy(round -> assertThat(round).noneMatch(UnitTestService.TestFailure::assertion));
        assertThat(service.state(1L).message()).contains("AI đã sửa 2", "ShopTest#chargesFullPrice")
                .doesNotContain("AI chưa sửa được");
    }

    @Test
    void stopsRepairingWhenTechnicalErrorsDoNotDropEvenIfAssertionFailuresDo() {
        startExit = 3;
        producesReport = true;
        casesPerRun = List.of(errorCase("a") + assertionCase("x") + assertionCase("y"), errorCase("a") + assertionCase("x"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        verify(units, org.mockito.Mockito.times(1)).repairFailing(eq(1L), any());
    }

    @Test
    void reportsAssertionFailuresAsSuspectedBusinessMismatchesWithoutRepair() {
        startExit = 3;
        producesReport = true;
        casesPerRun = List.of(errorCase("e1") + errorCase("e2") + assertionCase("a1") + assertionCase("a2") + assertionCase("a3"));

        service.start(1L, null);

        String message = service.state(1L).message();
        assertThat(message).contains("Đã chạy 7 test: 2 đạt, 5 không đạt.", "3 test cho kết quả khác mong đợi",
                "ShopTest#a1, ShopTest#a2, ShopTest#a3", "chưa đúng Business Rule", "Hãy kiểm tra lại", "AI không tự sửa");
        assertThat(message).doesNotContain("ShopTest#e1", "AI đã sửa", "AI chưa sửa");
    }

    @Test
    void capsTheListedAssertionFailureNames() {
        startExit = 3;
        producesReport = true;
        StringBuilder cases = new StringBuilder();
        for (int index = 1; index <= 7; index++) cases.append(assertionCase("a" + index));
        casesPerRun = List.of(cases.toString());

        service.start(1L, null);

        String message = service.state(1L).message();
        assertThat(message).contains("7 test cho kết quả khác mong đợi", "ShopTest#a5 … và 2 test khác");
        assertThat(message).doesNotContain("ShopTest#a6", "ShopTest#a7");
    }

    @Test
    void refusesAModulePathThatEscapesTheProjectSource() {
        service.start(1L, "../other-project");

        assertThat(service.state(1L).status()).isEqualTo(Status.FAILED);
        assertThat(commands).isEmpty();
    }

    @Test
    void repairsCompilerErrorsInTheRightMethodOfAMergedFile() throws IOException {
        compilationArchive();
        producesReport = true;
        firstReportRun = 2;
        casesPerRun = List.of("");
        logsPerRun = List.of(compileError(8, "cannot find symbol")
                + "[ERROR]   symbol:   method del()\n[ERROR]   location: variable shop of type Shop\n"
                + compileError(8, "cannot find symbol"), "ok");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnitTestService.TestFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(units).repairFailing(eq(1L), failures.capture());
        assertThat(failures.getValue()).singleElement().satisfies(failure -> {
            assertThat(failure.className()).isEqualTo("demo.ShopTest");
            assertThat(failure.methodName()).isEqualTo("second");
            assertThat(failure.assertion()).isFalse();
            assertThat(failure.detail()).startsWith("COMPILATION ERROR:")
                    .contains("line 8:", "cannot find symbol", "symbol:", "location:", "shop.del();");
        });
        assertThat(service.state(1L).message()).isEqualTo("AI đã sửa 1 lượt test qua 1 vòng. Đã chạy 2 test: 2 đạt, 0 không đạt. Coverage đã được cập nhật.");
        verify(units, never()).restore(any(), any());
        assertThat(commands.stream().filter(command -> command.get(0).equals("create"))).hasSize(1);
        assertThat(commands.stream().filter(command -> command.get(0).equals("exec") && command.contains("cp") && command.contains("/work/"))).hasSize(2);
        var executions = commands.stream().filter(command -> command.get(0).equals("exec") && !command.contains("cp")).toList();
        assertThat(executions).hasSize(2);
        assertThat(executions.get(0)).doesNotContain("GREYTEST_FAST_RERUN=1", "-o");
        assertThat(executions.get(1)).contains("GREYTEST_FAST_RERUN=1", "-o");
        assertThat(commands.get(commands.size() - 1)).startsWith("rm", "-f");
    }

    @Test
    void attributesSharedCompilerErrorsToEveryTestAndStopsWhenNotImproving() throws IOException {
        compilationArchive();
        logsPerRun = List.of(compileError(3, "cannot find symbol"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest(), new UnitTest()));

        service.start(1L, null, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnitTestService.TestFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(units).repairFailing(eq(1L), failures.capture());
        assertThat(failures.getValue()).extracting(UnitTestService.TestFailure::methodName).containsExactly("first", "second");
        assertThat(service.state(1L).status()).isEqualTo(Status.FAILED);
        assertThat(service.state(1L).message()).isEqualTo("AI đã sửa 2 lượt test qua 1 vòng. Còn 2 test không biên dịch được: ShopTest#first, ShopTest#second. Số lỗi kỹ thuật không giảm; dừng tự sửa.");
        verify(units, never()).restore(any(), any());
        verify(units, never()).removeUncompilable(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"src/main/java/demo/Shop.java", "src/test/java/demo/OwnTest.java"})
    void doesNotRepairCompilationErrorsInTheProjectsOwnFiles(String path) throws IOException {
        compilationArchive();
        logsPerRun = List.of("[ERROR] /work/" + path + ":[12,4] cannot find symbol\n" + compileError(8, "bad stub"));

        service.start(1L, null, 7L);

        assertThat(service.state(1L).status()).isEqualTo(Status.FAILED);
        assertThat(service.state(1L).message()).isEqualTo("Source của project không biên dịch được: /work/" + path + ":12: cannot find symbol");
        verify(units, never()).repairFailing(any(), any());
    }

    @Test
    void retriesMissingOfflineArtifactsOnlineOnce() {
        producesReport = true;
        firstReportRun = 1;
        casesPerRun = List.of(errorCase("first"), "", "");
        logsPerRun = List.of("ok", "[ERROR] Cannot access central in offline mode and the artifact x:y:jar:1 has not been downloaded from it before.", "ok");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        var executions = commands.stream().filter(command -> command.get(0).equals("exec") && !command.contains("cp")).toList();
        assertThat(executions).hasSize(3);
        assertThat(executions.get(1)).contains("GREYTEST_FAST_RERUN=1", "-o");
        assertThat(executions.get(2)).contains("GREYTEST_FAST_RERUN=1").doesNotContain("-o");
        assertThat(service.state(1L).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void disconnectsTheNetworkOnlyOnceAcrossConsecutiveOfflineRepairs() {
        producesReport = true;
        casesPerRun = List.of(errorCase("first") + errorCase("second"), errorCase("first"), "");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, null, 7L);

        assertThat(service.state(1L).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(commands.stream().filter(command -> command.contains("-o"))).hasSize(2);
        assertThat(commands.stream().filter(command -> command.contains("disconnect"))).hasSize(1);
    }

    @Test
    void removesTheSingleContainerWhenAnAttemptThrows() {
        throwsOnRun = true;
        service.start(1L, null);
        assertThat(service.state(1L).status()).isEqualTo(Status.FAILED);
        assertThat(commands.stream().filter(command -> command.get(0).equals("create"))).hasSize(1);
        assertThat(commands.get(commands.size() - 1)).startsWith("rm", "-f");
    }

    @Test
    void removesTheSingleContainerOnCommandTimeout() {
        timeoutRun = 1;
        service.start(1L, null);
        assertThat(service.state(1L).status()).isEqualTo(Status.FAILED);
        assertThat(service.state(1L).message()).contains("quá thời gian");
        assertThat(commands.get(commands.size() - 1)).startsWith("rm", "-f");
    }

    @Test
    void removesObsoleteGeneratedFilesAndOnlyCopiesTheModuleExportOnRerun() throws IOException {
        Files.createDirectories(sourceRoot.resolve("shop"));
        Files.writeString(sourceRoot.resolve("shop/pom.xml"), "<project/>");
        when(units.exportArchive(eq(1L), any())).thenReturn(
                new UnitTestService.ExportArchive(zip("src/test/java/demo/OldTest.java", "class OldTest {}"), List.of()),
                new UnitTestService.ExportArchive(zip("src/test/java/demo/ShopTest.java", "class ShopTest {}"), List.of()));
        producesReport = true;
        casesPerRun = List.of(errorCase("first"), "");
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));

        service.start(1L, "shop", 7L);

        assertThat(commands.stream().filter(command -> command.contains("rm") && command.get(0).equals("exec")))
                .singleElement().satisfies(command -> assertThat(command).contains("--", "/work/shop/src/test/java/demo/OldTest.java"));
        assertThat(commands.stream().filter(command -> command.get(0).equals("cp") && command.get(2).contains(":/stage/")))
                .hasSize(2).anySatisfy(command -> assertThat(command.get(1)).contains("export"));
        // Người dùng sandbox (UID 1000) tự copy nên sở hữu file và ghi được vào thư mục module.
        assertThat(commands.stream().filter(command -> command.get(0).equals("exec") && command.contains("cp")))
                .hasSize(2).anySatisfy(command -> assertThat(command).endsWith("/work/shop/"));
        assertThat(service.state(1L).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void doesNotRetryOfflineForCompilerErrors() throws IOException {
        compilationArchive();
        logsPerRun = List.of(compileError(8, "no suitable method found for thenReturn(boolean)"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        service.start(1L, null, 7L);
        assertThat(commands.stream().filter(command -> command.get(0).equals("exec") && !command.contains("cp"))).hasSize(2);
    }

    @Test
    void recognizesGradleJavacDiagnosticsWithModulePathsAndCapsDetail() throws IOException {
        compilationArchive();
        byte[] archive = units.exportArchive(1L, null).content();
        var result = SandboxCompilationFailures.parse("\u001b[31m/work/shop/src/test/java/demo/ShopTest.java:8: error: reference to del is ambiguous\u001b[0m\n"
                + "  symbol: " + "x".repeat(4000), archive, "/work/shop", 3000);
        assertThat(result.projectError()).isNull();
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.methodName()).isEqualTo("second");
            assertThat(failure.detail()).hasSize(3000).contains("reference to del is ambiguous", "symbol:");
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 10})
    void sharedErrorsOnlyAffectTestsOfTheirOwnClass(int line) throws IOException {
        String source = """
                package demo;
                class ShopTest {
                  Missing shop;
                  @org.junit.jupiter.api.Test void first() {
                    shop.ok();
                  }
                  @org.junit.jupiter.api.Test void second() {
                    shop.del();
                  }
                  void helper() { missing(); }
                }
                class OtherTest { @org.junit.jupiter.api.Test void other() {} }
                """;
        var result = SandboxCompilationFailures.parse(compileError(line, "cannot find symbol"),
                zip("src/test/java/demo/ShopTest.java", source), "/work", 3000);
        assertThat(result.failures()).extracting(UnitTestService.TestFailure::methodName).containsExactly("first", "second");
    }

    @Test
    void attributesImportAndBeforeEachErrorsToAllTestMethods() throws IOException {
        String source = """
                package demo;
                import missing.Type;
                class ShopTest {
                  @org.junit.jupiter.api.BeforeEach void setup() { missing(); }
                  @org.junit.jupiter.api.Test void first() {}
                  @org.junit.jupiter.api.Test void second() {}
                }
                """;
        var result = SandboxCompilationFailures.parse(compileError(2, "package missing does not exist") + compileError(4, "cannot find symbol"),
                zip("src/test/java/demo/ShopTest.java", source), "/work", 3000);
        assertThat(result.failures()).extracting(UnitTestService.TestFailure::methodName).containsExactly("first", "second");
        assertThat(result.failures()).allSatisfy(failure -> assertThat(failure.detail()).contains("line 2:", "line 4:", "missing.Type", "missing();"));
    }

    @Test
    void capsCompilationFailureNamesAfterRepair() throws IOException {
        String source = "package demo;\nclass ShopTest {\n Missing fixture;\n"
                + java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(i -> "@org.junit.jupiter.api.Test void t" + i + "() {}\n")
                        .collect(java.util.stream.Collectors.joining()) + "}";
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(zip("src/test/java/demo/ShopTest.java", source), List.of()));
        logsPerRun = List.of(compileError(3, "cannot find symbol"));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new UnitTest()));
        service.start(1L, null, 7L);
        assertThat(service.state(1L).message()).contains("7 test không biên dịch được", "ShopTest#t5 … và 2 test khác")
                .doesNotContain("ShopTest#t6", "ShopTest#t7");
    }

    private void compilationArchive() throws IOException {
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(zip("src/test/java/demo/ShopTest.java", """
                package demo;
                class ShopTest {
                  Missing shop;
                  @org.junit.jupiter.api.Test void first() {
                    shop.ok();
                  }
                  @org.junit.jupiter.api.Test void second() {
                    shop.del();
                  }
                  void helper() {}
                }
                """), List.of()));
    }

    private static String compileError(int line, String message) {
        return "[ERROR] /work/src/test/java/demo/ShopTest.java:[" + line + ",5] " + message + "\n";
    }

    private final java.util.Set<String> disconnected = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean imageMissing;

    private SandboxTestRunService.Result fakeDocker(List<String> args, Path log) throws IOException {
        commands.add(args);
        if (dockerUnavailable) throw new IOException("Docker không khả dụng.");
        if (args.get(0).equals("create")) {
            containers.add(args.get(args.indexOf("--name") + 1));
            maxContainers = Math.max(maxContainers, containers.size());
            if(createExit!=0)return new SandboxTestRunService.Result(createExit);
        }
        if (args.get(0).equals("rm")) containers.remove(args.get(2));
        if (executionEntered != null && args.get(0).equals("exec") && !args.contains("rm") && !args.contains("cp")) {
            executionEntered.countDown();
            try {
                if (!executionRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timed out");
            } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
        }
        if (args.get(0).equals("exec") && args.get(args.size() - 1).contains("-q compile")) {
            warms++;
            if(throwsOnWarm) throw new IOException("warm failed");
            return new SandboxTestRunService.Result(warmExit);
        }
        switch (args.get(0)) {
            case "exec" -> {
                if (args.contains("rm") || args.contains("cp")) return new SandboxTestRunService.Result(0);
                runs++;
                if (throwsOnRun) throw new IOException("fake timeout");
                String output = logsPerRun.get(Math.min(runs, logsPerRun.size()) - 1);
                Files.writeString(log, output);
                if (runs == timeoutRun) return new SandboxTestRunService.Result(-1);
                return new SandboxTestRunService.Result(output.contains("offline mode") ? 1 : startExit);
            }
            case "cp" -> {
                String from = args.get(1), to = args.get(2);
                if (to.contains(":/stage/")) { // host -> container
                    generatedTestReachedContainer |= Files.exists(Path.of(from).resolve("src/test/java/demo/ShopTest.java"))
                            && Files.exists(Path.of(from).resolve("pom.xml"));
                } else if (!producesReport || runs > reportRuns || runs < firstReportRun) {
                    return new SandboxTestRunService.Result(1);
                } else if (from.endsWith("jacoco.xml")) {
                    Files.writeString(Path.of(to), "<report/>");
                } else {
                    Files.createDirectories(Path.of(to));
                    String cases = casesPerRun.get(Math.min(runs, casesPerRun.size()) - 1);
                    int failed = cases.split("<testcase ", -1).length - 1; // luôn có thêm 2 test đạt
                    Files.writeString(Path.of(to).resolve("TEST-demo.ShopTest.xml"),
                            "<testsuite name=\"demo.ShopTest\" tests=\"" + (failed + 2) + "\" errors=\"0\" skipped=\"0\" failures=\""
                                    + failed + "\">" + cases + "</testsuite>");
                }
            }
            case "network" -> {
                // Docker thật báo lỗi khi ngắt mạng một container đã ngắt.
                if (args.get(1).equals("disconnect") && !disconnected.add(args.get(3)))
                    return new SandboxTestRunService.Result(1);
                if (args.get(1).equals("connect")) disconnected.remove(args.get(3));
            }
            case "image" -> { return new SandboxTestRunService.Result(imageMissing ? 1 : 0); }
            default -> { }
        }
        return new SandboxTestRunService.Result(0);
    }

    private static String assertionCase(String method) {
        return "<testcase name=\"" + method + "\" classname=\"demo.ShopTest\">"
                + "<failure message=\"expected: &lt;100&gt; but was: &lt;90&gt;\" type=\"org.opentest4j.AssertionFailedError\">"
                + "org.opentest4j.AssertionFailedError\n\tat demo.ShopTest." + method + "(ShopTest.java:30)</failure></testcase>";
    }

    private static String errorCase(String method) {
        return "<testcase name=\"" + method + "\" classname=\"demo.ShopTest\">" + errorElement() + "</testcase>";
    }

    private static String bothCase(String method) {
        return "<testcase name=\"" + method + "\" classname=\"demo.ShopTest\">"
                + "<failure message=\"verify\" type=\"org.mockito.exceptions.verification.WantedButNotInvoked\">wanted</failure>"
                + errorElement() + "</testcase>";
    }

    private static String errorElement() {
        return "<error message=\"boom\" type=\"java.lang.NullPointerException\">"
                + "java.lang.NullPointerException\n\tat demo.Shop.checkout(Shop.java:12)</error>";
    }

    private static byte[] zip(String name, String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(content.getBytes());
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
