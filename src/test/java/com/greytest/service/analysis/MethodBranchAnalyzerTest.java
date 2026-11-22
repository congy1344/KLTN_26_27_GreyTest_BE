package com.greytest.service.analysis;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.greytest.dto.SourceBranchDto;

class MethodBranchAnalyzerTest {

    @Test
    void extractsTrueAndFalseOutcomesForEveryNestedIfInSourceOrder() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String classify(int score, boolean active) {
                    if (score >= 80) {
                        if (active) {
                            return "ready";
                        }
                        return "inactive";
                    } else {
                        return "low";
                    }
                }
                """, 40);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly("IF-1-TRUE", "IF-1-FALSE", "IF-2-TRUE", "IF-2-FALSE");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::condition)
                .containsExactly("score >= 80", "score >= 80", "active", "active");
        assertThat(branches.get(0).lineStart()).isEqualTo(41);
    }

    @Test
    void outcomeLinesCoverTheBlockButNotTheCommentAboveIt() {
        var branches = MethodBranchAnalyzer.analyze("""
                public void create(boolean valid) {
                    // The decision is documented here.
                    if (valid) {
                        repository.save();
                    } else {
                        throw new IllegalStateException(
                                "invalid");
                    }
                }
                """, 30);

        assertThat(branches.get(0).lineStart()).isEqualTo(32);
        assertThat(branches.get(0).lineEnd()).isEqualTo(34);
        assertThat(branches.get(1).lineStart()).isEqualTo(34);
        assertThat(branches.get(1).lineEnd()).isEqualTo(37);
    }

    @Test
    void parsesLegacyJava8UnderscoreIdentifier() {
        var branches = MethodBranchAnalyzer.analyze("""
                public int _(int _) {
                    if (_ > 0) {
                        return _;
                    }
                    return 0;
                }
                """, 1);

        assertThat(decisionBranches(branches)).hasSize(2);
    }

    @Test
    void exposesTopLevelExecutableStatementsAsSourceAnchors() {
        var branches = MethodBranchAnalyzer.analyze("""
                public Appointment getById(Long id) {
                    Appointment appointment = repository.findById(id)
                            .orElseThrow(() -> new NotFoundException());
                    return enrichAppointmentResponse(appointment);
                }
                """, 100);

        // findById(...).orElseThrow(...) là quyết định Optional; câu return còn lại là anchor câu lệnh.
        assertThat(branches).extracting(SourceBranchDto::branchId)
                .containsExactly("OPTIONAL-1::PRESENT", "OPTIONAL-1::EMPTY", "STMT-1");
        assertThat(branches.get(0).lineStart()).isEqualTo(101);
        assertThat(branches.get(0).lineEnd()).isEqualTo(102);
        assertThat(branches.get(2).lineStart()).isEqualTo(103);
        assertThat(branches.get(2).lineEnd()).isEqualTo(103);
    }

    @Test
    void extractsEveryOutcomeFromJava21PatternSwitch() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String inspect(Object source) {
                    return switch (source) {
                        case String text -> text;
                        case Integer number -> number.toString();
                        default -> "";
                    };
                }
                """, 1);

        assertThat(branches).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "SWITCH-1::CASE-1",
                        "SWITCH-1::CASE-2",
                        "SWITCH-1::DEFAULT");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("String text", "Integer number", "DEFAULT");
    }

    @Test
    void extractsSwitchExpressionAndTernaryDecisionInSourceOrder() {
        var branches = MethodBranchAnalyzer.analyze("""
                public int tax(int amount, String region) {
                    int percent = switch (region) {
                        case "VN" -> 10;
                        case "US", "SG" -> 8;
                        default -> throw new IllegalArgumentException();
                    };
                    return amount == 0 ? 0 : amount * percent / 100;
                }
                """, 20);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "SWITCH-1::CASE-1",
                        "SWITCH-1::CASE-2",
                        "SWITCH-1::DEFAULT",
                        "TERNARY-1::TRUE",
                        "TERNARY-1::FALSE");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::condition)
                .containsExactly("region", "region", "region", "amount == 0", "amount == 0");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("\"VN\"", "\"US\" | \"SG\"", "DEFAULT", "TRUE", "FALSE");
    }

    @Test
    void addsNoMatchOutcomeForSwitchStatementWithoutDefault() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String label(int value) {
                    switch (value) {
                        case 1: return "one";
                        case 2: return "two";
                    }
                    return "other";
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "SWITCH-1::CASE-1",
                        "SWITCH-1::CASE-2",
                        "SWITCH-1::NO_MATCH");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("1", "2", "NO_MATCH");
    }

    @Test
    void treatsJava21CombinedNullDefaultAsDefaultWithoutFakeNoMatch() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String label(String value) {
                    return switch (value) {
                        case "ready" -> "ok";
                        case null, default -> "other";
                    };
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly("SWITCH-1::CASE-1", "SWITCH-1::DEFAULT");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("\"ready\"", "DEFAULT");
    }

    @Test
    void extractsForForeachWhileAndDoWhileOutcomes() {
        var branches = MethodBranchAnalyzer.analyze("""
                public int loops(java.util.List<Integer> values, int limit) {
                    int total = 0;
                    for (int index = 0; index < limit; index++) total += index;
                    for (Integer value : values) total += value;
                    while (total < limit) total++;
                    do { total--; } while (total > limit);
                    return total;
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "FOR-1::ENTER", "FOR-1::SKIP",
                        "FOREACH-1::ENTER", "FOREACH-1::SKIP",
                        "WHILE-1::ENTER", "WHILE-1::SKIP",
                        "DO_WHILE-1::REPEAT", "DO_WHILE-1::EXIT");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::condition)
                .containsExactly(
                        "index < limit", "index < limit",
                        "values", "values",
                        "total < limit", "total < limit",
                        "total > limit", "total > limit");
    }

    @Test
    void rejectsUnreadableSourceInsteadOfTreatingItAsBranchless() {
        assertThatThrownBy(() -> MethodBranchAnalyzer.analyze(
                "public void broken() { if (", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không parse được source method");
    }

    private List<SourceBranchDto> decisionBranches(List<SourceBranchDto> branches) {
        return branches.stream().filter(branch -> !"STATEMENT".equals(branch.kind())).toList();
    }

    @Test
    void sourceLinesCoverTheWholeBlockIncludingTheIfLineAndClosingBrace() {
        var branches = MethodBranchAnalyzer.analyze("""
                public long fee(long amount) {
                    if (amount >= 1000) {
                        return 0;
                    }
                    if (amount >= 500) {
                        return 1;
                    } else {
                        return 2;
                    }
                }
                """, 47);

        var first = branches.stream().filter(branch -> branch.branchId().equals("IF-1-TRUE")).findFirst().orElseThrow();
        assertThat(first.lineStart()).isEqualTo(48);
        assertThat(first.lineEnd()).isEqualTo(50);
        var noElse = branches.stream().filter(branch -> branch.branchId().equals("IF-1-FALSE")).findFirst().orElseThrow();
        assertThat(noElse.lineStart()).isEqualTo(48);
        assertThat(noElse.lineEnd()).isEqualTo(50);
        var withElse = branches.stream().filter(branch -> branch.branchId().equals("IF-2-FALSE")).findFirst().orElseThrow();
        assertThat(withElse.lineStart()).isEqualTo(53);
        assertThat(withElse.lineEnd()).isEqualTo(55);
    }

    @Test
    void optionalTerminalsAreDecisionsSoEachNotFoundPathHasItsOwnId() {
        // Giống DomainUserDetailsService.loadUserByUsername của jhipster-sample-app (project 1706).
        var branches = MethodBranchAnalyzer.analyze("""
                public UserDetails load(String login) {
                    if (isEmail(login)) {
                        return repository.findByEmail(login)
                            .map(user -> create(login, user))
                            .orElseThrow(() -> new UsernameNotFoundException("email " + login));
                    }
                    String lower = login.toLowerCase();
                    return repository.findByLogin(lower)
                        .map(user -> create(lower, user))
                        .orElseThrow(() -> new UsernameNotFoundException("User " + lower));
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId).containsExactly(
                "IF-1-TRUE", "IF-1-FALSE", "OPTIONAL-1::PRESENT", "OPTIONAL-1::EMPTY",
                "OPTIONAL-2::PRESENT", "OPTIONAL-2::EMPTY");
        assertThat(decisionBranches(branches)).filteredOn(b -> b.branchId().equals("OPTIONAL-2::EMPTY"))
                .singleElement().satisfies(branch -> {
                    assertThat(branch.kind()).isEqualTo("OPTIONAL");
                    assertThat(branch.condition()).startsWith("repository.findByLogin(lower)");
                    assertThat(branch.lineStart()).isEqualTo(8);
                    assertThat(branch.lineEnd()).isEqualTo(10);
                });
        // Câu return chứa quyết định Optional không còn là STMT anchor riêng.
        assertThat(branches).filteredOn(b -> "STATEMENT".equals(b.kind())).extracting(SourceBranchDto::condition)
                .containsExactly("String lower = login.toLowerCase();");
    }

    @Test
    void recognisesEveryOptionalFallbackTerminal() {
        var branches = MethodBranchAnalyzer.analyze("""
                public void sync(Long id) {
                    Item a = repo.findById(id).orElse(null);
                    Item b = repo.findById(id).orElseGet(Item::new);
                    repo.findById(id).ifPresent(item -> item.touch());
                    repo.findById(id).ifPresentOrElse(item -> item.touch(), () -> log.warn("missing"));
                    Item c = repo.findById(id).orElseThrow();
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId).containsExactly(
                "OPTIONAL-1::PRESENT", "OPTIONAL-1::EMPTY", "OPTIONAL-2::PRESENT", "OPTIONAL-2::EMPTY",
                "OPTIONAL-3::PRESENT", "OPTIONAL-3::EMPTY", "OPTIONAL-4::PRESENT", "OPTIONAL-4::EMPTY",
                "OPTIONAL-5::PRESENT", "OPTIONAL-5::EMPTY");
    }

    @Test
    void tryCatchIsADecisionWithOneOutcomePerCatchAndTryBodyStatementsStayAnchors() {
        var branches = MethodBranchAnalyzer.analyze("""
                public void send(Mail mail) {
                    try {
                        client.send(mail);
                    } catch (IOException exception) {
                        throw new MailException("io", exception);
                    } catch (RuntimeException exception) {
                        log.warn("failed");
                    } finally {
                        audit.record(mail);
                    }
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly("TRY-1::SUCCESS", "TRY-1::CATCH-1", "TRY-1::CATCH-2");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("SUCCESS", "IOException", "RuntimeException");
        assertThat(branches).filteredOn(b -> "STATEMENT".equals(b.kind())).extracting(SourceBranchDto::condition)
                .containsExactly("client.send(mail);", "audit.record(mail);");
    }

    @Test
    void preconditionCallsAreGuardDecisions() {
        var branches = MethodBranchAnalyzer.analyze("""
                public void update(Order order) {
                    Objects.requireNonNull(order, "order");
                    Assert.isTrue(order.getTotal() > 0, "total");
                    Preconditions.checkState(order.isOpen());
                    repository.save(order);
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId).containsExactly(
                "GUARD-1::PASS", "GUARD-1::FAIL", "GUARD-2::PASS", "GUARD-2::FAIL", "GUARD-3::PASS", "GUARD-3::FAIL");
        assertThat(branches).filteredOn(b -> "STATEMENT".equals(b.kind())).extracting(SourceBranchDto::condition)
                .containsExactly("repository.save(order);");
    }

    @Test
    void statementAnchorDescribesTheActualCode() {
        var branches = MethodBranchAnalyzer.analyze("""
                public void save(Order order) {
                    // Lưu đơn hàng.
                    repository.save(order);
                }
                """, 10);

        assertThat(branches).singleElement().satisfies(branch -> {
            assertThat(branch.condition()).isEqualTo("repository.save(order);");
            assertThat(branch.lineStart()).isEqualTo(12);
        });
    }
}
