package com.greytest.service.agent;

import java.util.List;
import java.util.regex.Pattern;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.expr.StringLiteralExpr;

/** Đọc mẫu exception và đối số là tên tham số hoặc string literal từ mô tả/Java AST. */
final class ExceptionMessageFormatClaim {
    static final String HEADER = "\\b(?:(?:với|từ|theo|bằng|from|with|using)\\s+(?:mẫu|template)"
            + "|(?:mẫu|template)\\s+(?:thông\\s+(?:điệp|báo)|message|định\\s+dạng|format|nguồn|source|chính\\s+xác))\\b";
    private static final Pattern TEMPLATE = Pattern.compile("(?iuU)(?:" + HEADER
            + "|(?:thông\\s+(?:điệp|báo)|message)[^\"“”`\\n]{0,160}\\b(?:mẫu|template|format\\s+pattern|String\\.format|được\\s+định\\s+dạng|định\\s+dạng\\s+(?:bằng|với|theo|từ)|formatted)\\b)");
    private static final String FORMATTED_WITH = "(?:(?:được\\s+)?định\\s+dạng\\s+bằng|formatted\\s+(?:using|with))";
    private static final Pattern POSTFIX_FORMAT = Pattern.compile("(?iuU)^\\s*" + FORMATTED_WITH + "\\b");
    // Chấp nhận cụm chỉ thứ tự/tham số nguồn (VD: "theo đúng thứ tự", "lần lượt") trước danh sách đối số.
    private static final String ORDER_MODIFIER = "(?:nguồn|source|không\\s+đổi|giữ\\s+nguyên|đã\\s+duyệt|unchanged"
            + "|của\\s+source|từ\\s+source|trong\\s+source"
            + "|theo\\s+(?:đúng\\s+)?thứ\\s+tự(?:\\s+(?:nguồn|source|trong\\s+source|đã\\s+duyệt))?"
            + "|lần\\s+lượt|in\\s+(?:(?:exact|the\\s+same|source)\\s+)?order)";
    private static final String LABEL = "(?:(?:các|những|source|unchanged)\\s+)*(?:(?:tham|đối)\\s+số|giá\\s+trị|biến|parameters?|arguments?)"
            + "(?:\\s+" + ORDER_MODIFIER + ")*\\s*(?::|là|gồm|bao\\s+gồm|are)?\\s*";
    private static final String ORDER_PHRASE = "(?:theo\\s+(?:đúng\\s+)?thứ\\s+tự(?:\\s+(?:nguồn|source|trong\\s+source|đã\\s+duyệt))?"
            + "|lần\\s+lượt|in\\s+(?:(?:exact|the\\s+same|source)\\s+)?order)\\s*(?::|là|are)?\\s*";
    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final String ACCESSOR = "(?:\\." + IDENTIFIER + "(?:\\(\\s*\\))?)+";
    private static final String CLAUSE_WORD = "(?:require|requires|requiring|verify|verifies|verifying|assert|asserts|asserting"
            + "|check|checks|checking|must|should|shall|do|does|not|never|without|ensure|ensures|ensuring"
            + "|include|includes|including|preserve|preserves|preserving|keep|keeps|keeping|match|matches|matching|contain|contains|containing"
            + "|bao\\s+gồm|thay\\s+vì|cung\\s+cấp|theo\\s+(?:đúng|thứ|mẫu|source|nguồn)|cho\\s+(?:phép|biết)|trong\\s+(?:đó|mẫu|source|nguồn)|so\\s+(?:sánh|với)|do\\s+(?:đó|chính|source|formatter)|khi\\s+[\\p{L}])";
    private static final String NAME = "`?(?!" + CLAUSE_WORD + "\\b)"
            + IDENTIFIER + "(?:" + ACCESSOR + ")?`?(?![\\p{L}\\p{N}_$])";
    private static final String ARGUMENT = "(?:`?\"(?:\\\\.|[^\"\\\\])*\"`?|" + NAME + ")";
    private static final String SEPARATOR = "(?:,\\s*(?:\\bvà\\b|\\band\\b)|,|\\bvà\\b|\\band\\b)";
    private static final String CONNECTOR = "(?:" + FORMATTED_WITH
            + "|và|với|cùng(?:\\s+với)?|sử\\s+dụng|dùng|từ|bằng|qua|gồm|bao\\s+gồm|and|using|with|from|by)";
    private static final Pattern ARGUMENTS = Pattern.compile("(?iuU)^\\s*[;,]?\\s*(?:"
            + CONNECTOR + "\\s+(?:(?:" + LABEL + ")|(?:" + ORDER_PHRASE + "))?|" + LABEL + "|" + ORDER_PHRASE + ")"
            + "(" + ARGUMENT + "(?:\\s*" + SEPARATOR + "\\s*" + ARGUMENT + ")*)");
    private static final Pattern EXPRESSION = Pattern.compile("^\\s*(?:[+\\-*/%?=<>!&|^\\[\\]()]|\\.[A-Za-z_$]|\\.\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\()");
    private static final Pattern CALL = Pattern.compile("(?<![\\w$.])(?:java\\.lang\\.)?String\\.format\\s*\\(\\s*$");

