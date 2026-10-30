package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import org.junit.jupiter.api.Test;

class EnrichmentStubValidationTest {
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test void rejectsTheActual3971ResponseBeforeItCanBeSaved() throws Exception {
        var f = fixture();
        assertThat(validate(f)).contains("Missing collaborator stub", "patientServiceClient.getPatientById", "doctorServiceClient.getDoctorById");
    }

    @Test void validationDoesNotDependOnDtoOrClientNames() throws Exception {
        String fixture = json.writeValueAsString(fixture()).replace("PatientDto", "MemberDetails")
                .replace("DoctorDto", "PractitionerDetails").replace("patientServiceClient", "memberClient")
                .replace("doctorServiceClient", "practitionerClient").replace("getPatientById", "loadMember").replace("getDoctorById", "loadPractitioner");
        assertThat(validate(json.readTree(fixture))).contains("Missing collaborator stub: memberClient.loadMember",
                "Missing collaborator stub: practitionerClient.loadPractitioner");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"patient", "doctor"})
    void identifiesTheRemainingMissingStub(String configured) throws Exception {
        var f = fixture(); var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String stub = configured.equals("patient") ? "org.mockito.Mockito.when(patientServiceClient.getPatientById(2001L)).thenReturn(patient);"
                : "org.mockito.Mockito.when(doctorServiceClient.getDoctorById(3001L)).thenReturn(doctor);";
        unit.put("source_code", unit.path("source_code").asText().replace("// Act", stub + "\n// Act"));
        assertThat(validate(f)).contains("Missing collaborator stub", configured.equals("patient") ? "doctorServiceClient.getDoctorById" : "patientServiceClient.getPatientById")
                .doesNotContain("Missing collaborator stub: " + configured + "ServiceClient");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void acceptsBothVerifiedWhenAndDoReturnStubs(boolean doReturn) throws Exception {
        var f = fixture(); var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String stubs = doReturn
                ? "org.mockito.Mockito.doReturn(patient).when(patientServiceClient).getPatientById(2001L); org.mockito.Mockito.doReturn(doctor).when(doctorServiceClient).getDoctorById(3001L);"
                : "org.mockito.Mockito.when(patientServiceClient.getPatientById(2001L)).thenReturn(patient); org.mockito.Mockito.when(doctorServiceClient.getDoctorById(3001L)).thenReturn(doctor);";
        unit.put("source_code", unit.path("source_code").asText().replace("// Act", stubs + "\n// Act"));
        assertThat(validate(f)).isEmpty();
    }

    @Test void doesNotRequireEnrichmentForAssertionsOnFallbackFieldsOnly() throws Exception {
        var f = fixture(); var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().lines()
                .filter(l -> !l.contains("result.getPatientName()") && !l.contains("result.getDoctorName()") && !l.contains("result.getDoctorSpecialization()"))
                .collect(java.util.stream.Collectors.joining("\n")));
        assertThat(validate(f)).doesNotContain("Missing collaborator stub");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"service", "result"})
    void doesNotAttributeAssertionsToReassignedLocals(String name) throws Exception {
        var f = fixture(); var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String source = unit.path("source_code").asText();
        source = name.equals("service") ? source.replace("// Act", "service = new AppointmentService(appointmentRepository, patientServiceClient, doctorServiceClient);\n// Act")
                : source.replace("// Assert", "result = new AppointmentResponseDto(appointment);\n// Assert");
        unit.put("source_code", source);
        assertThat(validate(f)).doesNotContain("Missing collaborator stub");
    }

    @Test void fallbackWithItsOwnEnrichmentIsNotAssumedToReturnNull() throws Exception {
        var f = fixture();
        for (var type : f.path("context").path("classes")) if (type.path("className").asText().equals("AppointmentResponseDto")) {
            ((ObjectNode) type).put("sourceCode", type.path("sourceCode").asText().replace("this.id = appointment.getId();",
                    "this.patientName = \"An Nguyễn\"; this.doctorName = \"Dr. Bình Trần\"; this.doctorSpecialization = \"Tim mạch\"; this.id = appointment.getId();"));
        }
        assertThat(validate(f)).doesNotContain("Missing collaborator stub");
    }

    @Test void constructorSideEffectsCannotProveNullFallbackFields() throws Exception {
        var f = fixture();
        for (var type : f.path("context").path("classes")) if (type.path("className").asText().equals("AppointmentResponseDto")) {
            String source = type.path("sourceCode").asText().replace("this.id = appointment.getId();", "this.id = populate(appointment);");
            int end = source.lastIndexOf('}');
            source = source.substring(0, end) + "private Long populate(Appointment a) { this.patientName=\"An Nguyễn\"; this.doctorName=\"Dr. Bình Trần\"; this.doctorSpecialization=\"Tim mạch\"; return a.getId(); } }";
            ((ObjectNode) type).put("sourceCode", source);
        }
        assertThat(validate(f)).doesNotContain("Missing collaborator stub");
    }

    @Test void unknownSetupCannotBeAssumedToPreserveConstructorInjection() throws Exception {
        var f = fixture(); var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("// Act",
                "org.springframework.test.util.ReflectionTestUtils.setField(service, \"patientServiceClient\", org.mockito.Mockito.mock(PatientServiceClient.class));\n// Act"));
        assertThat(validate(f)).doesNotContain("Missing collaborator stub");
    }

    @Test void generationRetriesMissingStubsAndPreservesTheApprovedAssertions() throws Exception {
        var f = fixture(); String invalid = json.writeValueAsString(f.path("response"));
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("// Act",
                "org.mockito.Mockito.when(patientServiceClient.getPatientById(2001L)).thenReturn(patient); "
                + "org.mockito.Mockito.when(doctorServiceClient.getDoctorById(3001L)).thenReturn(doctor);\n// Act"));
        var contexts = org.mockito.Mockito.mock(GenerationContextBuilder.class);
        org.mockito.Mockito.when(contexts.buildUnitTestContext(911L)).thenReturn(context(f));
        var prompts = org.mockito.Mockito.mock(PromptManager.class);
        org.mockito.Mockito.when(prompts.render(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn("APPROVED_ENRICHMENT");
        var client = org.mockito.Mockito.mock(LlmClient.class);
        org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(invalid, json.writeValueAsString(f.path("response")));
        try (var validators = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var agent = new AIAgentService(contexts, prompts, client, new GenerationResponseParser(json, validators.getValidator()),
                    org.mockito.Mockito.mock(AiContextLogService.class));
            var result = agent.generateUnitTests(911L);
            assertThat(result.unitTests().get(0).sourceCode()).contains("assertEquals(\"An Nguyễn\", result.getPatientName())",
                    "when(patientServiceClient.getPatientById(2001L))", "when(doctorServiceClient.getDoctorById(3001L))");
            var attempts = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).complete(attempts.capture(), org.mockito.ArgumentMatchers.any());
            assertThat(attempts.getAllValues().get(1)).contains("Missing collaborator stub", "patientServiceClient.getPatientById");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"locals", "parameters", "branch", "finally"})
    void doesNotAssumeNullAfterSourceReassignsOrUsesAnotherBranch(String kind) throws Exception {
        var f = fixture();
        String patient = "new PatientDto(2001L, \"An\", \"Nguyễn\", null, null)";
        String doctor = "new DoctorDto(3001L, \"Bình\", \"Trần\", \"Tim mạch\", true)";
        for (var type : f.path("context").path("classes")) if (type.path("className").asText().equals("AppointmentService")) {
            String source = type.path("sourceCode").asText();
            if (kind.equals("locals")) source = source.replace("return enrichAppointmentResponse(appointment, patient, doctor);",
                    "patient = " + patient + "; doctor = " + doctor + "; return enrichAppointmentResponse(appointment, patient, doctor);");
            else if (kind.equals("parameters")) source = source.replace("AppointmentResponseDto response = new AppointmentResponseDto(appointment);",
                    "patient = " + patient + "; doctor = " + doctor + "; AppointmentResponseDto response = new AppointmentResponseDto(appointment);");
            else if (kind.equals("finally")) source = source.replace("return enrichAppointmentResponse(appointment);",
                    "try { return enrichAppointmentResponse(appointment); } finally { return enrichAppointmentResponse(appointment, " + patient + ", " + doctor + "); }");
            else source = source.replace("try {", "if (appointment.getId() == 1001L) return enrichAppointmentResponse(appointment, " + patient + ", " + doctor + "); try {");
            ((ObjectNode) type).put("sourceCode", source);
        }
        assertThat(validate(f)).doesNotContain("Missing collaborator stub");
    }

    private JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent/enrichment-missing-stubs.json")) { return json.readTree(input); }
    }
    private String validate(JsonNode f) throws Exception {
        return GeneratedUnitTestSemanticValidator.validate(context(f),
                json.treeToValue(f.path("response"), UnitTestResponseDto.class)).orElse("");
    }
    private UnitTestContextDto context(JsonNode f) throws Exception {
        var c = json.treeToValue(f.path("context"), UnitTestContextDto.class);
        var classes = c.classes().stream().map(t -> new com.greytest.dto.agent.GenerationContextDtos.ClassContextDto(
                t.id(), t.packageName(), t.className(), t.qualifiedName(), t.classType(), t.filePath(), t.sourceCode(), t.annotations(), t.methods())).toList();
        var context = new UnitTestContextDto(c.project(), c.analysis(), classes, c.approvedBusinessRules(), c.approvedTestPlans(),
                c.approvedTestCases(), c.existingApprovedTestCases(), c.previousGeneratedUnitTests(), c.existingTests(), c.supportingResources(), c.contextWarnings());
        return context;
    }
}
