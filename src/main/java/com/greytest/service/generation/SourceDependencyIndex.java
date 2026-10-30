package com.greytest.service.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.ServiceScopeResolver;

/** Index AST theo digest snapshot; chỉ giữ trong input bất biến của một generation run. */
final class SourceDependencyIndex {
    private static final Set<String> SKIP = Set.of(".git", "target", "build", "node_modules", ".gradle", "generated-sources");
    private final Path root;
    private final Map<String, CompilationUnit> units = new TreeMap<>();
    private final Map<String, List<TypeDeclaration<?>>> types = new TreeMap<>();
    private final Map<Node, String> relativePaths = new java.util.IdentityHashMap<>();
    private final Map<TypeDeclaration<?>, String> typeIdentities = new java.util.IdentityHashMap<>();
    private final Map<String, CombinedTypeSolver> solvers = new TreeMap<>();
    private final ParserDependencyClasspath.Snapshot classpath;
    private CombinedTypeSolver cachedOutputSolver;
    private String analysisModule;
    private volatile boolean retainedInProcessCache;
    private record IndexReadKey(Path root) {}
    private record BuildReadKey(Path root, String module) {}
    private record VerifiedIndexKey(Path root, String digest) {}
    private record CachedIndex(SourceDependencyIndex index, long sourceCharacters) {}
    private static final Map<VerifiedIndexKey, CachedIndex> VERIFIED_INDEXES = new java.util.LinkedHashMap<>(4, 0.75f, true);
    private static long cachedSourceCharacters;
    private static final long MAX_CACHED_SOURCE_CHARACTERS = 2_000_000;
    private record ClosureKey(List<String> targets) {}
    private record Closure(Map<String, String> dependencies, List<String> problems) {}
    private final Map<ClosureKey, Closure> closures = new java.util.LinkedHashMap<>(64, 0.75f, true);

