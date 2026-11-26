package com.greytest.service.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.greytest.dto.UnitTestFileDto;
import com.greytest.entity.Project;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.UnitTestFileService;
import com.greytest.service.UnitTestService;
import com.greytest.service.agent.ProjectJavaVersionDetector;
import com.greytest.service.coverage.SandboxTestRunService.Status;

/**
 * Kiểm chứng với Docker thật: tạo container, chạy script coverage trên một project Maven thật, lấy jacoco.xml về.
 * Chỉ chạy khi đặt GREYTEST_SANDBOX_SOURCE trỏ tới thư mục project (cần Docker và mạng), ví dụ source-test/course-registration.
 */
@EnabledIfEnvironmentVariable(named = "GREYTEST_SANDBOX_SOURCE", matches = ".+")
class SandboxTestRunDockerIT {
    @Test
    @SuppressWarnings("unchecked")
    void runsARealMavenProjectInsideTheSandbox() {
        Path source = Path.of(System.getenv("GREYTEST_SANDBOX_SOURCE"));
        var testFile = new UnitTestFileDto("src/test/java/greytestcheck/SandboxSmokeTest.java", "SandboxSmokeTest", "greytestcheck", 1,
                List.of("TC-001"), "package greytestcheck;\nclass SandboxSmokeTest { @org.junit.jupiter.api.Test void runs() { "
                        + "org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); } }\n");
        byte[] archive = new UnitTestFileService().createCoverageArchive(List.of(testFile), source);

        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);
        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(source.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(javaVersions.detect(1L)).thenReturn(Optional.of(new ProjectJavaVersionDetector.JavaVersionInfo("17", "pom.xml")));
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(archive, List.of()));
        when(jobs.executeMutation(eq(1L), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        var service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, Runnable::run,
                SandboxTestRunService::dockerCli);

        service.start(1L, null);

        var state = service.state(1L);
        System.out.println(state.message() + "\n" + state.logTail());
        assertThat(state.status()).as(state.message() + "\n" + state.logTail()).isEqualTo(Status.SUCCEEDED);
        assertThat(state.tests()).isEqualTo(1);
        verify(coverage).upload(eq(1L), eq(null), any());
    }

