package com.greytest.service.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import com.greytest.dto.SourceBranchDto;

/**
 * Trích xuất checklist nhánh if/else từ source của một method để kiểm tra BR.
 */
public final class MethodBranchAnalyzer {

    private MethodBranchAnalyzer() {
    }

    public static List<SourceBranchDto> analyze(String sourceCode, Integer methodLineStart) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalStateException("Source method rong, khong the xac minh nhanh if/else.");
        }
        try {
            MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(sourceCode);
            List<IfStmt> conditions = method.findAll(IfStmt.class).stream()
                    .sorted(Comparator.comparingInt(MethodBranchAnalyzer::relativeLine))
                    .toList();
            List<SourceBranchDto> branches = new ArrayList<>();
            for (int index = 0; index < conditions.size(); index++) {
                IfStmt statement = conditions.get(index);
                int lineStart = absoluteLine(statement, methodLineStart, true);
                int lineEnd = absoluteLine(statement.getCondition(), methodLineStart, false);
                String condition = statement.getCondition().toString().replaceAll("\\s+", " ").trim();
                String prefix = "IF-" + (index + 1);
                branches.add(new SourceBranchDto(
                        prefix + "-TRUE", "IF", "TRUE", condition, lineStart, lineEnd));
                branches.add(new SourceBranchDto(
                        prefix + "-FALSE", "IF", "FALSE", condition, lineStart, lineEnd));
            }
            return List.copyOf(branches);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Khong parse duoc source method de xac minh nhanh if/else.", exception);
        }
    }

    private static int relativeLine(IfStmt statement) {
        return statement.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE);
    }

    private static int absoluteLine(com.github.javaparser.ast.Node node, Integer methodLineStart, boolean begin) {
        int relative = node.getRange()
                .map(range -> begin ? range.begin.line : range.end.line)
                .orElse(1);
        return methodLineStart == null || methodLineStart <= 0
                ? relative
                : methodLineStart + relative - 1;
    }
}
