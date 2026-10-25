package com.greytest.service.agent;

import java.nio.file.Path;
import java.util.List;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto;

/** Chia sẻ resource evidence hiện có cho fingerprint mà không đọc toàn bộ cấu hình vào provenance. */
public final class GenerationResourceEvidence {
    private GenerationResourceEvidence() {}

    public record Evidence(List<ResourceEvidenceDto> resources, List<String> warnings) {}

    public static Evidence collect(Path sourceRoot, List<ClassContextDto> classes) {
        var evidence = BusinessRuleResourceCollector.collect(sourceRoot, classes);
        boolean lombok = classes.stream().anyMatch(type -> type.sourceCode() != null && type.sourceCode().contains("lombok."));
        return new Evidence(evidence.resources().stream().filter(resource -> lombok || !resource.kind().equals("LOMBOK_CONFIGURATION")).toList(),
                evidence.warnings().stream().filter(warning -> lombok || !warning.contains("Lombok")).toList());
    }
}
