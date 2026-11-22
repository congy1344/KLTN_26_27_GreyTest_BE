package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Sửa máy móc, giữ nguyên ý nghĩa test, để không phải gọi AI sinh lại chỉ vì thiếu import/annotation. */
class UnitTestAutoFixerTest {

    @Test void removesOnlyTheReportedUnusedStubsFromTheFailingTest() {
        String source = """
                package demo;
                class FooTest {
                    @Test void works() {
                        Item item = new Item();
                        when(repo.find(1L))
                                .thenReturn(item);
                        doReturn(2).when(repo).count();
                        when(repo.name()).thenReturn("a");
                        assertEquals(1, service.run());
                    }
                }
                """;
        String fixed = UnitTestAutoFixer.removeUnusedStubs(source, "works",
                java.util.List.of("when(repo.find(1L))", "doReturn(2).when(repo).count();"));
        assertThat(fixed).doesNotContain("repo.find(1L)", "doReturn(2)")
                .contains("Item item = new Item();", "when(repo.name()).thenReturn(\"a\");", "assertEquals(1, service.run());");
    }

    @Test void removesUnusedStubsDeclaredInTheSetupOfTheSingleStoredTest() {
        String source = """
                class FooTest {
                    @BeforeEach void setUp() {
                        when(clock.instant()).thenReturn(NOW);
                        service = new Service(clock);
                    }
                    @Test void works() { assertEquals(1, service.run()); }
                }
                """;
        assertThat(UnitTestAutoFixer.removeUnusedStubs(source, "works", java.util.List.of("when(clock.instant()).thenReturn(NOW);")))
                .doesNotContain("clock.instant()").contains("service = new Service(clock);", "assertEquals(1, service.run());");
    }

    @Test void keepsTheTestForAiWhenAReportedLineIsNotExactlyOneStub() {
        String source = "class FooTest { @Test void works() { assertEquals(1, service.run()); when(a.b()).thenReturn(1); } }";
        assertThat(UnitTestAutoFixer.removeUnusedStubs(source, "works", java.util.List.of("assertEquals(1, service.run());"))).isNull();
        assertThat(UnitTestAutoFixer.removeUnusedStubs(source, "missing", java.util.List.of("when(a.b()).thenReturn(1);"))).isNull();
        assertThat(UnitTestAutoFixer.removeUnusedStubs(source, "works", java.util.List.of())).isNull();
    }

    @Test void addsMissingStaticImportsForMockitoAndAssertions() {
        String source = """
                package demo;

                import org.junit.jupiter.api.Test;

                class FooTest {
                    @Test void works() {
                        when(repo.find(anyLong())).thenReturn(null);
                        assertEquals(1, 1);
                        verify(repo, never()).save(any());
                    }
                }
                """;
        String fixed = UnitTestAutoFixer.fix(source);
        assertThat(fixed).contains("import static org.mockito.Mockito.when;", "import static org.mockito.Mockito.verify;",
                "import static org.mockito.Mockito.never;", "import static org.mockito.ArgumentMatchers.anyLong;",
                "import static org.mockito.ArgumentMatchers.any;", "import static org.junit.jupiter.api.Assertions.assertEquals;");
        assertThat(fixed).contains("when(repo.find(anyLong())).thenReturn(null);");
    }

    @Test void keepsExistingImportsAndLocalHelpersUntouched() {
        String source = """
                package demo;

                import static org.mockito.Mockito.*;

                class FooTest {
                    void run() { when(x).thenReturn(y); assertEquals(1, 1); }
                    private void assertEquals(int a, int b) { }
                }
                """;
        assertThat(UnitTestAutoFixer.fix(source)).isEqualTo(source);
    }

    @Test void treatsMockitoWildcardAsCoveringArgumentMatchers() {
        // Mockito kế thừa ArgumentMatchers nên import static Mockito.* đã đủ cho any(), eq()...
        String source = "package demo;\nimport static org.mockito.Mockito.*;\nclass FooTest { void run() { when(x.find(any())).thenReturn(eq(1)); } }\n";
        assertThat(UnitTestAutoFixer.fix(source)).isEqualTo(source);
    }

    @Test void marksAnInitializedClockFieldAsSpySoInjectMocksUsesIt() {
        String source = """
                package demo;

                import java.time.Clock;
                import org.mockito.InjectMocks;

                class FooTest {
                    private Clock clock = Clock.systemUTC();
                    private String name = "x";
                    @InjectMocks private FooService service;
                }
                """;
        String fixed = UnitTestAutoFixer.fix(source);
        assertThat(fixed).contains("import org.mockito.Spy;").containsPattern("@Spy\\s+private Clock clock = Clock.systemUTC\\(\\);");
        assertThat(fixed).doesNotContainPattern("@Spy\\s+private String name");
    }

    @Test void leavesUnparseableSourceAsIs() {
        assertThat(UnitTestAutoFixer.fix("class Broken {")).isEqualTo("class Broken {");
    }
}
