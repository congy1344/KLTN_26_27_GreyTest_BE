package com.greytest.service.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.nodeTypes.SwitchNode;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.greytest.dto.SourceBranchDto;

/**
 * Trích xuất checklist decision/outcome từ control-flow của một Service method.
 */
public final class MethodBranchAnalyzer {

    private MethodBranchAnalyzer() {
    }

    public static List<SourceBranchDto> analyze(String sourceCode, Integer methodLineStart) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalStateException("Source method rong, khong the xac minh control-flow.");
        }
        try {
            ParseResult<MethodDeclaration> parseResult = JavaParserFactory.parseMethodDeclaration(sourceCode);
            MethodDeclaration method = parseResult.getResult()
                    .filter(ignored -> parseResult.isSuccessful())
                    .orElseThrow(() -> new ParseProblemException(parseResult.getProblems()));
            List<SourceBranchDto> branches = new ArrayList<>();
            Map<String, Integer> counters = new HashMap<>();
            method.walk(Node.TreeTraversal.PREORDER, node -> {
                if (node instanceof IfStmt statement) {
                    String prefix = nextPrefix(counters, "IF");
                    addBinary(branches, prefix, "IF", statement.getCondition(), statement,
                            methodLineStart, "TRUE", "FALSE");
                } else if (node instanceof SwitchExpr expression) {
                    addSwitch(branches, nextPrefix(counters, "SWITCH"), expression, expression,
                            methodLineStart);
                } else if (node instanceof SwitchStmt statement) {
                    addSwitch(branches, nextPrefix(counters, "SWITCH"), statement, statement,
                            methodLineStart);
                } else if (node instanceof ConditionalExpr expression) {
                    String prefix = nextPrefix(counters, "TERNARY");
                    addBinary(branches, prefix, "TERNARY", expression.getCondition(), expression,
                            methodLineStart, "TRUE", "FALSE");
                } else if (node instanceof ForStmt statement) {
                    String prefix = nextPrefix(counters, "FOR");
                    Expression condition = statement.getCompare().orElse(null);
                    if (condition == null) {
                        addOutcome(branches, prefix + "::ENTER", "FOR", "ENTER", "true",
                                statement, statement, methodLineStart);
                    } else {
                        addBinary(branches, prefix, "FOR", condition, statement,
                                methodLineStart, "ENTER", "SKIP");
                    }
                } else if (node instanceof ForEachStmt statement) {
                    String prefix = nextPrefix(counters, "FOREACH");
                    addBinary(branches, prefix, "FOREACH", statement.getIterable(), statement,
                            methodLineStart, "ENTER", "SKIP");
                } else if (node instanceof WhileStmt statement) {
                    String prefix = nextPrefix(counters, "WHILE");
                    addBinary(branches, prefix, "WHILE", statement.getCondition(), statement,
                            methodLineStart, "ENTER", "SKIP");
                } else if (node instanceof DoStmt statement) {
                    String prefix = nextPrefix(counters, "DO_WHILE");
                    addBinary(branches, prefix, "DO_WHILE", statement.getCondition(), statement,
                            methodLineStart, "REPEAT", "EXIT");
                }
            });
            return List.copyOf(branches);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Khong parse duoc source method de xac minh control-flow.", exception);
        }
    }

    private static void addSwitch(
            List<SourceBranchDto> branches,
            String prefix,
            Node node,
            SwitchNode switchNode,
            Integer methodLineStart) {
        int caseNumber = 0;
        for (SwitchEntry entry : switchNode.getEntries()) {
            boolean defaultEntry = isDefaultEntry(entry);
            String outcome = defaultEntry
                    ? "DEFAULT"
                    : entry.getLabels().stream()
                            .map(Expression::toString)
                            .collect(Collectors.joining(" | "));
            if (entry.getGuard().isPresent()) {
                outcome += " when " + normalized(entry.getGuard().get());
            }
            String branchId = defaultEntry
                    ? prefix + "::DEFAULT"
                    : prefix + "::CASE-" + (++caseNumber);
            addOutcome(branches, branchId, "SWITCH", outcome,
                    normalized(switchNode.getSelector()), node, switchNode.getSelector(), methodLineStart);
        }
        if (node instanceof SwitchStmt
                && switchNode.getEntries().stream().noneMatch(MethodBranchAnalyzer::isDefaultEntry)) {
            addOutcome(branches, prefix + "::NO_MATCH", "SWITCH", "NO_MATCH",
                    normalized(switchNode.getSelector()), node, switchNode.getSelector(), methodLineStart);
        }
    }

    private static boolean isDefaultLabel(Expression label) {
        return "default".equals(label.toString().trim());
    }

    private static boolean isDefaultEntry(SwitchEntry entry) {
        return entry.isDefault()
                || entry.getLabels().isEmpty()
                || entry.getLabels().stream().anyMatch(MethodBranchAnalyzer::isDefaultLabel);
    }

    private static void addBinary(
            List<SourceBranchDto> branches,
            String prefix,
            String kind,
            Expression condition,
            Node statement,
            Integer methodLineStart,
            String firstOutcome,
            String secondOutcome) {
        String normalizedCondition = normalized(condition);
        addOutcome(branches, outcomeId(prefix, firstOutcome), kind, firstOutcome,
                normalizedCondition, statement, condition, methodLineStart);
        addOutcome(branches, outcomeId(prefix, secondOutcome), kind, secondOutcome,
                normalizedCondition, statement, condition, methodLineStart);
    }

    private static String outcomeId(String prefix, String outcome) {
        return prefix.startsWith("IF-")
                ? prefix + "-" + outcome
                : prefix + "::" + outcome;
    }

    private static void addOutcome(
            List<SourceBranchDto> branches,
            String branchId,
            String kind,
            String outcome,
            String condition,
            Node statement,
            Node conditionNode,
            Integer methodLineStart) {
        branches.add(new SourceBranchDto(
                branchId,
                kind,
                outcome,
                condition,
                absoluteLine(statement, methodLineStart, true),
                absoluteLine(conditionNode, methodLineStart, false)));
    }

    private static String nextPrefix(Map<String, Integer> counters, String kind) {
        return kind + "-" + counters.merge(kind, 1, Integer::sum);
    }

    private static String normalized(Node node) {
        return node.toString().replaceAll("\\s+", " ").trim();
    }

    private static int absoluteLine(Node node, Integer methodLineStart, boolean begin) {
        int relative = node.getRange()
                .map(range -> begin ? range.begin.line : range.end.line)
                .orElse(1);
        return methodLineStart == null || methodLineStart <= 0
                ? relative
                : methodLineStart + relative - 1;
    }
}