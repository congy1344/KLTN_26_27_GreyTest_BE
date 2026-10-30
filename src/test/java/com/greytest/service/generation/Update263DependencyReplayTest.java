package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;

class Update263DependencyReplayTest {
    @TempDir Path root;

    @Test
    void all25ExistingOutputsAndTheirSourceClosuresAreVerifiable() throws Exception {
        try (var zip = new ZipInputStream(getClass().getResourceAsStream("/generation/update263-source.zip"))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path file = root.resolve(entry.getName()).normalize();
                assertThat(file.startsWith(root)).isTrue();
                Files.createDirectories(file.getParent()); Files.write(file, zip.readAllBytes());
            }
        }
        var index = SourceDependencyIndex.load(root);
        var json = new ObjectMapper();
        var saved = json.readTree(getClass().getResourceAsStream("/generation/update263-existing-units.json"));
        assertThat(saved.size()).isEqualTo(25);
        String path = "backend/appointment-service/src/main/java/com/hospital/appointment/service/AppointmentService.java";
        String source = Files.readString(root.resolve(path));
        var type = StaticJavaParser.parse(source).getClassByName("AppointmentService").orElseThrow();
        for (var unit : saved) {
            var problems = new ArrayList<String>();
            new GeneratedOutputDependencyResolver(index, problems, "backend/appointment-service")
                    .resolve(List.of(unit.path("source_code").asText()));
            assertThat(problems).as("case %s output", unit.path("case_id")).isEmpty();
            var method = type.getMethodsByName(unit.path("method_name").asText()).get(0);
            var dto = new MethodContextDto(unit.path("method_id").asLong(), "com.hospital.appointment.service.AppointmentService",
                    method.getNameAsString(), method.getTypeAsString(), method.getParameters().stream()
                            .map(p -> new MethodParamDto(p.getNameAsString(), p.getTypeAsString())).toList(), List.of(),
                    "PUBLIC", method.toString(), 1, 1, List.of(), List.of(), List.of());
            var owner = new ClassContextDto(1L, "com.hospital.appointment.service", "AppointmentService",
                    "com.hospital.appointment.service.AppointmentService", "SERVICE", path, source, List.of(), List.of(dto));
            problems.clear(); index.dependencies(List.of(owner), problems);
            assertThat(problems).as("case %s source", unit.path("case_id")).isEmpty();
        }
        var drafts = json.readTree(getClass().getResourceAsStream("/generation/update263-saved-drafts.json"));
        assertThat(drafts.size()).isEqualTo(16);
        for (var draft : drafts) {
            var problems = new ArrayList<String>();
            new GeneratedOutputDependencyResolver(index, problems, "backend/appointment-service")
                    .resolve(List.of(draft.path("source_code").asText()));
            assertThat(problems).as("saved draft case %s", draft.path("case_id")).isEmpty();
        }
    }
}