    /** Lượt đầu test không biên dịch được; bản "AI sửa" (giả lập) phải chạy lại nhanh trong cùng container và đạt. */
    @Test
    @SuppressWarnings("unchecked")
    void repairsACompilationErrorAndRerunsInTheSameContainer() {
        Path source = Path.of(System.getenv("GREYTEST_SANDBOX_SOURCE"));
        String header = "package greytestcheck;\nclass SandboxSmokeTest { @org.junit.jupiter.api.Test void runs() { ";
        byte[] broken = archive(source, header + "int value = \"not a number\"; } }\n");
        byte[] fixed = archive(source, header + "org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); } }\n");

        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);
        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(source.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(javaVersions.detect(1L)).thenReturn(Optional.of(new ProjectJavaVersionDetector.JavaVersionInfo("17", "pom.xml")));
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(broken, List.of()),
                new UnitTestService.ExportArchive(fixed, List.of()));
        List<UnitTestService.TestFailure> sentToAi = new java.util.ArrayList<>();
        when(units.repairFailing(eq(1L), any())).thenAnswer(call -> {
            sentToAi.addAll(call.<List<UnitTestService.TestFailure>>getArgument(1));
            return List.of(new com.greytest.entity.UnitTest());
        });
        when(jobs.executeMutation(eq(1L), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(jobs.executeAiMutation(eq(1L), eq(7L), any(), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(3).get());
        var service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, Runnable::run,
                SandboxTestRunService::dockerCli);

        long started = System.nanoTime();
        service.start(1L, null, 7L);
        System.out.println("TOTAL_SECONDS=" + (System.nanoTime() - started) / 1_000_000_000.0);

        var state = service.state(1L);
        System.out.println(state.message() + "\n" + state.logTail());
        System.out.println("SENT_TO_AI=" + sentToAi);
        assertThat(sentToAi).singleElement().satisfies(failure -> {
            assertThat(failure.className()).isEqualTo("greytestcheck.SandboxSmokeTest");
            assertThat(failure.methodName()).isEqualTo("runs");
            assertThat(failure.detail()).startsWith("COMPILATION ERROR:").contains("incompatible types");
            assertThat(failure.assertion()).isFalse();
        });
        assertThat(state.status()).as(state.message() + "\n" + state.logTail()).isEqualTo(Status.SUCCEEDED);
        assertThat(state.tests()).isEqualTo(1);
    }

    /** Luồng tự xác minh sau khi sinh: container được chuẩn bị sẵn trong lúc "AI sinh", rồi sửa lỗi biên dịch và chạy lại. */
    @Test
    @SuppressWarnings("unchecked")
    void verifiesGeneratedTestsInAPrewarmedContainer() throws Exception {
        Path source = Path.of(System.getenv("GREYTEST_SANDBOX_SOURCE"));
        String header = "package greytestcheck;\nclass SandboxSmokeTest { @org.junit.jupiter.api.Test void runs() { ";
        byte[] broken = archive(source, header + "int value = \"not a number\"; } }\n");
        byte[] fixed = archive(source, header + "org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); } }\n");

        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);
        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(source.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(javaVersions.detect(1L)).thenReturn(Optional.of(new ProjectJavaVersionDetector.JavaVersionInfo("17", "pom.xml")));
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(broken, List.of()),
                new UnitTestService.ExportArchive(fixed, List.of()));
        when(units.repairFailing(eq(1L), any())).thenReturn(List.of(new com.greytest.entity.UnitTest()));
        when(jobs.executeMutation(eq(1L), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(jobs.executeAiMutation(eq(1L), eq(7L), any(), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(3).get());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, executor, SandboxTestRunService::dockerCli);
        List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        var warmed = new java.util.concurrent.CountDownLatch(1);
        long started = System.nanoTime();
        long[] generatedAt = new long[1];
        com.greytest.service.GenerationJobContext.bind(7L, line -> {
            logs.add(line);
            if (line.startsWith("Sandbox đã sẵn sàng") || line.startsWith("Không chuẩn bị trước được")) warmed.countDown();
        });
        try {
            service.generateAndVerify(1L, null, false, () -> {
                // Giả lập thời gian AI sinh: chờ tới khi sandbox báo đã chuẩn bị xong.
                try { warmed.await(5, java.util.concurrent.TimeUnit.MINUTES); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                generatedAt[0] = System.nanoTime();
                return true;
            });
        } finally {
            com.greytest.service.GenerationJobContext.clear();
            executor.shutdownNow();
        }
        System.out.println("PREWARM_SECONDS=" + (generatedAt[0] - started) / 1_000_000_000.0);
        System.out.println("VERIFY_AFTER_GENERATION_SECONDS=" + (System.nanoTime() - generatedAt[0]) / 1_000_000_000.0);
        logs.forEach(line -> System.out.println("PROGRESS: " + line.lines().findFirst().orElse("")));

        var state = service.state(1L);
        assertThat(logs).anyMatch(line -> line.startsWith("Sandbox đã sẵn sàng"));
        assertThat(state.status()).as(state.message() + "\n" + state.logTail()).isEqualTo(Status.SUCCEEDED);
        assertThat(state.message()).contains("AI đã sửa 1 lượt test qua 1 vòng");
        assertThat(state.tests()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsAProjectStoredInASubdirectoryModule(@org.junit.jupiter.api.io.TempDir Path wrapper) throws Exception {
        // Project 2113: source nằm trong thư mục con, sandbox phải chép được vào /work/<module> dưới UID 1000.
        Path source = Path.of(System.getenv("GREYTEST_SANDBOX_SOURCE"));
        Path module = wrapper.resolve("app");
        try (var files = java.nio.file.Files.walk(source)) {
            for (Path file : (Iterable<Path>) files.filter(f -> !source.relativize(f).toString().startsWith("target") && !source.relativize(f).toString().startsWith(".git"))::iterator) {
                Path target = module.resolve(source.relativize(file).toString());
                if (java.nio.file.Files.isDirectory(file)) java.nio.file.Files.createDirectories(target);
                else java.nio.file.Files.copy(file, target);
            }
        }
        byte[] archive = archive(module, "package greytestcheck;\nclass SandboxSmokeTest { @org.junit.jupiter.api.Test void runs() { "
                + "org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); } }\n");

        UnitTestService units = mock(UnitTestService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        ProjectJavaVersionDetector javaVersions = mock(ProjectJavaVersionDetector.class);
        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(wrapper.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(javaVersions.detect(1L)).thenReturn(Optional.of(new ProjectJavaVersionDetector.JavaVersionInfo("17", "pom.xml")));
        when(units.exportArchive(eq(1L), any())).thenReturn(new UnitTestService.ExportArchive(archive, List.of()));
        when(jobs.executeMutation(eq(1L), any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        var service = new SandboxTestRunService(units, projects, coverage, jobs, javaVersions, Runnable::run,
                SandboxTestRunService::dockerCli);

        service.start(1L, "app");

        var state = service.state(1L);
        assertThat(state.status()).as(state.message() + "\n" + state.logTail()).isEqualTo(Status.SUCCEEDED);
        assertThat(state.tests()).isEqualTo(1);
    }

    private static byte[] archive(Path source, String testSource) {
        return new UnitTestFileService().createCoverageArchive(List.of(new UnitTestFileDto(
                "src/test/java/greytestcheck/SandboxSmokeTest.java", "SandboxSmokeTest", "greytestcheck", 1, List.of("TC-001"), testSource)), source);
    }
}
