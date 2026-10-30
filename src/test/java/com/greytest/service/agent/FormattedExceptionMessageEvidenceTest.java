package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;

class FormattedExceptionMessageEvidenceTest {
    @Test
    void acceptsTheCorrectedResourceExceptionFromProject907Log() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var stream = getClass().getResourceAsStream("/agent/resource-format-replay.json")) {
            var saved = json.readTree(stream);
            List<ClassContextDto> classes = json.convertValue(saved.get("classes"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            String claim = saved.path("description").asText();
            var evidence = new SourceExceptionMessageEvidence(classes);
            var context = new com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto(
                    null, null, classes, List.of(), List.of(), List.of(), List.of());
            var response = json.treeToValue(saved.get("response"),
                    com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto.class);
            assertThat(UpstreamSourceEvidenceValidator.businessRules(context, response)).isEmpty();
            assertThat(evidence.validate(classes, 21233L, claim, null, true)).isEmpty();
            assertThat(evidence.validate(classes, 21233L, claim.replace("Appointment", "Doctor"), null, true)).isPresent();
            assertThat(evidence.validate(classes, 21233L, claim.replace(", id.", ", otherId."), null, true)).isPresent();
        }
    }

    @Test
    void preservesAProvenTemplateWithoutSubstitutionArguments() {
        var classes = sources("DepartmentNotFoundException", "", "",
                "throw new DepartmentNotFoundException(String.format(\"No data %%\"));");
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"No data %%\"", null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DepartmentNotFoundException với thông điệp \"Failed to find the department with id %d\" được định dạng bằng departmentId",
            "DepartmentNotFoundException với thông điệp được tạo bằng String.format từ mẫu \"Failed to find the department with id %d\" và departmentId",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId"})
    void acceptsTheSourceFormatTemplateWithItsActualArgument(String claim) {
        assertThat(validate(departmentSources(""), claim, null)).isEmpty();
        assertThat(validate(departmentSources(""), claim, Map.of("departmentId", 42))).isEmpty();
    }

    @Test
    void acceptsTheMedicalRecordFormatFromTheReportedLog() {
        var classes = sources("MedicalRecordNotFoundException", "Long recordId", "",
                "throw new MedicalRecordNotFoundException(String.format(\"Failed to fetch the medical record for id %d\", recordId));");
        assertThat(validate(classes, "MedicalRecordNotFoundException với thông điệp \"Failed to fetch the medical record for id %d\" được định dạng bằng recordId", null))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DepartmentNotFoundException message from template \"Failed to find the department with id %s\" using departmentId",
            "DepartmentNotFoundException message from template \"Wrong department %d\" using departmentId",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using otherId",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId and otherId",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId + 1",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId.longValue()",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId . longValue()",
            "DepartmentNotFoundException message equals String.format(\"Failed to find the department with id %d\", departmentId + 1)",
            "DepartmentNotFoundException message equals String.format(\"Failed to find the department with id %d\", departmentId.longValue())",
            "DepartmentNotFoundException message equals String.format(\"Failed to find the department with id %d\", otherId)",
            "DepartmentNotFoundException message equals String.format(\"Failed to find the department with id %d\", departmentId,)",
            "DepartmentNotFoundException message equals String.format(\"Wrong department %d\", departmentId)",
            "DepartmentNotFoundException message equals String.format(\"Failed to find the department with id %d\", departmentId) + \"wrong\"",
            "DepartmentNotFoundException. Template thông điệp nguồn: \"Failed to find the department with id %d\"; tham số theo thứ tự nguồn: otherId.",
            "DepartmentNotFoundException message from template \"Failed to find the department with id %d\"",
            "DepartmentNotFoundException message \"Failed to find the department with id %d\"",
            "DepartmentNotFoundException message \"Failed to find the department with id 42\""})
    void rejectsWrongTemplatesArgumentsAndUnprovenRenderedMessages(String claim) {
        assertThat(validate(departmentSources(""), claim, Map.of("departmentId", 42))).isPresent();
    }

    @Test
    void aCorrectTemplateDoesNotHideAnIncorrectCompleteMessage() {
        assertThat(validate(departmentSources(""),
                "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId; "
                + "DepartmentNotFoundException message \"Department missing\"", null)).isPresent();
    }

    @Test
    void preservesTheSourceArgumentOrder() {
        var classes = sources("DepartmentNotFoundException", "Long departmentId, Long otherId", "",
                "throw new DepartmentNotFoundException(String.format(\"Missing %d/%d\", departmentId, otherId));");
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d/%d\" using departmentId and otherId", null)).isEmpty();
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d/%d\" using otherId and departmentId", null)).isPresent();
    }

    @Test
    void doesNotClaimAnUnchangedInputAfterTheSourceMutatesIt() {
        assertThat(validate(departmentSources("departmentId++;"),
                "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId", null)).isPresent();
    }

    @Test
    void doesNotUseAnUnrelatedFormatCallAsExceptionMessageEvidence() {
        var classes = sources("DepartmentNotFoundException", "Long departmentId", "",
                "String preview = String.format(\"Missing %d\", departmentId); throw new DepartmentNotFoundException(\"actual\");");
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d\" using departmentId", null)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"String", "Number", "Double", "UnresolvedType", "long...", "long[]"})
    void rejectsAnIncompatibleSourceArgumentForDecimalFormatting(String type) {
        var classes = sources("DepartmentNotFoundException", type + " departmentId", "",
                "throw new DepartmentNotFoundException(String.format(\"Missing %d\", departmentId));");
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d\" using departmentId", null))
                .isPresent();
    }

    @Test
    void allowsSentencePunctuationAfterTheFormatArguments() {
        assertThat(validate(departmentSources(""),
                "DepartmentNotFoundException message from template \"Failed to find the department with id %d\" using departmentId. The complete message is computed at runtime.", null))
                .isEmpty();
    }

    @Test
    void doesNotConfuseConstructorParametersWithTheSelectedMethodInputs() {
        var classes = sourceClasses("void run(Long departmentId, Long otherId) { throw new DepartmentNotFoundException(otherId); }",
                "static class DepartmentNotFoundException extends RuntimeException { DepartmentNotFoundException(Long departmentId) { "
                + "super(String.format(\"Missing %d\", departmentId)); } }");
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d\" using departmentId", null))
                .isPresent();
    }

    @Test
    void bindsConstructorFormatArgumentsToTheCallingMethod() {
        var classes = sourceClasses("void run(Long departmentId, Long otherId) { throw new DepartmentNotFoundException(otherId); }",
                "static class DepartmentNotFoundException extends RuntimeException { DepartmentNotFoundException(Long departmentId) { "
                + "super(String.format(\"Missing %d\", departmentId)); } }");
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d\" using otherId", null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ResourceNotFoundException message from template \"%s not found with %s: '%s'\" using \"Appointment\", \"id\", id",
            "ResourceNotFoundException với thông điệp được tạo bằng String.format từ mẫu \"%s not found with %s: '%s'\" và các tham số \"Appointment\", \"id\", id.",
            "Khi không tìm thấy appointment theo id, service ném ResourceNotFoundException với thông điệp được tạo bằng String.format từ mẫu \"%s not found with %s: '%s'\" với các đối số \"Appointment\", \"id\", id theo đúng thứ tự, và yêu cầu thông điệp hoàn chỉnh do chính formatter đó tính tại runtime.",
            "Khi xóa lịch hẹn theo id, service tìm lịch hẹn bằng appointmentRepository.findById và ném ResourceNotFoundException với thông điệp được tạo từ mẫu \"%s not found with %s: '%s'\" cùng các tham số \"Appointment\", \"id\", id nếu không tìm thấy.",
            "Khi không tìm thấy appointment theo id được truyền vào thông qua appointmentRepository.findById, service ném ra ResourceNotFoundException với thông điệp được tạo bằng String.format từ mẫu \"%s not found with %s: '%s'\" sử dụng \"Appointment\", \"id\", id.",
            "Khi không tìm thấy appointment theo id, service ném ResourceNotFoundException với thông điệp từ mẫu \"%s not found with %s: '%s'\" với các tham số \"Appointment\", \"id\", và id.",
            "ResourceNotFoundException message from template \"%s not found with %s: '%s'\" using \"Appointment\", \"id\", and id",
            "Ném ResourceNotFoundException. Thông điệp phải bằng toàn bộ kết quả được tính lúc chạy bằng String.format với mẫu \"%s not found with %s: '%s'\" và các tham số theo đúng thứ tự \"Appointment\", \"id\", id; không chỉ kiểm tra loại exception. SOURCE_MISMATCH: source dùng đúng mẫu và tham số đã duyệt.",
            "Ném ResourceNotFoundException. Thông điệp phải bằng toàn bộ kết quả được tính lúc chạy bằng String.format với mẫu \"%s not found with %s: '%s'\" và các tham số theo đúng thứ tự \"Appointment\", \"id\", id; không chỉ kiểm tra loại exception. Không gọi save và không thực hiện enrichment.",
            "Ném ResourceNotFoundException. Thông điệp phải bằng toàn bộ kết quả được tính lúc chạy bằng String.format từ template \"%s not found with %s: '%s'\" với các tham số theo đúng thứ tự \"Appointment\", \"id\", id; không chỉ kiểm tra loại exception.",
            "Ném ResourceNotFoundException. Toàn bộ message phải bằng kết quả runtime của String.format với template \"%s not found with %s: '%s'\" và các đối số theo đúng thứ tự \"Appointment\", \"id\", id; không chỉ kiểm tra loại exception.",
            "Ném ResourceNotFoundException. Toàn bộ message phải bằng kết quả runtime của String.format với template \"%s not found with %s: '%s'\" và các đối số theo đúng thứ tự \"Appointment\", \"id\", id; yêu cầu so sánh toàn bộ message, gồm dấu câu, không chỉ kiểm tra loại exception.",
            "Ném ResourceNotFoundException. Thông điệp phải bằng toàn bộ nội dung được tính lúc chạy bằng formatter nguồn String.format với template \"%s not found with %s: '%s'\" và các tham số theo đúng thứ tự \"Appointment\", \"id\", id; không chỉ kiểm tra loại ngoại lệ hoặc một phần thông điệp.",
            "Service ném ResourceNotFoundException. Toàn bộ thông điệp phải bằng kết quả String.format được tính lúc chạy từ mẫu \"%s not found with %s: '%s'\" với các tham số \"Appointment\", \"id\", id, bao gồm nguyên dấu câu trong mẫu. Không xóa lịch hẹn vì không tìm thấy.",
            "Service ném ResourceNotFoundException. Toàn bộ thông điệp phải bằng kết quả String.format được tính lúc chạy từ mẫu \"%s not found with %s: '%s'\" với các tham số \"Appointment\", \"id\", id, bao gồm nguyên dấu câu trong mẫu. Không cập nhật hoặc lưu lịch hẹn.",
            "Service ném ResourceNotFoundException. Thông điệp phải bằng toàn bộ kết quả runtime của String.format với template \"%s not found with %s: '%s'\" using \"Appointment\", \"id\", id, giữ nguyên dấu câu và giá trị tham số id; không chỉ kiểm tra loại exception hoặc một phần thông điệp.",
            "ResourceNotFoundException message equals String.format(\"%s not found with %s: '%s'\", \"Appointment\", \"id\", id)"})
    void acceptsLiteralAndBoundArgumentsFromResourceException(String claim) {
        var classes = resourceSources();
        assertThat(validate(classes, claim, null)).isEmpty();
        assertThat(validate(classes, claim, Map.of("id", 42))).isEmpty();
        assertThat(validate(classes, claim.replace("Appointment", "Doctor"), null)).isPresent();
        assertThat(validate(classes, claim.replace(", id", ", otherId").replace("và id", "và otherId").replace("and id", "and otherId"), null)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Khi đã tìm được một appointment xung đột cho cùng bác sĩ, ngày và giờ hẹn thông qua appointmentRepository.findConflictingAppointment, service ném ra IllegalArgumentException với thông điệp được tạo bằng nối chuỗi từ \"Doctor already has an appointment at \" nối với requestDto.getAppointmentTime(), nối với \" on \", nối với requestDto.getAppointmentDate().",
            "Khi tạo lịch hẹn, service gọi appointmentRepository.findConflictingAppointment với mã bác sĩ, ngày và giờ trong yêu cầu; nếu kết quả có lịch trùng, service ném IllegalArgumentException với thông điệp gồm \"Doctor already has an appointment at \" nối với giờ, \" on \" và ngày trong yêu cầu.",
            "Ném IllegalArgumentException với thông điệp được ghép chuỗi từ tiền tố \"Doctor already has an appointment at \" và thời gian hẹn, nối với \" on \" và ngày hẹn.",
            "Ném IllegalArgumentException với thông điệp được tạo bằng cách nối \"Doctor already has an appointment at \" với giá trị giờ hẹn, \" on \" và ngày hẹn."})
    void acceptsVietnameseConcatenatedExceptionMessageClaims(String claim) {
        var classes = sources("IllegalArgumentException", "AppointmentRequestDto requestDto",
                "static class AppointmentRequestDto { String getAppointmentTime() { return \"09:00\"; } String getAppointmentDate() { return \"2026-10-30\"; } } ",
                "throw new IllegalArgumentException(\"Doctor already has an appointment at \" + requestDto.getAppointmentTime() + \" on \" + requestDto.getAppointmentDate());");
        assertThat(validate(classes, claim, null)).isEmpty();
        assertThat(validate(classes, claim.replace("Doctor already has an appointment at ", "Invented prefix: "), null)).isPresent();
        var plainFormatError = sources("IllegalArgumentException", "", "", "throw new IllegalArgumentException(\"Invalid email\");");
        assertThat(validate(plainFormatError, "Ném IllegalArgumentException với thông điệp lỗi định dạng \"Invalid email\".", null)).isEmpty();
        var connectionError = sources("IllegalStateException", "", "", "throw new IllegalStateException(\"Connection refused\");");
        assertThat(validate(connectionError, "Ném IllegalStateException với thông điệp lỗi kết nối \"Connection\".", null)).isPresent();
        var getterFormat = sources("ResourceNotFoundException", "AppointmentRequestDto dto",
                "static class AppointmentRequestDto { Long getId() { return 1L; } } ",
                "throw new ResourceNotFoundException(String.format(\"%s not found with %s: '%s'\", \"Appointment\", \"id\", dto.getId()));");
        assertThat(validate(getterFormat, "Ném ResourceNotFoundException với thông điệp từ mẫu \"%s not found with %s: '%s'\" với các tham số \"Appointment\", \"id\", dto.getId().", null)).isEmpty();
        assertThat(validate(getterFormat, "ResourceNotFoundException message equals String.format(\"%s not found with %s: '%s'\", \"Appointment\", \"id\", dto.getId())", null)).isEmpty();
        assertThat(validate(getterFormat, "Ném ResourceNotFoundException với thông điệp từ mẫu \"%s not found with %s: '%s'\" với các tham số \"Appointment\", \"id\", dto.getOtherId().", null)).isPresent();
        assertThat(GeneratedFixtureContractValidator.requiresRealDependency("Dùng MedicalRecordMapper thật; không gọi save.", "MedicalRecordMapper", true)).isTrue();
        assertThat(GeneratedFixtureContractValidator.requiresRealDependency("Dùng MedicalRecordMapper thật, không dùng mock.", "MedicalRecordMapper", true)).isTrue();
        assertThat(GeneratedFixtureContractValidator.requiresRealDependency("Không dùng MedicalRecordMapper thật.", "MedicalRecordMapper", true)).isFalse();
        assertThat(GeneratedFixtureContractValidator.requiresRealDependency("Dùng MedicalRecordMapper thật hoặc mock.", "MedicalRecordMapper", true)).isFalse();
        var tpContext = new com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto(null, null, List.of(),
                List.of(new com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto(10L, 1L, "BR-001", "Rule", null, "AI_GENERATED", "APPROVED", false, "IF-1"),
                        new com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto(11L, 1L, "BR-002", "Rule 2", null, "AI_GENERATED", "APPROVED", false, "IF-2")), List.of());
        var missingAnchorInCovered = new com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto(List.of(
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto(1L, 10L, List.of(11L), "Plan", "Desc", "HAPPY_PATH")));
        assertThat(GenerationValidationGate.validateReferences(tpContext, missingAnchorInCovered)).isPresent();
        var duplicateInCovered = new com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto(List.of(
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto(1L, 10L, List.of(10L, 10L, 11L), "Plan", "Desc", "HAPPY_PATH")));
        assertThat(GenerationValidationGate.validateReferences(tpContext, duplicateInCovered)).isPresent();
    }

    @Test
    void rejectsIncompatibleBinaryComparisonTautologicalSameAndUnapprovedEnrichedNullAssertions() {
        var appointmentClass = new ClassContextDto(1L, "com.hospital.appointment.model", "Appointment",
                "com.hospital.appointment.model.Appointment", "ENTITY", "Appointment.java",
                "package com.hospital.appointment.model; public class Appointment { private Long id; private String notes; "
                        + "public Long getId() { return id; } public void setId(Long id) { this.id = id; } "
                        + "public String getNotes() { return notes; } public void setNotes(String notes) { this.notes = notes; } }",
                List.of(), List.of());
        var responseDtoClass = new ClassContextDto(2L, "com.hospital.appointment.dto", "AppointmentResponseDto",
                "com.hospital.appointment.dto.AppointmentResponseDto", "DTO", "AppointmentResponseDto.java",
                "package com.hospital.appointment.dto; public class AppointmentResponseDto { private Long id; private String notes; private String patientName; "
                        + "public Long getId() { return id; } public void setId(Long id) { this.id = id; } "
                        + "public String getNotes() { return notes; } public void setNotes(String notes) { this.notes = notes; } "
                        + "public String getPatientName() { return patientName; } public void setPatientName(String patientName) { this.patientName = patientName; } }",
                List.of(), List.of());
        String createMethodSource = "public AppointmentResponseDto createAppointment() { Appointment saved = new Appointment(); "
                + "return enrichAppointmentResponse(saved, \"An Nguyen\"); }";
        var createMethod = new MethodContextDto(10L, "com.hospital.appointment.service.AppointmentService", "createAppointment",
                "AppointmentResponseDto", List.of(), List.of(), "PUBLIC", createMethodSource, 1, 10, List.of(), List.of(), List.of());
        var serviceClass = new ClassContextDto(3L, "com.hospital.appointment.service", "AppointmentService",
                "com.hospital.appointment.service.AppointmentService", "SERVICE", "AppointmentService.java",
                "package com.hospital.appointment.service; import com.hospital.appointment.model.Appointment; import com.hospital.appointment.dto.AppointmentResponseDto; "
                        + "public class AppointmentService { " + createMethodSource
                        + " private AppointmentResponseDto enrichAppointmentResponse(Appointment a, String name) { "
                        + "AppointmentResponseDto response = new AppointmentResponseDto(); response.setPatientName(name); return response; } }",
                List.of(), List.of(createMethod));
        var tc = new com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto(
                3863L, 3029L, "TC-013", "BOUNDARY", "Default SCHEDULED status when request status is null.",
                "Clients return valid DTOs.", Map.of("input", Map.of("notes", "null")),
                "Saved with SCHEDULED status and returns id 303.", "HIGH", "BR-020", "APPROVED", false);
        var utContext = new com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto(
                null, null, List.of(appointmentClass, responseDtoClass, serviceClass), List.of(), List.of(), List.of(tc),
                List.of(), List.of(), List.of(), List.of(), List.of());
        String badTestSource = "package com.hospital.appointment.service;\n"
                + "import com.hospital.appointment.model.Appointment;\n"
                + "import com.hospital.appointment.dto.AppointmentResponseDto;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.assertNull;\n"
                + "import static org.junit.jupiter.api.Assertions.assertSame;\n"
                + "import static org.junit.jupiter.api.Assertions.assertTrue;\n"
                + "class AppointmentServiceTest {\n"
                + "    @Test void testCreate() {\n"
                + "        AppointmentService sut = new AppointmentService();\n"
                + "        Appointment saved = new Appointment();\n"
                + "        AppointmentResponseDto result = sut.createAppointment();\n"
                + "        assertTrue(saved == result.getId() || result.getId().equals(303L));\n"
                + "        assertSame(saved, saved);\n"
                + "        assertNull(result.getNotes());\n"
                + "        assertTrue(result.getPatientName() == null);\n"
                + "    }\n"
                + "}\n";
        var parsed = new com.github.javaparser.JavaParser().parse(badTestSource).getResult().orElseThrow();
        var apiProblems = GeneratedTestApiValidator.validate(utContext, parsed, 3863L);
        assertThat(apiProblems).anyMatch(p -> p.contains("Incompatible type comparison 'saved == result.getId()'"));
        assertThat(apiProblems).anyMatch(p -> p.contains("Tautological identity assertion 'assertSame(saved, saved)'"));
        var repoProblems = GeneratedRepositoryFixtureValidator.validate(utContext, createMethod, 3863L, parsed);
        assertThat(repoProblems).anyMatch(p -> p.contains("Unapproved null assertion on 'result.getPatientName()'"));
        assertThat(repoProblems).noneMatch(p -> p.contains("result.getNotes()"));
    }

    @Test
    void rejectsModifiedOrIncompleteConstructorFormatArguments() {
        String prefix = "ResourceNotFoundException message from template \"%s not found with %s: '%s'\" using \"Appointment\", \"id\", ";
        for (String suffix : List.of("id + 1", "id.longValue()", "id, otherId", "otherId"))
            assertThat(validate(resourceSources(), prefix + suffix, null)).as(suffix).isPresent();
    }

    private List<ClassContextDto> resourceSources() {
        return sourceClasses("void run(Long id, Long otherId) { throw new ResourceNotFoundException(\"Appointment\", \"id\", id); }",
                "static class ResourceNotFoundException extends RuntimeException { "
                + "ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) { "
                + "super(String.format(\"%s not found with %s: '%s'\", resourceName, fieldName, fieldValue)); } }");
    }

    @Test
    void reportsAllBusinessRuleMessageProblemsInOneCorrection() {
        var context = new com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto(
                null, null, resourceSources(), List.of(), List.of(), List.of(), List.of());
        var response = new com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto(List.of(
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto(1L,
                        "ResourceNotFoundException message \"Wrong first\"", "VALIDATION"),
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto(1L,
                        "ResourceNotFoundException message \"Wrong second\"", "VALIDATION")));
        assertThat(UpstreamSourceEvidenceValidator.businessRules(context, response)).hasValueSatisfying(
                problem -> assertThat(problem).contains("Wrong first", "Wrong second"));
    }

    @Test
    void reportsAllTestCaseMessageProblemsInOneCorrection() {
        var rule = new com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto(
                1L, 1L, "BR-001", "Missing resource throws ResourceNotFoundException", null, "AI_GENERATED", "APPROVED", false, null);
        var plan = new com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto(
                7L, 1L, List.of(1L), "TP-001", "Missing resource", "Check exception", "EXCEPTION", "APPROVED", false);
        var context = new com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto(
                null, null, resourceSources(), List.of(rule), List.of(plan), List.of());
        var response = new com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto(List.of(
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto(7L, "EXCEPTION", "First", "", Map.of(),
                        "ResourceNotFoundException message \"Wrong first\"", "HIGH", ""),
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto(7L, "EXCEPTION", "Second", "", Map.of(),
                        "ResourceNotFoundException message \"Wrong second\"", "HIGH", "")));
        assertThat(UpstreamSourceEvidenceValidator.cases(context, response)).hasValueSatisfying(
                problem -> assertThat(problem).contains("Wrong first", "Wrong second", "plan_id=7"));
    }

    private java.util.Optional<String> validate(List<ClassContextDto> classes, String claim, Map<String, Object> input) {
        return new SourceExceptionMessageEvidence(classes).validate(classes, 1L, claim, input, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\n", "A)", "a, and b"})
    void preservesEscapedAndPunctuatedStringArguments(String value) {
        String literal = new com.github.javaparser.ast.expr.StringLiteralExpr().setString(value).toString();
        var classes = sources("DepartmentNotFoundException", "", "",
                "throw new DepartmentNotFoundException(String.format(\"Missing %s\", " + literal + "));");
        assertThat(validate(classes, "DepartmentNotFoundException message equals String.format(\"Missing %s\", " + literal + ")", null)).isEmpty();
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %s\" using " + literal, null)).isEmpty();
        String wrong = new com.github.javaparser.ast.expr.StringLiteralExpr().setString("\n").toString();
        assertThat(validate(classes, "DepartmentNotFoundException message equals String.format(\"Missing %s\", " + wrong + ")", null)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Runnable unused = new Runnable() { public void run() { throw new DepartmentNotFoundException(String.format(\"Missing %d\", id)); } };",
            "class Local { void inner(Long id) { throw new DepartmentNotFoundException(String.format(\"Missing %d\", id)); } }"})
    void doesNotBorrowMessageEvidenceFromUncalledNestedCode(String body) {
        var classes = sources("DepartmentNotFoundException", "Long id", "", body);
        assertThat(validate(classes, "DepartmentNotFoundException message from template \"Missing %d\" using id", null)).isPresent();
    }

    private List<ClassContextDto> departmentSources(String prefix) {
        return sources("DepartmentNotFoundException", "Long departmentId, Long otherId", "",
                prefix + "throw new DepartmentNotFoundException(String.format(\"Failed to find the department with id %d\", departmentId));");
    }

    private List<ClassContextDto> sources(String exception, String parameters, String supporting, String body) {
        String method = "void run(" + parameters + ") { " + body + " }";
        String exceptionSource = "static class " + exception + " extends RuntimeException { " + exception
                + "(String message) { super(message); } }";
        return sourceClasses(method, supporting + exceptionSource);
    }

    private List<ClassContextDto> sourceClasses(String method, String declarations) {
        var dto = new MethodContextDto(1L, "sample.Service", "run", "void", List.of(), List.of(), "PUBLIC", method,
                1, 1, List.of(), List.of(), List.of());
        return List.of(new ClassContextDto(1L, "sample", "Service", "sample.Service", "SERVICE", "Service.java",
                "package sample; class Service { " + declarations + method + " }", List.of(), List.of(dto)));
    }
}
