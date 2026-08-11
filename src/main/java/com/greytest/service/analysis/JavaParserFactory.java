package com.greytest.service.analysis;

import java.io.IOException;
import java.nio.file.Path;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Parse source Java 8-21, ?u ti?n grammar Java 21 v? fallback cho c? ph?p legacy Java 8.
 */
final class JavaParserFactory {

    private JavaParserFactory() {
    }

    static ParseResult<CompilationUnit> parse(Path file) throws IOException {
        ParseResult<CompilationUnit> primary = create(ParserConfiguration.LanguageLevel.JAVA_21).parse(file);
        if (primary.isSuccessful()) return primary;
        return preferSuccessful(primary, create(ParserConfiguration.LanguageLevel.JAVA_8).parse(file));
    }

    static ParseResult<MethodDeclaration> parseMethodDeclaration(String sourceCode) {
        ParseResult<MethodDeclaration> primary = create(ParserConfiguration.LanguageLevel.JAVA_21)
                .parseMethodDeclaration(sourceCode);
        if (primary.isSuccessful()) return primary;
        return preferSuccessful(primary, create(ParserConfiguration.LanguageLevel.JAVA_8)
                .parseMethodDeclaration(sourceCode));
    }

    private static <T> ParseResult<T> preferSuccessful(ParseResult<T> primary, ParseResult<T> fallback) {
        return fallback.isSuccessful() ? fallback : primary;
    }

    private static JavaParser create(ParserConfiguration.LanguageLevel languageLevel) {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel));
    }
}
