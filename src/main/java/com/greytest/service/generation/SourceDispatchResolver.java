package com.greytest.service.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

/** Chỉ nối implementation khi quan hệ type và overload có bằng chứng; ambiguity chặn reuse. */
final class SourceDispatchResolver {
    private final SourceDependencyIndex index;

    SourceDispatchResolver(SourceDependencyIndex index) { this.index = index; }

    void visit(TypeDeclaration<?> contract, MethodDeclaration declaration, TypeDeclaration<?> caller,
            Map<String, String> dependencies, Set<String> visited, List<String> problems) {
        List<String> ancestryProblems = new ArrayList<>();
        try {
        List<TypeDeclaration<?>> matches = new ArrayList<>();
        if(contract.isClassOrInterfaceDeclaration() && !contract.asClassOrInterfaceDeclaration().isInterface()
                && !contract.asClassOrInterfaceDeclaration().isAbstract()) matches.add(contract);
        for (TypeDeclaration<?> candidate : index.dispatchTypes(caller)) {
            if (!candidate.isClassOrInterfaceDeclaration()) continue;
            var type = candidate.asClassOrInterfaceDeclaration();
            if (type.isInterface() || type.isAbstract()) continue;
            if (assignable(candidate, contract, new HashSet<>(), ancestryProblems)) matches.add(candidate);
        }
        String dispatchKey = index.identity(contract) + "#" + declaration.getSignature();
        if (matches.isEmpty()) {
            dependencies.put("dispatch-source-contract:" + dispatchKey, "no-source-implementation");
            return;
        }
        if (matches.size() == 1) {
            TypeDeclaration<?> implementation = matches.get(0);
            var overrides = overrides(implementation, declaration, ancestryProblems);
            if (overrides.size() == 1) {
                index.outputContract(implementation, dependencies, visited, problems);
                index.visitMethod(overrides.get(0), dependencies, visited, problems);
                return;
            }
            if (overrides.isEmpty() && declaration.getBody().isPresent()) return;
        }
        problems.add("unresolved dynamic dispatch: " + dispatchKey);
        for (TypeDeclaration<?> implementation : matches) {
            Map<String, String> possible = new TreeMap<>();
            Set<String> possibleVisited = new HashSet<>(visited);
            possibleVisited.add("output-dependency-mode");
            index.outputContract(implementation, possible, possibleVisited, problems);
            var methods = inheritedMethods(implementation,declaration,new HashSet<>(),false,ancestryProblems);
            for (MethodDeclaration method : methods) index.visitMethod(method, possible, possibleVisited, problems);
            String prefix = "dispatch-candidate:" + index.identity(implementation) + "|";
            possible.forEach((key, digest) -> dependencies.put(prefix + key, digest));
        }
        } finally {
            problems.addAll(ancestryProblems);
        }
    }

    private List<MethodDeclaration> overrides(TypeDeclaration<?> type, MethodDeclaration contract, List<String> problems) {
        return inheritedMethods(type,contract,new HashSet<>(),true,problems);
    }

    private List<MethodDeclaration> inheritedMethods(TypeDeclaration<?> type,MethodDeclaration contract,Set<String> visited,boolean exact,List<String> problems) {
        if(!visited.add(index.identity(type))) return List.of();
        var declared=type.getMethodsByName(contract.getNameAsString()).stream().filter(method-> {
            if(method.getBody().isEmpty()) return false;
            if(!exact) return method.getParameters().size()==contract.getParameters().size();
            try { return method.resolve().getSignature().equals(contract.resolve().getSignature()); }
            catch(RuntimeException | LinkageError unresolved) { problems.add("unresolved dispatch signature: " + index.identity(type)); return false; }
        }).toList();
        // Override gần nhất che phương thức cùng signature ở lớp cha; không chọn theo tên đơn thuần.
        if(exact && !declared.isEmpty()) return declared;
        var result=new ArrayList<>(declared);
        if(type.isClassOrInterfaceDeclaration()) {
            var owner=type.asClassOrInterfaceDeclaration();
            for(var reference:owner.getExtendedTypes()) {
                var parent=parentType(reference,type,problems);
                if(parent!=null) result.addAll(inheritedMethods(parent,contract,visited,exact,problems));
            }
            if(exact && !result.isEmpty()) return result;
            for(var reference:owner.getImplementedTypes()) {
                var parent=parentType(reference,type,problems);
                if(parent!=null) result.addAll(inheritedMethods(parent,contract,visited,exact,problems));
            }
        }
        return result;
    }

    private boolean assignable(TypeDeclaration<?> candidate, TypeDeclaration<?> target, Set<String> visited, List<String> problems) {
        if (!visited.add(index.identity(candidate)) || !candidate.isClassOrInterfaceDeclaration()) return false;
        var declaration = candidate.asClassOrInterfaceDeclaration();
        List<ClassOrInterfaceType> parents = new ArrayList<>(declaration.getImplementedTypes());
        parents.addAll(declaration.getExtendedTypes());
        for (var reference : parents) {
            try {
                var resolved = reference.resolve().asReferenceType();
                TypeDeclaration<?> parent = index.exactType(resolved.getQualifiedName(), candidate, problems);
                if (parent != null && (index.identity(parent).equals(index.identity(target)) || assignable(parent, target, visited, problems))) return true;
                if (parent == null && resolved.getAllAncestors().stream().anyMatch(ancestor -> ancestor.getQualifiedName().equals(target.getFullyQualifiedName().orElse("")))) {
                    problems.add("unresolved external dispatch implementation: " + index.identity(candidate));
                    return true;
                }
            } catch (RuntimeException | LinkageError unresolved) {
                problems.add("unresolved dispatch parent: " + index.identity(candidate) + "#" + reference.getNameWithScope());
            }
        }
        return false;
    }

    private TypeDeclaration<?> parentType(ClassOrInterfaceType reference, TypeDeclaration<?> caller, List<String> problems) {
        try {
            var resolved = reference.resolve().asReferenceType();
            var parent = index.exactType(resolved.getQualifiedName(), caller, problems);
            if (parent == null && !resolved.getQualifiedName().startsWith("java.")) problems.add("unresolved inherited dispatch body: " + resolved.getQualifiedName());
            return parent;
        } catch (RuntimeException | LinkageError exception) {
            problems.add("unresolved dispatch parent: " + index.identity(caller) + "#" + reference.getNameWithScope()); return null;
        }
    }
}
