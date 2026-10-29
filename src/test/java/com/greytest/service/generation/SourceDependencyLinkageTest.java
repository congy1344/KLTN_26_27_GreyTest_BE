package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

class SourceDependencyLinkageTest {
    @Test
    void missingOptionalRuntimeTypeRecordsUnresolvedDependencyInsteadOfCrashing(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/main/java/example/Subject.java");
        Files.createDirectories(file.getParent());
        String source = "package example; class Subject { int target() { return missing(); } }";
        Files.writeString(file, source);
        var index = SourceDependencyIndex.load(root);
        var unit = StaticJavaParser.parse(source);
        unit.setStorage(file);
        var resolver = mock(SymbolResolver.class);
        unit.setData(Node.SYMBOL_RESOLVER_KEY, resolver);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        when(resolver.resolveDeclaration(call, ResolvedMethodDeclaration.class))
                .thenThrow(new NoClassDefFoundError("org/reactivestreams/Publisher"));
        var dependencies = new TreeMap<String, String>();
        var problems = new ArrayList<String>();

        assertThatCode(() -> index.visitMethod(unit.findFirst(MethodDeclaration.class).orElseThrow(),
                dependencies, new HashSet<>(), problems)).doesNotThrowAnyException();

        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("unresolved call", "missing"));
        assertThat(dependencies).isNotEmpty();
    }
}
