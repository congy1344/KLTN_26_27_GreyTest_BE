package com.greytest.service.analysis;

import java.util.List;
import java.util.Map;

import com.greytest.service.analysis.JavaParserHelper.ParsedFile;

/** Resolve field dependency tới repository type đã được xác nhận trong project. */
final class RepositoryTypeResolver {

    private RepositoryTypeResolver() {
    }

    static Long resolve(
            String repositoryType,
            String packageName,
            ParsedFile parsedFile,
            Map<String, List<Long>> qualifiedNameToIds,
            Map<String, List<Long>> simpleNameToIds) {
        Long directMatch = uniqueId(qualifiedNameToIds.get(repositoryType));
        if (directMatch != null) return directMatch;

        String importedName = parsedFile.compilationUnit().getImports().stream()
                .filter(importDecl -> !importDecl.isAsterisk() && !importDecl.isStatic())
                .map(importDecl -> importDecl.getNameAsString())
                .filter(name -> name.endsWith("." + repositoryType))
                .findFirst()
                .orElse(null);
        Long importedMatch = uniqueId(qualifiedNameToIds.get(importedName));
        if (importedMatch != null) return importedMatch;

        Long samePackageMatch = uniqueId(qualifiedNameToIds.get(packageName + "." + repositoryType));
        if (samePackageMatch != null) return samePackageMatch;
        return uniqueId(simpleNameToIds.get(repositoryType));
    }

    static Long uniqueId(List<Long> ids) {
        return ids != null && ids.size() == 1 ? ids.get(0) : null;
    }
}