    private SourceDependencyIndex(Path root, Map<String, String> sources, Map<String, CompilationUnit> parsedUnits, ParserDependencyClasspath.Snapshot classpath) {
        this.root = root;
        this.classpath = classpath;
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);
        for (var entry : sources.entrySet()) {
            CompilationUnit unit;
            if (parsedUnits.containsKey(entry.getKey()) && parsedUnits.get(entry.getKey()).getTokenRange()
                    .map(tokens -> tokens.toString().trim().equals(entry.getValue().trim())).orElse(false)) unit = parsedUnits.get(entry.getKey()).clone();
            else {
                GenerationJobContext.increment("dependencyAstParses");
                var parsed = parser.parse(entry.getValue());
                if (!parsed.isSuccessful()) continue;
                unit = parsed.getResult().orElseThrow();
            }
            unit.setStorage(root.resolve(entry.getKey()));
            units.put(entry.getKey(), unit);
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(name -> {
                    types.computeIfAbsent(name, ignored -> new ArrayList<>()).add(type);
                    relativePaths.put(type, entry.getKey());
                    typeIdentities.put(type, ServiceScopeResolver.modulePath(entry.getKey()) + ":" + name);
                });
            }
        }
        LombokSourceSymbols.expand(root, units);
        configureResolvers();
    }

    private void configureResolvers() {
        Map<String, MemoryTypeSolver> memories = new TreeMap<>();
        for (String path : units.keySet()) {
            String module = ServiceScopeResolver.modulePath(path);
            if (solvers.containsKey(module)) continue;
            MemoryTypeSolver memory = new MemoryTypeSolver();
            memories.put(module, memory);
            solvers.put(module, classpath.solver(memory, module));
        }
        units.forEach((path, unit) -> unit.setData(Node.SYMBOL_RESOLVER_KEY,
                new JavaSymbolSolver(solvers.get(ServiceScopeResolver.modulePath(path)))));
        for (var entry : memories.entrySet()) {
            for (var sourceType : types.entrySet()) {
                var local = sourceType.getValue().stream().filter(type -> ServiceScopeResolver.modulePath(relative(type)).equals(entry.getKey())).toList();
                var available = sourceType.getValue().stream().filter(type -> classpath.sourceAvailable(entry.getKey(), ServiceScopeResolver.modulePath(relative(type)))).toList();
                TypeDeclaration<?> declaration = local.size() == 1 ? local.get(0) : available.size() == 1 ? available.get(0) : null;
                if (declaration == null) continue;
                try {
                    String module = ServiceScopeResolver.modulePath(relative(declaration));
                    entry.getValue().addDeclaration(sourceType.getKey(), JavaParserFacade.get(solvers.get(module)).getTypeDeclaration(declaration));
                } catch (RuntimeException | LinkageError exception) {
                    // Record/type chưa được solver hỗ trợ vẫn giữ AST; bước resolve sẽ trả diagnostic rõ ràng.
                }
            }
        }
    }

    static SourceDependencyIndex load(Path sourceRoot) {
        return load(sourceRoot, Map.of());
    }

    static SourceDependencyIndex load(Path sourceRoot, Map<String, CompilationUnit> parsedUnits) {
        try {
            if (sourceRoot == null) throw new IllegalArgumentException("Missing source root");
            Path root = sourceRoot.toRealPath();
            return com.greytest.service.agent.GenerationContextBuilder.inputSnapshot(new IndexReadKey(root),
                    () -> loadVerified(root, parsedUnits));
        } catch (IOException exception) { throw new IllegalArgumentException("Unreadable snapshot", exception); }
    }

    private static SourceDependencyIndex loadVerified(Path root, Map<String, CompilationUnit> parsedUnits) {
        try {
            Map<String, String> sources = new TreeMap<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    if (!path.equals(root) && SKIP.contains(path.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    checked(root, path);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    checked(root, path);
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (relative.contains("src/main/java/") && relative.endsWith(".java")) {
                        if (Files.size(path) > 2_000_000) throw new IllegalArgumentException("Oversized source dependency");
                        GenerationJobContext.increment("dependencySourceReads");
                        sources.put(relative, Files.readString(path));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            var classpath = ParserDependencyClasspath.load(root, sources.keySet().stream().map(ServiceScopeResolver::modulePath).collect(java.util.stream.Collectors.toSet()));
            StringBuilder manifest = new StringBuilder();
            manifest.append("analysis-classpath:").append(classpath.digest()).append('\n');
            sources.forEach((path, source) -> manifest.append(path.length()).append(':').append(path)
                    .append(':').append(GenerationFingerprintService.hash(source)).append(':')
                    .append(LombokSourceSymbols.hasConfiguration(root.resolve(path).getParent())).append('\n'));
            String digest = GenerationFingerprintService.hash(manifest.toString());
            return GenerationJobContext.memoize("source-dependency-index-v1:" + root + ":" + digest,
                    () -> verifiedIndex(new VerifiedIndexKey(root, digest), sources, parsedUnits, classpath));
        } catch (IOException exception) { throw new IllegalArgumentException("Unreadable snapshot", exception); }
    }

    private static SourceDependencyIndex verifiedIndex(VerifiedIndexKey key, Map<String, String> sources,
            Map<String, CompilationUnit> parsedUnits, ParserDependencyClasspath.Snapshot classpath) {
        synchronized (VERIFIED_INDEXES) {
            CachedIndex cached = VERIFIED_INDEXES.get(key);
            if (cached != null) { GenerationJobContext.increment("dependencyIndexHits"); return cached.index(); }
        }
        GenerationJobContext.increment("dependencyIndexMisses");
        SourceDependencyIndex created = new SourceDependencyIndex(key.root(), sources, parsedUnits, classpath);
        long characters = sources.values().stream().mapToLong(String::length).sum();
        if (characters > MAX_CACHED_SOURCE_CHARACTERS) { created.releaseUncachedFacades(); return created; }
        synchronized (VERIFIED_INDEXES) {
            CachedIndex previous = VERIFIED_INDEXES.get(key);
            if (previous != null) { created.releaseUncachedFacades(); return previous.index(); }
            while (!VERIFIED_INDEXES.isEmpty() && (VERIFIED_INDEXES.size() >= (classpath.configured() ? 1 : 4)
                    || cachedSourceCharacters + characters > MAX_CACHED_SOURCE_CHARACTERS)) {
                var oldest = VERIFIED_INDEXES.entrySet().iterator();
                var removed = oldest.next();
                cachedSourceCharacters -= removed.getValue().sourceCharacters();
                removed.getValue().index().retainedInProcessCache=false;
                oldest.remove();
                // Facade của thư viện giữ strong reference ngược về solver; bỏ registry khi evict để giải phóng AST cũ.
                synchronized(JavaParserFacade.class) { JavaParserFacade.clearInstances(); }
            }
            VERIFIED_INDEXES.put(key, new CachedIndex(created, characters));
            created.retainedInProcessCache=true;
            cachedSourceCharacters += characters;
        }
        return created;
    }

    synchronized Map<String, String> dependencies(List<ClassContextDto> targets, List<String> problems) {
        try { return dependenciesWithLifetime(targets,problems); }
        finally { analysisModule = null; releaseUncachedFacades(); }
    }

    private Map<String,String> dependenciesWithLifetime(List<ClassContextDto> targets,List<String> problems) {
        List<String> identities = new ArrayList<>();
        for (ClassContextDto owner : targets) for (MethodContextDto method : owner.methods()) identities.add(
                owner.filePath() + "#" + owner.qualifiedName() + "#" + method.methodName() + "("
                        + (method.parameters() == null ? "" : method.parameters().stream().map(com.greytest.dto.MethodParamDto::type)
                            .collect(java.util.stream.Collectors.joining(","))) + ")");
        identities.sort(String::compareTo);
        ClosureKey key = new ClosureKey(List.copyOf(identities));
        Closure cached = closures.get(key);
        if (cached != null) {
            GenerationJobContext.increment("dependencyClosureHits");
            problems.addAll(cached.problems());
            return new TreeMap<>(cached.dependencies());
        }
        List<String> sourceProblems = new ArrayList<>();
        Map<String, String> result = computeDependencies(targets, sourceProblems);
        if (closures.size() >= 64) closures.remove(closures.keySet().iterator().next());
        closures.put(key, new Closure(java.util.Collections.unmodifiableMap(new TreeMap<>(result)), List.copyOf(sourceProblems)));
        problems.addAll(sourceProblems);
        return result;
    }

    private Map<String, String> computeDependencies(List<ClassContextDto> targets, List<String> problems) {
        Map<String, String> result = new TreeMap<>();
        classpathEvidence(result);
        Set<String> targetTypes = new HashSet<>();
        for (ClassContextDto target : targets) targetTypes.add("target-type:"
                + ServiceScopeResolver.modulePath(target.filePath()) + ":" + target.qualifiedName());
        for (ClassContextDto owner : targets) {
            analysisModule = ServiceScopeResolver.modulePath(owner.filePath());
            Set<String> visited = new HashSet<>(targetTypes);
            String path = safeRelative(owner.filePath());
            CompilationUnit unit = units.get(path);
            if (unit == null) { problems.add("unresolved target source: " + path); continue; }
            var matching = unit.findAll(TypeDeclaration.class).stream()
                    .filter(type -> type.getFullyQualifiedName().filter(owner.qualifiedName()::equals).isPresent()).toList();
            if (matching.size() != 1) { problems.add("unresolved target type: " + owner.qualifiedName()); continue; }
            TypeDeclaration<?> type = matching.get(0);
            for (MethodContextDto method : owner.methods()) {
                var methods = type.getMethodsByName(method.methodName()).stream().filter(candidate -> signatureMatches(candidate, method)).toList();
                if (methods.size() != 1) { problems.add("unresolved target signature: " + owner.qualifiedName() + "#" + method.methodName()); continue; }
                visitMethod(methods.get(0), result, visited, problems);
            }
        }
        return result;
    }

    private boolean signatureMatches(MethodDeclaration candidate, MethodContextDto target) {
        var params = target.parameters() == null ? List.<com.greytest.dto.MethodParamDto>of() : target.parameters();
        if (candidate.getParameters().size() != params.size()) return false;
        for (int i = 0; i < params.size(); i++) {
            String actual = candidate.getParameter(i).getType().asString() + (candidate.getParameter(i).isVarArgs() ? "[]" : "");
            if (!actual.equals(params.get(i).type()) && !simple(actual).equals(simple(params.get(i).type()))) return false;
        }
        return true;
    }

    void visitMethod(MethodDeclaration method, Map<String, String> result, Set<String> visited, List<String> problems) {
        TypeDeclaration<?> owner = method.findAncestor(TypeDeclaration.class).orElseThrow();
        String key = identity(owner) + "#" + method.getSignature();
        if (!visited.add(key)) return;
        result.put(key, digest(method));
        visitContract(owner, result, visited, problems, false);
        for (AnnotationExpr annotation : method.findAll(AnnotationExpr.class)) visitAnnotation(annotation, owner, result, visited, problems);
        visitBody(method, owner, key, result, visited, problems);
    }

    private void visitBody(Node body, TypeDeclaration<?> owner, String key, Map<String, String> result,
            Set<String> visited, List<String> problems) {
        // Source AST giữ literal nguyên vẹn; nối dependency bằng receiver/type/signature đã resolve.
        for (ClassOrInterfaceType type : body.findAll(ClassOrInterfaceType.class)) visitReferencedType(type, result, visited, problems, true);
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            try {
                var resolved = call.resolve();
                result.put("resolved-call:" + resolved.getQualifiedSignature(), "external-or-source");
                var declaration = resolved.toAst(MethodDeclaration.class);
                if (declaration.isPresent()) {
                    var actual = exactType(resolved.declaringType().getQualifiedName(), owner, problems);
                    if (actual != null) {
                        var candidates = actual.getMethodsByName(resolved.getName()).stream()
                                .filter(candidate -> sameResolvedSignature(candidate, resolved.getSignature())).toList();
                        if (candidates.size() != 1) problems.add("unresolved dependency overload: " + resolved.getQualifiedSignature());
                        else {
                            visitMethod(candidates.get(0), result, visited, problems);
                            var invoked=candidates.get(0);
                            if (actual.isClassOrInterfaceDeclaration() && !actual.asClassOrInterfaceDeclaration().isFinal()
                                    && !invoked.isStatic() && !invoked.isPrivate() && !invoked.isFinal()
                                    && call.getScope().filter(scope->scope.isObjectCreationExpr()).isEmpty()) {
                                new SourceDispatchResolver(this).visit(actual, candidates.get(0), owner, result, visited, problems);
                            }
                        }
                    }
                } else if (types.containsKey(resolved.declaringType().getQualifiedName())) problems.add("unresolved source method: " + resolved.getQualifiedSignature());
            } catch (RuntimeException | LinkageError exception) {
                // Reflection có thể gặp type tùy chọn thiếu trong runtime; không dùng closure này làm bằng chứng tái sử dụng.
                if (!knownConstructorStreamCollector(call, owner, result, visited, problems))
                    problems.add("unresolved call: " + identity(owner) + "#" + call.getNameAsString());
            }
        }
        for (FieldAccessExpr access : body.findAll(FieldAccessExpr.class)) {
            if (knownTypeQualifier(access)) continue;
            try {
                var field = access.resolve();
                if (field.isField()) visitFieldOwner(field.asField().declaringType().getQualifiedName(), owner, result, visited, problems);
            } catch (RuntimeException | LinkageError exception) { problems.add("unresolved field access: " + identity(owner)); }
        }
        for (NameExpr name : body.findAll(NameExpr.class)) {
            try {
                var value = name.resolve();
                if (value.isField()) visitFieldOwner(value.asField().declaringType().getQualifiedName(), owner, result, visited, problems);
            } catch (RuntimeException | LinkageError exception) {
                // Type qualifiers such as Math in Math.abs are resolved by their surrounding expression.
                if (!(name.getParentNode().orElse(null) instanceof MethodCallExpr)
                        && !(name.getParentNode().orElse(null) instanceof FieldAccessExpr)) problems.add("unresolved value: " + name.getNameAsString());
            }
        }
        for (ObjectCreationExpr creation : body.findAll(ObjectCreationExpr.class)) {
            try {
                var resolved = creation.resolve();
                TypeDeclaration<?> sourceType = exactType(resolved.declaringType().getQualifiedName(), owner, problems);
                if (sourceType != null) visitContract(sourceType, result, visited, problems, false);
            } catch (RuntimeException | LinkageError exception) { problems.add("unresolved constructor: " + identity(owner)); }
        }
        for (MethodReferenceExpr reference : body.findAll(MethodReferenceExpr.class)) {
            if (!visitConstructorReference(reference, owner, result, visited, problems)
                    && !visitMethodReference(reference, owner, result, visited, problems)) {
                problems.add("unresolved method reference: " + key);
            }
        }
    }

    private boolean knownTypeQualifier(FieldAccessExpr access) {
        Node qualifier = access;
        while (qualifier.getParentNode().orElse(null) instanceof FieldAccessExpr parent && parent.getScope() == qualifier) qualifier = parent;
        if (!(qualifier.getParentNode().orElse(null) instanceof MethodCallExpr call) || call.getScope().orElse(null) != qualifier) return false;
        try {
            var resolved = call.resolve();
            return resolved.isStatic() && resolved.declaringType().getQualifiedName().equals(qualifier.toString());
        } catch (RuntimeException | LinkageError unresolved) { return false; }
    }

    private boolean knownConstructorStreamCollector(MethodCallExpr call, TypeDeclaration<?> owner, Map<String, String> result,
            Set<String> visited, List<String> problems) {
        if (!call.getNameAsString().equals("collect") || call.getArguments().size() != 1
                || call.getScope().filter(scope -> scope.isMethodCallExpr()).isEmpty()
                || !call.getArgument(0).isMethodCallExpr()) return false;
        MethodCallExpr map = call.getScope().orElseThrow().asMethodCallExpr();
        MethodCallExpr collector = call.getArgument(0).asMethodCallExpr();
        try {
            var resolved = collector.resolve();
            if (!resolved.declaringType().getQualifiedName().equals("java.util.stream.Collectors")
                    || !Set.of("toList", "toSet").contains(resolved.getName()) || !collector.getArguments().isEmpty()
                    || map.getArguments().size() != 1 || !map.getArgument(0).isMethodReferenceExpr()) return false;
            if (!visitConstructorReference(map.getArgument(0).asMethodReferenceExpr(), owner, result, visited, problems)) return false;
            result.put("resolved-call:java.util.stream.Stream.collect(java.util.stream.Collector)", "external-or-source");
            return true;
        } catch (RuntimeException | LinkageError unresolved) { return false; }
    }

    private boolean visitConstructorReference(MethodReferenceExpr reference, TypeDeclaration<?> caller, Map<String, String> result,
            Set<String> visited, List<String> problems) {
        if (!reference.getIdentifier().equals("new") || !reference.getScope().isTypeExpr()
                || reference.getParentNode().filter(MethodCallExpr.class::isInstance).isEmpty()) return false;
        MethodCallExpr map = (MethodCallExpr) reference.getParentNode().orElseThrow();
        if (!map.getNameAsString().equals("map") || map.getArguments().size() != 1 || map.getArgument(0) != reference || map.getScope().isEmpty()) return false;
        try {
            if (!map.resolve().declaringType().getQualifiedName().equals("java.util.stream.Stream")) return false;
            var stream = map.getScope().orElseThrow().calculateResolvedType();
            if (!stream.isReferenceType() || !stream.asReferenceType().getQualifiedName().equals("java.util.stream.Stream")) return false;
            var arguments = stream.asReferenceType().typeParametersValues();
            if (arguments.size() != 1 || !arguments.get(0).isReferenceType() || !arguments.get(0).asReferenceType().typeParametersValues().isEmpty()) return false;
            var target = reference.getScope().asTypeExpr().getType().resolve();
            if (!target.isReferenceType()) return false;
            var type = exactType(target.asReferenceType().getQualifiedName(), caller, problems);
            if (type == null || !type.isClassOrInterfaceDeclaration() || type.asClassOrInterfaceDeclaration().isInterface()
                    || type.asClassOrInterfaceDeclaration().isAbstract() || !type.asClassOrInterfaceDeclaration().getTypeParameters().isEmpty()
                    || type.getParentNode().filter(CompilationUnit.class::isInstance).isEmpty()) return false;
            if (!type.isPublic() && !type.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString()).orElse("")
                    .equals(caller.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""))) return false;
            var constructors = type.getConstructors().stream().filter(c -> c.getParameters().size() == 1)
                    .filter(c -> c.getParameter(0).getType().resolve().isAssignableBy(arguments.get(0))).toList();
            if (constructors.size() != 1) return false;
            visitContract(type, result, visited, problems, false);
            var constructor = constructors.get(0);
            if (!constructor.isPublic() || !constructor.getTypeParameters().isEmpty() || !constructor.getThrownExceptions().isEmpty()) return false;
            result.put("resolved-constructor-reference:" + identity(type) + "#" + constructor.getSignature(), digest(constructor));
            return true;
        } catch (RuntimeException | LinkageError unresolved) { return false; }
    }

    private boolean visitMethodReference(MethodReferenceExpr reference, TypeDeclaration<?> caller, Map<String, String> result,
            Set<String> visited, List<String> problems) {
        try {
            var resolved = reference.resolve();
            String declaringType = resolved.declaringType().getQualifiedName();
            TypeDeclaration<?> targetType = exactType(declaringType, caller, problems);
            if (targetType != null) {
                var candidates = targetType.getMethodsByName(reference.getIdentifier());
                if (!candidates.isEmpty()) {
                    visitContract(targetType, result, visited, problems, false);
                    visitMethod(candidates.get(0), result, visited, problems);
                    return true;
                }
            }
            result.put("resolved-call:" + resolved.getQualifiedSignature(), "external-or-source");
            return true;
        } catch (RuntimeException | LinkageError unresolved) {
            if (reference.getScope() != null) {
                String scopeName = reference.getScope().toString();
                CompilationUnit unit = caller.findCompilationUnit().orElse(null);
                String qualified = scopeName;
                if (unit != null && !scopeName.contains(".")) {
                    qualified = unit.getImports().stream()
                            .filter(i -> !i.isStatic() && i.getName().getIdentifier().equals(scopeName))
                            .map(i -> i.getNameAsString()).findFirst()
                            .orElseGet(() -> unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + scopeName);
                }
                TypeDeclaration<?> targetType = exactType(qualified, caller, problems);
                if (targetType != null) {
                    var candidates = targetType.getMethodsByName(reference.getIdentifier());
                    if (!candidates.isEmpty()) {
                        visitContract(targetType, result, visited, problems, false);
                        visitMethod(candidates.get(0), result, visited, problems);
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private boolean sameResolvedSignature(MethodDeclaration method, String signature) {
        try { return method.resolve().getSignature().equals(signature); }
        catch (RuntimeException | LinkageError exception) { return false; }
    }

    private void visitContract(TypeDeclaration<?> owner, Map<String, String> result, Set<String> visited,
            List<String> problems, boolean dto) {
        dto = dto || LombokSourceSymbols.isData(owner);
        String key = identity(owner) + (dto ? "#dto" : "#contract");
        if (!visited.add(key)) return;
        if (LombokSourceSymbols.unsupported(owner) != null) problems.add(LombokSourceSymbols.unsupported(owner));
        TypeDeclaration<?> contract = owner.clone();
        LombokSourceSymbols.removeGenerated(contract);
        if (!dto) contract.getMembers().removeIf(member -> member.isMethodDeclaration() || member.isTypeDeclaration());
        result.put(key, digest(contract));
        for (ClassOrInterfaceType type : owner.findAll(ClassOrInterfaceType.class)) {
            if (!dto && type.findAncestor(MethodDeclaration.class).isPresent()) continue;
            visitReferencedType(type, result, visited, problems, dto);
        }
        owner.getConstructors().forEach(constructor -> {
            visitBody(constructor, owner, identity(owner) + "#" + constructor.getSignature(), result, visited, problems);
        });
        for (var member : owner.getMembers()) {
            if (!member.isMethodDeclaration() && !member.isConstructorDeclaration() && !member.isTypeDeclaration()
                    && !LombokSourceSymbols.isGenerated(member)
                    && (!member.findAll(MethodCallExpr.class).isEmpty() || !member.findAll(ObjectCreationExpr.class).isEmpty())) {
                problems.add("unresolved initializer dependency: " + identity(owner));
            }
        }
        for (AnnotationExpr annotation : owner.findAll(AnnotationExpr.class)) {
            if (!dto && annotation.findAncestor(MethodDeclaration.class).isPresent()) continue;
            visitAnnotation(annotation, owner, result, visited, problems);
        }
        if (dto) for (MethodDeclaration method : owner.getMethods()) {
            visitMethod(method, result, visited, problems);
            if (LombokSourceSymbols.isData(owner) && !method.isPrivate() && !method.isStatic() && !method.isFinal()
                    && owner.isClassOrInterfaceDeclaration() && !owner.asClassOrInterfaceDeclaration().isFinal()) {
                new SourceDispatchResolver(this).visit(owner, method, owner, result, visited, problems);
            }
        }
    }

    private void visitAnnotation(AnnotationExpr annotation, TypeDeclaration<?> owner, Map<String, String> result,
            Set<String> visited, List<String> problems) {
        try {
            TypeDeclaration<?> sourceType = exactType(annotation.resolve().getQualifiedName(), owner, problems);
            if (sourceType != null) visitContract(sourceType, result, visited, problems, true);
        } catch (RuntimeException | LinkageError exception) {
            if (!isKnownExternalAnnotation(annotation, owner)) {
                problems.add("unresolved annotation: " + annotation.getNameAsString());
            }
        }
    }

    private boolean isKnownExternalAnnotation(AnnotationExpr annotation, TypeDeclaration<?> owner) {
        String name = annotation.getNameAsString();
        if (name.contains(".")) return isExternalPrefix(name);
        CompilationUnit unit = owner.findCompilationUnit().orElse(null);
        if (unit != null) {
            for (var imported : unit.getImports()) {
                if (!imported.isStatic() && imported.getName().getIdentifier().equals(name)) {
                    return isExternalPrefix(imported.getNameAsString());
                }
            }
            if (unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk()
                    && isExternalPrefix(i.getNameAsString()))) {
                return true;
            }
        }
        return Set.of("Entity", "Table", "Column", "Id", "GeneratedValue", "ManyToOne", "OneToMany",
                "JoinColumn", "MappedSuperclass", "Transactional", "Service", "Repository", "Component",
                "Controller", "RestController", "Data", "Getter", "Setter", "NoArgsConstructor",
                "AllArgsConstructor", "RequiredArgsConstructor", "Builder", "EqualsAndHashCode",
                "ToString", "Slf4j", "Override", "Deprecated", "SuppressWarnings").contains(name);
    }

    private static boolean isExternalPrefix(String qualifiedName) {
        return qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.") || qualifiedName.startsWith("jakarta.")
                || qualifiedName.startsWith("org.springframework.") || qualifiedName.startsWith("lombok.")
                || qualifiedName.startsWith("org.hibernate.") || qualifiedName.startsWith("org.junit.")
                || qualifiedName.startsWith("org.mockito.") || qualifiedName.startsWith("com.fasterxml.jackson.")
                || qualifiedName.startsWith("org.slf4j.");
    }

    TypeDeclaration<?> outputType(String name, List<String> problems) {
        return outputType(name, null, problems);
    }

    TypeDeclaration<?> outputType(String name, String module, List<String> problems) {
        var candidates = types.getOrDefault(name, List.of());
        if (classpath.configured()) {
            var available = candidates.stream().filter(type -> classpath.sourceAvailable(module, ServiceScopeResolver.modulePath(relative(type)))).toList();
            if (!candidates.isEmpty() && available.isEmpty()) problems.add("unresolved source outside analysis module classpath: " + name);
            candidates = available;
        } else if (module != null && candidates.size() > 1) {
            var local = candidates.stream().filter(type -> ServiceScopeResolver.modulePath(relative(type)).equals(module)).toList();
            if (local.size() == 1) return local.get(0);
        }
        if (candidates.size() > 1) problems.add("unresolved output module: " + name);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    synchronized CombinedTypeSolver outputSolver() {
        if(cachedOutputSolver!=null) return cachedOutputSolver;
        MemoryTypeSolver memory = new MemoryTypeSolver();
        CombinedTypeSolver solver = classpath.solver(memory, ".");
        for (var entry : types.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            TypeDeclaration<?> declaration = entry.getValue().get(0);
            try {
                memory.addDeclaration(entry.getKey(), JavaParserFacade.get(solvers.get(
                        ServiceScopeResolver.modulePath(relative(declaration)))).getTypeDeclaration(declaration));
            } catch (RuntimeException | LinkageError exception) { /* Unsupported types produce diagnostics when actually referenced. */ }
        }
        cachedOutputSolver=solver;
        return solver;
    }

    CombinedTypeSolver outputSolver(String module) {
        if (classpath.configured() && !solvers.containsKey(module)) throw new IllegalArgumentException("Missing authoritative output module");
        if (module != null && solvers.containsKey(module)) return solvers.get(module);
        return outputSolver();
    }

    void releaseUncachedFacades() {
        if(!retainedInProcessCache) synchronized(JavaParserFacade.class) { JavaParserFacade.clearInstances(); }
    }

    void outputContract(TypeDeclaration<?> type, Map<String, String> result, Set<String> visited, List<String> problems) {
        visitContract(type, result, visited, problems, false);
    }

    void classpathEvidence(Map<String, String> evidence) { evidence.put("analysis-classpath-v1", classpath.digest()); }

    void outputModule(String module) { analysisModule = module; }

    List<TypeDeclaration<?>> dispatchTypes(TypeDeclaration<?> caller) {
        if (classpath.configured()) {
            String module = analysisModule == null ? ServiceScopeResolver.modulePath(relative(caller)) : analysisModule;
            return sourceTypes().stream().filter(type -> classpath.sourceAvailable(module, ServiceScopeResolver.modulePath(relative(type)))).toList();
        }
        if (analysisModule != null && !analysisModule.equals(".")) {
            return sourceTypes().stream().filter(type -> {
                String candidateModule = ServiceScopeResolver.modulePath(relative(type));
                return candidateModule.equals(analysisModule) || candidateModule.equals(".");
            }).toList();
        }
        return sourceTypes();
    }


    void outputMethod(TypeDeclaration<?> owner, String name, String signature, int arity, MethodCallExpr call,
            Map<String, String> result, Set<String> visited, List<String> problems) {
        var methods = owner.getMethodsByName(name).stream().filter(method -> signature != null
                ? sameResolvedSignature(method, signature) : method.getParameters().size() == arity).toList();
        if (methods.isEmpty() && owner.isClassOrInterfaceDeclaration()) {
            var decl = owner.asClassOrInterfaceDeclaration();
            if (isSpringDataRepositoryMethod(decl, name, arity)) {
                outputContract(owner, result, visited, problems);
                result.put("resolved-call:org.springframework.data.repository.CrudRepository." + name, "external-or-source");
                return;
            }
            for (var ext : decl.getExtendedTypes()) {
                TypeDeclaration<?> parent = exactType(ext.getNameWithScope(), owner, problems);
                if (parent != null) {
                    var inherited = parent.getMethodsByName(name).stream().filter(method -> signature != null
                            ? sameResolvedSignature(method, signature) : method.getParameters().size() == arity).toList();
                    if (inherited.size() == 1) {
                        outputContract(owner, result, visited, problems);
                        outputMethod(parent, name, signature, arity, call, result, visited, problems);
                        return;
                    }
                }
            }
        }
        if (methods.size() != 1) { problems.add("unresolved output overload: " + identity(owner) + "#" + name); return; }
        if (signature == null && LombokSourceSymbols.isGenerated(methods.get(0))) {
            problems.add("unresolved generated output call: " + identity(owner) + "#" + name); return;
        }
        visitMethod(methods.get(0), result, visited, problems);
        var method = methods.get(0);
        if (owner.isClassOrInterfaceDeclaration() && !owner.asClassOrInterfaceDeclaration().isFinal()
                && !method.isPrivate() && !method.isFinal() && !method.isStatic()
                && (call == null || call.getScope().filter(scope -> scope.isObjectCreationExpr()).isEmpty())) {
            new SourceDispatchResolver(this).visit(owner, method, owner, result, visited, problems);
        }
    }

    private boolean isSpringDataRepositoryMethod(ClassOrInterfaceDeclaration decl, String name, int arity) {
        boolean isRepo = decl.isInterface() && (decl.getNameAsString().endsWith("Repository")
                || decl.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().contains("Repository")));
        if (!isRepo) return false;
        return switch (name) {
            case "findById", "existsById", "deleteById", "getById", "getReferenceById", "getOne" -> arity == 1;
            case "save", "saveAndFlush" -> arity == 1;
            case "saveAll", "findAllById", "deleteAllById", "deleteAll" -> arity <= 1;
            case "delete" -> arity == 1;
            case "count", "flush" -> arity == 0;
            case "findAll" -> arity <= 2;
            default -> false;
        };
    }

    synchronized List<ClassContextDto> resourceClasses(Map<String, String> dependencyEvidence) {
        Map<String, String> dependencies = new TreeMap<>(dependencyEvidence);
        dependencyEvidence.forEach((key, digest) -> {
            if (key.startsWith("dispatch-candidate:") && key.contains("|")) dependencies.put(key.substring(key.indexOf('|') + 1), digest);
        });
        List<ClassContextDto> result = new ArrayList<>();
        for (var candidates : types.values()) for (TypeDeclaration<?> type : candidates) {
            String identity = identity(type);
            if (!dependencies.containsKey(identity + "#contract") && !dependencies.containsKey(identity + "#dto")) continue;
            TypeDeclaration<?> slice = type.clone();
            LombokSourceSymbols.removeGenerated(slice);
            if (!dependencies.containsKey(identity + "#dto")) slice.getMembers().removeIf(member -> member.isMethodDeclaration()
                    && !dependencies.containsKey(identity + "#" + member.asMethodDeclaration().getSignature()));
            CompilationUnit source = type.findCompilationUnit().orElseThrow().clone();
            source.setTypes(new com.github.javaparser.ast.NodeList<>());
            source.addType(slice);
            boolean invoked = type.getMethods().stream().anyMatch(method -> dependencies.containsKey(identity + "#" + method.getSignature()));
            result.add(new ClassContextDto(null, null, type.getNameAsString(), invoked ? type.getFullyQualifiedName().orElseThrow() : null,
                    null, relative(type), source.toString(), List.of(), List.of()));
        }
        return result;
    }

    private void visitReferencedType(ClassOrInterfaceType reference, Map<String, String> result,
            Set<String> visited, List<String> problems, boolean dto) {
        if (reference.getParentNode().orElse(null) instanceof ClassOrInterfaceType parent
                && parent.getScope().filter(reference::equals).isPresent()) return;
        try {
            var resolved = reference.resolve();
            if (!resolved.isReferenceType()) return;
            TypeDeclaration<?> caller = reference.findAncestor(TypeDeclaration.class).orElseThrow();
            TypeDeclaration<?> sourceType = exactType(resolved.asReferenceType().getQualifiedName(), caller, problems);
            result.put("resolved-type:" + resolved.asReferenceType().getQualifiedName(), "external-or-source");
            if (sourceType != null) visitContract(sourceType, result, visited, problems,
                    dto && isDto(sourceType, caller, visited));
        } catch (RuntimeException | LinkageError exception) {
            // JavaParser 3.26 chưa resolve đầy đủ record; chỉ fallback theo package/import tường minh.
            TypeDeclaration<?> caller = reference.findAncestor(TypeDeclaration.class).orElseThrow();
            CompilationUnit unit = reference.findCompilationUnit().orElseThrow();
            String name = reference.getNameWithScope();
            List<String> names = new ArrayList<>();
            if (name.contains(".")) names.add(name);
            else {
                for (var imported : unit.getImports()) {
                    if (!imported.isAsterisk() && !imported.isStatic() && imported.getName().getIdentifier().equals(name)) names.add(imported.getNameAsString());
                }
                if (names.isEmpty()) names.add(unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + name);
            }
            if (names.size() == 1) {
                TypeDeclaration<?> sourceType = exactType(names.get(0), caller, problems);
                if (sourceType != null) {
                    visitContract(sourceType, result, visited, problems, dto && isDto(sourceType, caller, visited));
                    return;
                }
            }
            problems.add("unresolved type: " + reference.getNameAsString());
        }
    }

    private boolean isDto(TypeDeclaration<?> type, TypeDeclaration<?> caller, Set<String> visited) {
        if (visited.contains("output-dependency-mode") || identity(type).equals(identity(caller))
                || visited.contains("target-type:" + identity(type))) return false;
        return type.getAnnotations().stream().noneMatch(annotation -> Set.of("Service", "Repository", "Controller",
                "RestController", "Component").contains(annotation.getName().getIdentifier()));
    }

    private void visitFieldOwner(String name, TypeDeclaration<?> caller, Map<String, String> result,
            Set<String> visited, List<String> problems) {
        TypeDeclaration<?> sourceType = exactType(name, caller, problems);
        if (sourceType != null) visitContract(sourceType, result, visited, problems, false);
    }

    TypeDeclaration<?> exactType(String name, TypeDeclaration<?> caller, List<String> problems) {
        List<TypeDeclaration<?>> candidates = types.getOrDefault(name, List.of());
        if (candidates.isEmpty()) return null;
        String module = ServiceScopeResolver.modulePath(relative(caller));
        if (classpath.configured()) candidates = candidates.stream().filter(type -> classpath.sourceAvailable(module, ServiceScopeResolver.modulePath(relative(type)))).toList();
        if (candidates.isEmpty()) return null;
        var local = candidates.stream().filter(type -> ServiceScopeResolver.modulePath(relative(type)).equals(module)).toList();
        if (local.size() == 1) return local.get(0);
        if (candidates.size() == 1) return candidates.get(0);
        problems.add("unresolved source module: " + name);
        return null;
    }

    Map<String, String> resources(JsonNode resources, List<ClassContextDto> targets, List<String> problems) {
        Map<String, String> result = new TreeMap<>();
        Map<String, List<String>> evidenceDigests = new TreeMap<>();
        for (JsonNode evidence : resources) {
            try {
                String path = safeRelative(evidence.path("filePath").asText());
                checked(root, root.resolve(path));
                evidenceDigests.computeIfAbsent(evidence.path("kind").asText() + ":" + path, ignored -> new ArrayList<>())
                        .add(GenerationFingerprintService.hash(evidence.path("content").asText()));
            }
            catch (IOException exception) { problems.add("unresolved resource"); }
            catch (RuntimeException exception) { problems.add("unresolved resource"); }
        }
        evidenceDigests.forEach((key, values) -> result.put(key, GenerationFingerprintService.hash(
                values.stream().sorted().collect(java.util.stream.Collectors.joining("\n")))));
        for (ClassContextDto target : targets) {
            String module = ServiceScopeResolver.modulePath(target.filePath());
            result.putAll(com.greytest.service.agent.GenerationContextBuilder.inputSnapshot(new BuildReadKey(root, module),
                    () -> buildContracts(module)));
        }
        return result;
    }

    private Map<String, String> buildContracts(String module) {
        GenerationJobContext.increment("dependencyBuildReads");
        Map<String, String> result = new TreeMap<>();
            Path directory = module.equals(".") ? root : root.resolve(safeRelative(module));
            while (directory != null && directory.startsWith(root)) {
                for (String file : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties")) {
                    Path candidate = directory.resolve(file);
                    if (Files.exists(candidate)) addFile(root.relativize(candidate).toString(), result);
                }
                if (directory.equals(root)) break;
                directory = directory.getParent();
            }
        return Map.copyOf(result);
    }

    Map<String, String> sourceResources(Map<String, String> dependencies, List<String> problems) {
        var classes = resourceClasses(dependencies);
        var evidence = com.greytest.service.agent.GenerationResourceEvidence.collect(root, classes);
        for (String warning : evidence.warnings()) problems.add("unresolved resource evidence: " + warning);
        Map<String, String> result = resources(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(evidence.resources()), classes, problems);
        return result;
    }

    private void addFile(String path, Map<String, String> result) {
        try {
            String relative = safeRelative(path);
            Path candidate = root.resolve(relative);
            checked(root, candidate);
            result.put(relative, GenerationFingerprintService.hash(Files.readAllBytes(candidate)));
        } catch (IOException exception) { throw new IllegalArgumentException("Unreadable dependency file", exception); }
    }

    private static void checked(Path root, Path path) throws IOException {
        if (!path.normalize().startsWith(root) || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root)) throw new IllegalArgumentException("Unsafe source path");
    }
    private static String safeRelative(String path) { return com.greytest.service.agent.GenerationSourceFingerprint.safePath(path); }
    String relative(Node node) {
        String cached = relativePaths.get(node);
        return cached != null ? cached : root.relativize(node.findCompilationUnit().orElseThrow()
                .getStorage().orElseThrow().getPath()).toString().replace('\\', '/');
    }
    String identity(TypeDeclaration<?> type) {
        String cached = typeIdentities.get(type);
        return cached != null ? cached : ServiceScopeResolver.modulePath(relative(type)) + ":" + type.getFullyQualifiedName().orElseThrow();
    }
    List<TypeDeclaration<?>> sourceTypes() { return types.values().stream().flatMap(List::stream).toList(); }
    private static String simple(String type) { return type.replace("java.lang.", "").replace(" ", ""); }
    private static String digest(Node node) {
        Node copy = node.clone(); copy.getAllContainedComments().forEach(comment -> comment.remove()); copy.removeComment();
        return GenerationFingerprintService.hash(copy.toString());
    }
}
