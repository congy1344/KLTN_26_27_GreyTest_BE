package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;

class AppointmentFormatReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @ParameterizedTest(name = "{0}")
    @MethodSource("savedCases")
    void acceptsTheSavedSourceProvenRuntimeMessageRequirement(String log, JsonNode saved) throws Exception {
        var classes = JSON.convertValue(fixture().get("classes"), new TypeReference<java.util.List<ClassContextDto>>() { });
        Map<String, Object> input = JSON.convertValue(saved.get("input"), new TypeReference<>() { });
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, saved.path("methodId").asLong(),
                saved.path("expectedResult").asText(), input, true)).as(log).isEmpty();
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, saved.path("methodId").asLong(),
                saved.path("expectedResult").asText().replace("Failed to find the appointment", "Wrong appointment"), input, true))
                .as("wrong template: " + log).isPresent();
        assertThat(new SourceExceptionMessageEvidence(classes).validate(classes, saved.path("methodId").asLong(),
                saved.path("expectedResult").asText().replace("appointmentId", "otherId"), input, true))
                .as("wrong argument: " + log).isPresent();
    }

    static Stream<Arguments> savedCases() throws Exception {
        return java.util.stream.StreamSupport.stream(fixture().path("cases").spliterator(), false)
                .map(saved -> Arguments.of(saved.path("sourceLog").asText(), saved));
    }

    private static JsonNode fixture() throws Exception {
        try (var stream = AppointmentFormatReplayTest.class.getResourceAsStream("/agent/appointment-format-replay.json")) {
            return JSON.readTree(stream);
        }
    }
}
