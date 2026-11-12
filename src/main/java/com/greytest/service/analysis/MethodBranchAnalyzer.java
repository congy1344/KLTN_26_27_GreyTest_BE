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
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.greytest.dto.SourceBranchDto;

/**
 * Trích xuất checklist decision/outcome từ control-flow của một Service method.
 */
public final class MethodBranchAnalyzer {
    private static final int MAX_STATEMENT_TEXT = 160;

    private MethodBranchAnalyzer() {
    }

    public static List<SourceBranchDto> analyze(String sourceCode, Integer methodLineStart) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalStateException("Source method rỗng, không thể xác minh control-flow.");
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
                    int[] whole = lines(statement, methodLineStart);
                    int[] then = {whole[0], lines(statement.getThenStmt(), methodLineStart)[1]};
                    int[] otherwise = statement.getElseStmt().map(branch -> lines(branch, methodLineStart)).orElse(whole);
                    addBinary(branches, prefix, "IF", statement.getCondition(), then, otherwise, "TRUE", "FALSE");
                } else if (node instanceof SwitchExpr expression) {
                    addSwitch(branches, nextPrefix(counters, "SWITCH"), expression, expression,
                            methodLineStart);
                } else if (node instanceof SwitchStmt statement) {
                    addSwitch(branches, nextPrefix(counters, "SWITCH"), statement, statement,
                            methodLineStart);
                } else if (node instanceof ConditionalExpr expression) {
                    int[] whole = lines(expression, methodLineStart);
                    addBinary(branches, nextPrefix(counters, "TERNARY"), "TERNARY", expression.getCondition(), whole, whole, "TRUE", "FALSE");
                } else if (node instanceof ForStmt statement) {
                    String prefix = nextPrefix(counters, "FOR");
                    int[] whole = lines(statement, methodLineStart);
                    Expression condition = statement.getCompare().orElse(null);
                    if (condition == null) {
                        addOutcome(branches, prefix + "::ENTER", "FOR", "ENTER", "true", whole);
                    } else {
                        addBinary(branches, prefix, "FOR", condition, whole, whole, "ENTER", "SKIP");
                    }
                } else if (node instanceof ForEachStmt statement) {
                    int[] whole = lines(statement, methodLineStart);
                    addBinary(branches, nextPrefix(counters, "FOREACH"), "FOREACH", statement.getIterable(), whole, whole, "ENTER", "SKIP");
                } else if (node instanceof WhileStmt statement) {
                    int[] whole = lines(statement, methodLineStart);
                    addBinary(branches, nextPrefix(counters, "WHILE"), "WHILE", statement.getCondition(), whole, whole, "ENTER", "SKIP");
                } else if (node instanceof DoStmt statement) {
                    int[] whole = lines(statement, methodLineStart);
                    addBinary(branches, nextPrefix(counters, "DO_WHILE"), "DO_WHILE", statement.getCondition(), whole, whole, "REPEAT", "EXIT");
                }
            });
            List<SourceBranchDto> anchors = new ArrayList<>();
            appendSourceStatementAnchors(method, anchors, methodLineStart);

            List<SourceBranchDto> merged = new ArrayList<>();
            int aIdx = 0;
            for (SourceBranchDto branch : branches) {
                while (aIdx < anchors.size() && anchors.get(aIdx).lineStart() < branch.lineStart()) {
                    merged.add(anchors.get(aIdx++));
                }
                merged.add(branch);
            }
            while (aIdx < anchors.size()) {
                merged.add(anchors.get(aIdx++));
            }
            return List.copyOf(merged);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Không parse được source method để xác minh control-flow.", exception);
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
                    normalized(switchNode.getSelector()), lines(entry, methodLineStart));
        }
        if (node instanceof SwitchStmt
                && switchNode.getEntries().stream().noneMatch(MethodBranchAnalyzer::isDefaultEntry)) {
            addOutcome(branches, prefix + "::NO_MATCH", "SWITCH", "NO_MATCH",
                    normalized(switchNode.getSelector()), lines(node, methodLineStart));
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
            int[] firstLines,
            int[] secondLines,
            String firstOutcome,
            String secondOutcome) {
        String normalizedCondition = normalized(condition);
        addOutcome(branches, outcomeId(prefix, firstOutcome), kind, firstOutcome, normalizedCondition, firstLines);
        addOutcome(branches, outcomeId(prefix, secondOutcome), kind, secondOutcome, normalizedCondition, secondLines);
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
            int[] lines) {
        branches.add(new SourceBranchDto(branchId, kind, outcome, condition, lines[0], lines[1]));
    }

    private static void appendSourceStatementAnchors(
            MethodDeclaration method,
            List<SourceBranchDto> branches,
            Integer methodLineStart) {
        int[] counter = {0};
        method.getBody().ifPresent(body -> body.getStatements()
                .forEach(statement -> collectStatementAnchors(statement, branches, counter, methodLineStart)));
    }

    private static void collectStatementAnchors(
            Statement statement,
            List<SourceBranchDto> branches,
            int[] counter,
            Integer methodLineStart) {
        if (isDecisionStatement(statement)) return;
        if (statement instanceof BlockStmt block) {
            block.getStatements().forEach(child -> collectStatementAnchors(child, branches, counter, methodLineStart));
            return;
        }
        addOutcome(branches, "STMT-" + (++counter[0]), "STATEMENT", "EXECUTABLE", statementText(statement),
                lines(statement, methodLineStart));
    }

    private static boolean isDecisionStatement(Statement statement) {
        return statement instanceof IfStmt
                || statement instanceof SwitchStmt
                || statement instanceof ForStmt
                || statement instanceof ForEachStmt
                || statement instanceof WhileStmt
                || statement instanceof DoStmt
                || statement.findFirst(ConditionalExpr.class).isPresent()
                || statement.findFirst(SwitchExpr.class).isPresent();
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

    /** Cả khối trong source, gồm dòng if và dấu đóng, để người đọc thấy đúng đoạn code của nhánh. */
    private static int[] lines(Node node, Integer methodLineStart) {
        return new int[] {
                absoluteLine(node, methodLineStart, true),
                absoluteLine(node, methodLineStart, false)
        };
    }

    /** Dùng chính câu lệnh làm mô tả để giao diện hiện được code thay vì nhãn chung. */
    private static String statementText(Statement statement) {
        Statement copy = statement.clone();
        copy.removeComment();
        String text = normalized(copy);
        return text.length() > MAX_STATEMENT_TEXT ? text.substring(0, MAX_STATEMENT_TEXT - 3) + "..." : text;
    }
}