    record Parsed(boolean template, boolean valid, List<String> arguments) { }
    private ExceptionMessageFormatClaim() { }

    static Parsed parse(String beforeLiteral, String literal, String afterLiteral) {
        var call = CALL.matcher(beforeLiteral);
        if (call.find()) return javaCall(beforeLiteral.substring(call.start()), literal, afterLiteral);
        if (!TEMPLATE.matcher(beforeLiteral).find() && !POSTFIX_FORMAT.matcher(afterLiteral).find())
            return new Parsed(false, false, List.of());
        var arguments = ARGUMENTS.matcher(afterLiteral);
        if (!arguments.find()) return new Parsed(true, true, List.of());
        boolean valid = !EXPRESSION.matcher(afterLiteral.substring(arguments.end())).find();
        var values = new java.util.ArrayList<String>();
        var tokens = Pattern.compile("(?iuU)(?:^|\\s*" + SEPARATOR + "\\s*)(" + ARGUMENT + ")").matcher(arguments.group(1));
        while (tokens.find()) {
            String raw = tokens.group(1);
            if (raw.startsWith("`") && raw.endsWith("`") && raw.length() >= 2) raw = raw.substring(1, raw.length() - 1);
            values.add(raw.startsWith("\"") ? raw : raw.replace("`", "").replaceAll("\\(\\s*\\)", "()"));
        }
        return new Parsed(true, valid, List.copyOf(values));
    }

    private static Parsed javaCall(String start, String literal, String suffix) {
        int close = -1, depth = 1;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            if (!quoted) {
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) { close = i; break; }
            }
        }
        if (close < 0) return new Parsed(true, false, List.of());
        // Chỉ nhận literal/tên biến; không chấp nhận biểu thức thay đổi dữ liệu nguồn.
        String expression = start + new StringLiteralExpr().setString(literal) + suffix.substring(0, close + 1);
        var parsed = new JavaParser().parseExpression(expression);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty() || !parsed.getResult().get().isMethodCallExpr())
            return new Parsed(true, false, List.of());
        var arguments = parsed.getResult().get().asMethodCallExpr().getArguments();
        boolean valid = arguments.stream().skip(1).allMatch(ExceptionMessageFormatClaim::isSupportedArgument)
                && !EXPRESSION.matcher(suffix.substring(close + 1).replaceFirst("^`", "")).find();
        return new Parsed(true, valid, arguments.stream().skip(1).map(Object::toString).toList());
    }

    private static boolean isSupportedArgument(com.github.javaparser.ast.expr.Expression expr) {
        if (expr.isNameExpr() || expr.isStringLiteralExpr()) return true;
        if (expr.isFieldAccessExpr()) return isSupportedArgument(expr.asFieldAccessExpr().getScope());
        if (expr.isMethodCallExpr()) {
            var call = expr.asMethodCallExpr();
            return call.getArguments().isEmpty() && call.getScope().map(ExceptionMessageFormatClaim::isSupportedArgument).orElse(false);
        }
        return false;
    }
}
