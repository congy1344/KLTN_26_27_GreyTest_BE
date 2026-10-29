package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

/** Lỗi BR/TC thật phải bị chặn tại bước sinh, trước khi trở thành oracle của UT. */
class UpstreamSourceEvidenceRegressionTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Mapper thực hiện ánh xạ DTO sang entity theo mock setup.", "MedicalRecordMapper thực hiện ánh xạ DTO sang entity theo mock setup."})
    void performingMappingDoesNotRequireARealMapperImplementation(String preconditions) throws Exception {
        var f = fixture("hms-required-real-mapper-role");
        ((ObjectNode) f.path("context").path("approvedTestCases").get(0)).put("preconditions", preconditions);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved fixture contract");
    }

    @Test void aQuotedExceptionMessageDoesNotDeclareACollaboratorBan() throws Exception {
        var f = fixture("hms-null-excluding-never");
        ((ObjectNode) f.path("context").path("approvedTestCases").get(0)).put("expectedResult", "Ném ngoại lệ với thông điệp \"Do not call appointmentMapper.toDto.\"");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Null-inclusive verification");
    }

    @Test void rejectsNullExcludingTypedMatchersForAnApprovedMethodWideBan() throws Exception {
        var f = fixture("hms-null-excluding-never");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Null-inclusive verification", "save", "toDto"));
    }

    @Test void acceptsNullInclusiveAnyForTheApprovedMethodWideBan() throws Exception {
        var f = fixture("hms-null-excluding-never");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("org.mockito.ArgumentMatchers.any(Appointment.class)", "org.mockito.ArgumentMatchers.<Appointment>any()"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void aSingleSourceMapperCannotBeMockedWhenTheApprovedMapperRoleMustBeReal() throws Exception {
        var f = fixture("hms-required-real-mapper-role");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved fixture contract", "MedicalRecordMapper", "real"));
    }

    @Test void aDeclaredClassCanHoldAStaticEnumFixtureConstant() throws Exception {
        var f = fixture("hms-enum-fixture");
        var holder = json.createObjectNode().put("id", 999998).put("packageName", "com.example.HMS.service")
                .put("className", "EventConstants").put("qualifiedName", "com.example.HMS.service.EventConstants").put("classType", "CLASS")
                .put("filePath", "Hospital-Management-System/HMS/src/main/java/com/example/HMS/service/EventConstants.java")
                .put("sourceCode", "package com.example.HMS.service; public final class EventConstants { public static final com.example.HMS.enums.AppointmentStatus COMPLETED = com.example.HMS.enums.AppointmentStatus.APPOINTMENT_COMPLETED; }");
        holder.putArray("annotations"); holder.putArray("methods");
        ((ArrayNode) f.path("context").path("classes")).add(holder);
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("Thread.State.valueOf(\"APPOINTMENT_COMPLETED\")", "com.example.HMS.service.EventConstants.COMPLETED"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void rejectsAnInventedProductionEnumTypeForARawEnumInput() throws Exception {
        var f = fixture("hms-enum-fixture");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("Thread.State.valueOf(\"APPOINTMENT_COMPLETED\")", "com.example.HMS.service.WebhookEventType.APPOINTMENT_COMPLETED"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Enum fixture type", "not declared", "WebhookEventType"));
    }

    @Test void aLocallyDeclaredEnumPreservesTheRawEnumNameWithoutInventingProductionTypes() throws Exception {
        var f = fixture("hms-enum-fixture");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("Thread.State.valueOf(\"APPOINTMENT_COMPLETED\")", "EventFixture.APPOINTMENT_COMPLETED")
                .replace("class webhookServiceTest {", "class webhookServiceTest { private enum EventFixture { APPOINTMENT_COMPLETED }"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"constructor", "initializer", "explicitImport"})
    void spyConstructionIsNotInferredWhenBindingOrPreExtensionInitializationIsUnknown(String kind) throws Exception {
        var f = fixture("hms-uninitialized-spy");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = u.path("source_code").asText();
        if (kind.equals("explicitImport")) code = code.replace("import org.mockito.Spy;", "import org.mockito.*;\nimport custom.Spy;");
        else code = code.replace("class MedicalRecordServiceTest {", "class MedicalRecordServiceTest {\n"
                + (kind.equals("constructor") ? "MedicalRecordServiceTest()" : "")
                + " { medicalRecordMapper = new MedicalRecordMapper(appointmentRepository); }");
        u.put("source_code", code);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Uninitialized @Spy");
    }

    @Test void arraySpyTypeDoesNotCrashSourceValidation() throws Exception {
        var f = fixture("hms-uninitialized-spy");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("private MedicalRecordMapper medicalRecordMapper;", "private MedicalRecordMapper[] medicalRecordMapper;"));
        assertThatCode(() -> GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).doesNotThrowAnyException();
    }

    @Test void anEnumFixtureMustNotInventABusinessValueUsingAnUnrelatedEnumFactory() throws Exception {
        var f = fixture("hms-enum-fixture");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Enum fixtures", "declared"));
    }

    @Test void acceptsTheApprovedEnumNameAsAnActualDeclaredProjectConstant() throws Exception {
        var f = fixture("hms-enum-fixture");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("Thread.State.valueOf(\"APPOINTMENT_COMPLETED\")", "com.example.HMS.enums.AppointmentStatus.APPOINTMENT_COMPLETED"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void rejectsReferenceIdentityBetweenAnEntityAndItsUnrelatedDto() throws Exception {
        var f = fixture("hms-incompatible-identity");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Impossible reference identity", "Bill", "BillDto"));
    }

    @Test void acceptsMappedDtoFieldAssertionsWithoutInventingEntityIdentity() throws Exception {
        var f = fixture("hms-incompatible-identity");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("assertSame(savedBill, result);", "org.junit.jupiter.api.Assertions.assertNotNull(result);"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void rejectsAnUninitializedSpyWithoutASourceSupportedNoArgConstructor() throws Exception {
        var f = fixture("hms-uninitialized-spy");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Uninitialized @Spy", "MedicalRecordMapper", "0 arguments"));
    }

    @Test void anExplicitRealMapperConstructorAvoidsImplicitSpyConstruction() throws Exception {
        var f = fixture("hms-uninitialized-spy");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("@Spy\n    private MedicalRecordMapper medicalRecordMapper;", "")
                .replace("@InjectMocks", "")
                .replace("// Arrange", "// Arrange\nmedicalRecordService = new MedicalRecordService(patientRepository, doctorRepository, medicalRepository, prescriptionRepository, appointmentRepository, new MedicalRecordMapper(appointmentRepository));"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void aProjectAnnotationNamedSpyDoesNotTriggerMockitoConstructionRules() throws Exception {
        var f = fixture("hms-uninitialized-spy");
        var u = (ObjectNode) f.path("response").path("unit_tests").get(0);
        u.put("source_code", u.path("source_code").asText().replace("import org.mockito.Spy;", "")
                .replace("class MedicalRecordServiceTest {", "class MedicalRecordServiceTest { @interface Spy {}"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Uninitialized @Spy");
    }

    @Test void acceptsExplicitConstructionBeforeActEvenWithAnUnusedInjectMocksAnnotation() throws Exception {
        var f = fixture("hms-explicit-constructor");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void constructionAfterActDoesNotHideMissingInjectedDependencies() throws Exception {
        var f = fixture("hms-explicit-constructor");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = unit.path("source_code").asText();
        int start = code.indexOf("service = new MedicalRecordService(");
        int end = code.indexOf(";", start) + 1;
        String construction = code.substring(start, end);
        unit.put("source_code", code.substring(0, start) + code.substring(end).replace(
                "MedicalRecordDto result = service.createMedicalRecord(request);", "MedicalRecordDto result = service.createMedicalRecord(request);\n" + construction));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("require @Mock or @Spy", "MedicalRecordMapper"));
    }

    @Test void explicitConstructorDoesNotInitializeAnAdditionalAutowiredField() throws Exception {
        var f = fixture("hms-explicit-constructor");
        for (var clazz : f.path("context").path("classes")) {
            if (!clazz.path("qualifiedName").asText().equals("com.example.HMS.service.MedicalRecordService")) continue;
            var object = (ObjectNode) clazz;
            object.put("sourceCode", object.path("sourceCode").asText()
                    .replace("import lombok.RequiredArgsConstructor;", "import lombok.RequiredArgsConstructor;\nimport org.springframework.beans.factory.annotation.Autowired;")
                    .replace("private final MedicalRecordMapper medicalRecordMapper;", "@Autowired private MedicalRecordMapper medicalRecordMapper;"));
        }
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("appointmentRepository,\n                realMapper);", "appointmentRepository);"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("require @Mock or @Spy", "MedicalRecordMapper"));
    }

    @Test void aRealLocalMapperPassedToTheConstructorIsNotTheShadowedMockField() throws Exception {
        var f = fixture("hms-real-mapper-implementation");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("@InjectMocks private BillService billService;", "private BillService billService;")
                .replace("// Arrange", "// Arrange\nBillMapper billMapper = new BillMapper();\nbillService = new BillService(billRepository, patientRepository, appointmentRepository, billMapper);"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved fixture contract");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"local", "initialized"})
    void aProductionRealMapperIsNotAnInjectedMock(String kind) throws Exception {
        var f = fixture("hms-unstubbed-mapping");
        for (var clazz : f.path("context").path("classes")) {
            if (!clazz.path("qualifiedName").asText().equals("com.example.HMS.service.AppointmentService")) continue;
            var object = (ObjectNode) clazz;
            if (kind.equals("initialized")) object.put("sourceCode", object.path("sourceCode").asText().replace(
                    "private final AppointmentMapper appointmentMapper;", "private final AppointmentMapper appointmentMapper = new AppointmentMapper();"));
            else {
                String original = "List<Appointment> appointment = appointmentRepository.findByDate(date);";
                String replacement = "AppointmentMapper appointmentMapper = new AppointmentMapper(); " + original;
                object.put("sourceCode", object.path("sourceCode").asText().replace(original, replacement));
                for (var method : object.path("methods")) if (method.path("methodName").asText().equals("getAppointmentsByDate"))
                    ((ObjectNode) method).put("sourceCode", method.path("sourceCode").asText().replace(original, replacement));
            }
        }
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Unstubbed mock projection");
    }

    @Test void aShortCircuitNullCheckDoesNotDereferenceANullDto() throws Exception {
        var f = fixture("hms-unstubbed-mapping");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = unit.path("source_code").asText().lines().filter(line -> !line.contains("firstDto.") && !line.contains("secondDto."))
                .collect(java.util.stream.Collectors.joining("\n"));
        code = code.replace("verify(appointmentRepository).findByDate(eq(date));",
                "org.junit.jupiter.api.Assertions.assertTrue(firstDto == null || firstDto.getAppointmentId() != null); verify(appointmentRepository).findByDate(eq(date));");
        unit.put("source_code", code);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Unstubbed mock projection");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Do not save appointment, except when validation succeeds.", "Không lưu appointment, trừ khi validation thành công."})
    void commaQualifiedNoSaveDoesNotBecomeAnUnconditionalBan(String expected) throws Exception {
        var f = fixture("hms-forbidden-save");
        ((ObjectNode) f.path("context").path("approvedTestCases").get(0)).put("expectedResult", expected);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved negative interaction");
    }

    @Test void rejectsPositiveRepositorySaveAgainstAnExplicitEntityNoSaveRequirement() throws Exception {
        var f = fixture("hms-forbidden-save");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved negative interaction", "appointmentRepository.save"));
    }

    @Test void rejectsPopulatingExplicitlyNullPatchConstructorArguments() throws Exception {
        var f = fixture("hms-null-patch-input");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved input", "constructor", "null"));
    }

    @Test void acceptsTheSamePatchScenariosWithTheirApprovedNullInputs() throws Exception {
        var f = fixture("hms-null-patch-input");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace(
                "AppointmentDto unchangedDoctorDto = new AppointmentDto(501L, 10L, 20L, null, null, null, null);",
                "AppointmentDto unchangedDoctorDto = new AppointmentDto(null, null, null, null, null, null, null);"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void rejectsMockingAnApprovedRealImplementation() throws Exception {
        var f = fixture("hms-real-mapper-implementation");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved fixture contract", "BillMapper", "real"));
    }

    @Test void rejectsDereferencingAnUnstubbedMockProjection() throws Exception {
        var f = fixture("hms-unstubbed-mapping");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Unstubbed mock projection", "appointmentMapper", "toDto"));
    }

    @Test void acceptsARealSpyForTheMappedDtoProjection() throws Exception {
        var f = fixture("hms-unstubbed-mapping");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("@Mock private AppointmentMapper appointmentMapper;",
                "@org.mockito.Spy private AppointmentMapper appointmentMapper = new AppointmentMapper();"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void acceptsApprovedNullScenariosWithoutImposingAnArrayOrder() throws Exception {
        var f = fixture("hms-null-patch-input");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace(
                "AppointmentDto unchangedDoctorDto = new AppointmentDto(501L, 10L, 20L, null, null, null, null);",
                "AppointmentDto unchangedDoctorDto = new AppointmentDto(null, null, null, null, null, null, null);"));
        var scenarios = (ArrayNode) f.path("context").path("approvedTestCases").get(0).path("testData").path("input").path("cases");
        var first = scenarios.get(0); scenarios.set(0, scenarios.get(1)); scenarios.set(1, first);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Không lưu appointment quá một lần.", "Không lưu appointment với patientId 20."})
    void doesNotWidenAnArgumentOrCountQualifiedNoSaveRequirement(String expected) throws Exception {
        var f = fixture("hms-forbidden-save");
        ((ObjectNode) f.path("context").path("approvedTestCases").get(0)).put("expectedResult", expected);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved negative interaction");
    }

    @Test void acceptsTheActualEcommerceMessageContainsUsernameClaim() throws Exception {
        var f = fixture("ecommerce-message-substring");
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isEmpty();
    }

    @Test void verifiesTheActualHmsOptionalMedicalRecordException() throws Exception {
        var f = fixture("hms-optional-medical-exception");
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isEmpty();
    }

    @Test void rejectsReadingAnUncapturedLocalCaptorFromTheLiveResponse() throws Exception {
        var f = fixture("realworld-uncaptured-captor");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("ArgumentCaptor", "savedArticleCaptor", "capture()"));
    }

    @Test void acceptsTheCaptorWhenTheVerifiedArgumentActuallyCaptures() throws Exception {
        var f = fixture("realworld-uncaptured-captor");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("save(any(Article.class))", "save(savedArticleCaptor.capture())"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void aFieldCaptorDoesNotBorrowAnOutOfScopeLocalsUncapturedState() throws Exception {
        var f = fixture("realworld-uncaptured-captor");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = unit.path("source_code").asText()
                .replace("class ArticleCommandServiceTest {", "class ArticleCommandServiceTest { @org.mockito.Captor ArgumentCaptor<Article> savedArticleCaptor; "
                        + "@org.junit.jupiter.api.BeforeEach void captureEarlierSave() { articleRepository.save(new Article()); verify(articleRepository).save(savedArticleCaptor.capture()); }")
                .replace("ArgumentCaptor<Article> savedArticleCaptor = ArgumentCaptor.forClass(Article.class);",
                        "{ ArgumentCaptor<Article> savedArticleCaptor = ArgumentCaptor.forClass(Article.class); }");
        unit.put("source_code", code);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("is read by getValue() but never captures");
    }

    @Test void anUninvokedSupplierIsNotProofThatCaptorGetValueExecutes() throws Exception {
        var f = fixture("realworld-uncaptured-captor");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("Article savedArticle = savedArticleCaptor.getValue();",
                "java.util.function.Supplier<Article> unusedFallback = () -> savedArticleCaptor.getValue(); Article savedArticle = result;"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("is read by getValue() but never captures");
    }

    @Test void rejectsStringBasedDecimalScaleAssertionsEvenWhenAnUnrelatedInvoiceNumberHasAFormat() throws Exception {
        var f = fixture("billing-formatted-number-decimal");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("BigDecimal numeric assertion contract", "toPlainString", "compareTo"));
    }

    @Test void jsonMappingCorrectionIncludesTheActualRejectedArrayAndDtoPath(@TempDir java.nio.file.Path root) throws Exception {
        var f = fixture("billing-upstream-message");
        String invalid = "{\"rules\":[\"placeholder-rule\"]}";
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildBusinessRuleGenerationContext(1L)).thenReturn(brContext(f));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(invalid);
        assertThatThrownBy(() -> agent(contexts, client, root).generateBusinessRules(1L)).isInstanceOf(LlmResponseException.class);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("# JSON correction", invalid, "BusinessRuleResponseDto", "[0]");
    }

    @Test void acceptsTheLiveCredentialExceptionMessageWithItsActualConstructor() throws Exception {
        var f = fixture("ecommerce-exception-constructor");
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isEmpty();
    }

    @Test void rejectsTheLiveMockitoToStringVerification() throws Exception {
        var f = fixture("realworld-verify-to-string");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Mockito cannot verify toString()"));
    }

    @Test void allowsStubbingToStringWithoutVerifyingIt() throws Exception {
        var f = fixture("realworld-verify-to-string");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("verify(userRelationshipQueryService).toString();",
                "when(userRelationshipQueryService.toString()).thenReturn(\"relationship service\");"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Mockito cannot verify toString()");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"local", "type", "variable"})
    void doesNotMistakeACustomVerifyHelperForMockito(String kind) throws Exception {
        var f = fixture("realworld-verify-to-string");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = unit.path("source_code").asText();
        if (!kind.equals("local")) code = code.replace("verify(userRelationshipQueryService).toString()", "Mockito.verify(userRelationshipQueryService).toString()");
        String helper = kind.equals("local") ? "<T> T verify(T value) { return value; }"
                : kind.equals("type") ? "static class Mockito { static <T> T verify(T value) { return value; } }"
                : "FakeVerifier Mockito = new FakeVerifier(); static class FakeVerifier { <T> T verify(T value) { return value; } }";
        unit.put("source_code", code.substring(0, code.lastIndexOf('}')) + helper + "}");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Mockito cannot verify toString()");
    }

    @Test void planCorrectionIncludesTheRejectedResponseAndAuthoritativeRuleMapping(@TempDir java.nio.file.Path root) throws Exception {
        var f = fixture("hms-wrong-plan-references");
        var context = json.treeToValue(f.get("context"), TestPlanContextDto.class);
        String invalid = json.writeValueAsString(f.get("response"));
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestPlanContext(1L)).thenReturn(context);
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(invalid);
        assertThatThrownBy(() -> agent(contexts, client, root).generateTestPlan(1L))
                .isInstanceOf(LlmResponseException.class);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("333=[173, 174, 175, 176]", invalid,
                "Previous invalid response (untrusted repair data)");
    }

    @Test void acceptsAnExplicitJavaMessageConcatenationFromTheLiveHospitalResponse() throws Exception {
        var f = fixture("hospital-java-concat-message");
        assertThat(GenerationValidationGate.validateReferences(brContext(f), json.treeToValue(f.get("response"), BusinessRuleResponseDto.class))).isEmpty();
    }

    @Test void aMissingSourceDecisionHasCorrectionDetailsAndIsRetryable(@TempDir java.nio.file.Path root) throws Exception {
        var f = fixture("billing-missing-source-decisions");
        var failure = catchThrowable(() -> agent(mock(GenerationContextBuilder.class), mock(LlmClient.class), root)
                .validateSnapshotOutput(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE, 3L, brContext(f),
                        json.treeToValue(f.get("response"), BusinessRuleResponseDto.class), null));
        assertThat(failure).isInstanceOfSatisfying(LlmResponseException.class, error -> {
            assertThat(error.isRetryable()).isTrue();
            assertThat(error.getMessage()).contains("Generation validation gate failed:", "136", "TERNARY-1");
        });
    }

    @Test void duplicateSourceDecisionsAreSentToCorrectionWithTheirMethodId(@TempDir java.nio.file.Path root) throws Exception {
        var f = fixture("billing-missing-source-decisions");
        var rules = (ArrayNode) f.path("response").path("rules");
        var ternary = ((ObjectNode) rules.get(0)).deepCopy();
        ternary.put("branch_id", "TERNARY-1").put("description", "Khi ghi chú cũ khác null, thêm ký tự xuống dòng trước ghi chú mới; nếu null dùng chuỗi rỗng.");
        rules.add(ternary); rules.add(rules.get(0).deepCopy());
        var failure = catchThrowable(() -> agent(mock(GenerationContextBuilder.class), mock(LlmClient.class), root)
                .validateSnapshotOutput(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE, 3L, brContext(f),
                        json.treeToValue(f.get("response"), BusinessRuleResponseDto.class), null));
        assertThat(failure).isInstanceOfSatisfying(LlmResponseException.class, error -> {
            assertThat(error.isRetryable()).isTrue();
            assertThat(error.getMessage()).contains("Generation validation gate failed:", "Duplicate", "136", "IF-1");
        });
    }

    @Test void rejectsTheLiveUnitTestThatReversesAnApprovedNoCallRequirement() throws Exception {
        var f = fixture("library-negative-interaction");
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved negative interaction", "bookMapper.toView", "10"));
    }

    @Test void acceptsNeverVerificationWithoutChangingTheLibraryOracle() throws Exception {
        var f = fixture("library-negative-interaction");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("verify(bookMapper).toView", "verify(bookMapper, org.mockito.Mockito.never()).toView"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class))).isEmpty();
    }

    @Test void rejectsOnlyVerificationForAnApprovedNoCallRequirement() throws Exception {
        var f = fixture("library-negative-interaction");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("verify(bookMapper).toView", "verify(bookMapper, org.mockito.Mockito.only()).toView"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved negative interaction"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"bookMapper.toView must not be called.", "Never call bookMapper.toView."})
    void recognizesAnExplicitEnglishNoCallRequirement(String expected) throws Exception {
        var f = fixture("library-negative-interaction");
        ((ObjectNode) f.path("context").path("approvedTestCases").get(0)).put("expectedResult", expected);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("Approved negative interaction"));
    }

    @Test void doesNotApplyOneCasesNoCallRequirementToAnotherTestMethod() throws Exception {
        var f = fixture("library-negative-interaction");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = unit.path("source_code").asText().replace("verify(bookMapper).toView", "verify(bookMapper, org.mockito.Mockito.never()).toView");
        code = code.substring(0, code.lastIndexOf('}')) + "@Test void unrelatedCase() { verify(bookMapper).toView(org.mockito.ArgumentMatchers.any()); } }";
        unit.put("source_code", code);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved negative interaction");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "bookMapper.toView(bookA) must not be called.", "Never call bookMapper.toView(bookA).",
            "bookMapper.toView must not be called more than once.", "Never call bookMapper.toView twice.",
            "bookMapper.toView không được gọi quá một lần.", "Never call bookMapper.toViewExtra."})
    void doesNotWidenQualifiedOrDifferentMethodRequirements(String expected) throws Exception {
        var f = fixture("library-negative-interaction");
        ((ObjectNode) f.path("context").path("approvedTestCases").get(0)).put("expectedResult", expected);
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        unit.put("source_code", unit.path("source_code").asText().replace("verify(bookMapper).toView(org.mockito.ArgumentMatchers.any());",
                "pl.com.coders.libria1.domain.Book bookB = new pl.com.coders.libria1.domain.Book(); verify(bookMapper).toView(bookB);"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved negative interaction");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"local", "custom", "short-type", "short-variable"})
    void customOnlyHelpersReturningNeverAreNotPositiveVerification(String kind) throws Exception {
        var f = fixture("library-negative-interaction");
        var unit = (ObjectNode) f.path("response").path("unit_tests").get(0);
        String code = unit.path("source_code").asText();
        String mode = kind.equals("local") ? "only()" : kind.startsWith("short-") ? "Mockito.only()" : "CustomModes.only()";
        code = code.replace("verify(bookMapper).toView", "verify(bookMapper, " + mode + ").toView");
        String helper = kind.equals("local")
                ? "private org.mockito.verification.VerificationMode only() { return org.mockito.Mockito.never(); }"
                : kind.equals("short-type") ? "static class Mockito { static org.mockito.verification.VerificationMode only() { return org.mockito.Mockito.never(); } }"
                : kind.equals("short-variable") ? "static class FakeModes { org.mockito.verification.VerificationMode only() { return org.mockito.Mockito.never(); } }"
                : "static class CustomModes { static org.mockito.verification.VerificationMode only() { return org.mockito.Mockito.never(); } }";
        if (kind.equals("short-variable")) code = code.replace("verify(bookMapper, Mockito.only())", "FakeModes Mockito = new FakeModes(); verify(bookMapper, Mockito.only())");
        if (kind.equals("local")) code = code.replace("import static org.mockito.Mockito.verify;",
                "import static org.mockito.Mockito.verify;\nimport static org.mockito.Mockito.only;");
        code = code.substring(0, code.lastIndexOf('}')) + helper + "}";
        unit.put("source_code", code);
        assertThat(GeneratedUnitTestSemanticValidator.validate(unitContext(f), json.treeToValue(f.get("response"), UnitTestResponseDto.class)).orElse(""))
                .doesNotContain("Approved negative interaction");
    }

    @Test void acceptsCapturedLibraryOptionalVariableAndQuotedMessageFragment() throws Exception {
        var f = fixture("library-local-optional-message");
        assertThat(GenerationValidationGate.validateReferences(brContext(f), json.treeToValue(f.get("response"), BusinessRuleResponseDto.class))).isEmpty();
    }

    @Test void acceptsCapturedHospitalLiteralAndDynamicMessageComposition() throws Exception {
        var f = fixture("hospital-dynamic-exception-message");
        assertThat(GenerationValidationGate.validateReferences(brContext(f), json.treeToValue(f.get("response"), BusinessRuleResponseDto.class))).isEmpty();
    }

    @Test void stillRejectsAnInventedLiteralFragmentInTheLibraryRule() throws Exception {
        var f = fixture("library-local-optional-message");
        var first = (ObjectNode) f.path("response").path("rules").get(0);
        first.put("description", first.path("description").asText().replace("Book not exist with id : ", "Invented prefix: "));
        assertThat(GenerationValidationGate.validateReferences(brContext(f), json.treeToValue(f.get("response"), BusinessRuleResponseDto.class))).isPresent();
    }

    @Test void rejectsTheInventedPeriodInTheCapturedBusinessRule() throws Exception {
        var f = fixture("billing-upstream-message");
        assertThat(GenerationValidationGate.validateReferences(brContext(f), json.treeToValue(f.get("response"), BusinessRuleResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("18671", "message", "ResourceNotFoundException"));
    }

    @Test void acceptsExactMessageWithSentencePunctuationOutsideTheQuotes() throws Exception {
        var f = fixture("billing-upstream-message");
        var rule = (ObjectNode) f.path("response").path("rules").get(0);
        rule.put("description", rule.path("description").asText().replace("'<appointmentId>'.", "'<appointmentId>'") + ".");
        assertThat(GenerationValidationGate.validateReferences(brContext(f), json.treeToValue(f.get("response"), BusinessRuleResponseDto.class))).isEmpty();
    }

    @Test void rejectsBothCapturedCollectionMockArgumentOrdersBeforeSavingCases() throws Exception {
        var f = fixture("realworld-upstream-collection");
        var context = tcContext(f);
        var response = json.treeToValue(f.get("response"), TestCaseResponseDto.class);
        var before = json.valueToTree(response).deepCopy();
        for (var item : response.cases()) {
            assertThat(GenerationValidationGate.validateTestCases(context, new TestCaseResponseDto(List.of(item))))
                    .hasValueSatisfying(p -> assertThat(p).contains("2443", "source", "article-2", "article-1"));
        }
        JsonNode after = json.valueToTree(response);
        assertThat(after).isEqualTo(before);
    }

    @Test void acceptsSourceDerivedFixtureArgumentsWithoutAddingABusinessInteractionRequirement() throws Exception {
        var f = fixture("realworld-upstream-collection");
        correctArguments(f, true);
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isEmpty();
    }

    @Test void stillRejectsUnsupportedTemporalClaimsAfterMockArgumentsWereFixed() throws Exception {
        var f = fixture("realworld-upstream-collection");
        correctArguments(f, false);
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class)))
                .hasValueSatisfying(p -> assertThat(p).contains("source", "before", "mutation"));
    }

    @Test void preservesAnExplicitUserBusinessRequirementEvenWhenSourceDisagrees() throws Exception {
        var f = fixture("realworld-upstream-collection");
        for (var rule : f.path("context").path("approvedBusinessRules"))
            ((ObjectNode) rule).put("source", "USER_ADDED").put("isModified", true);
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isEmpty();
    }

    @Test void doesNotGuessABoxedRemoveOverload() throws Exception {
        var f = fixture("realworld-upstream-collection");
        for (var source : f.path("context").path("classes")) {
            ((ObjectNode) source).put("sourceCode", source.path("sourceCode").asText().replace("articles.remove(page.getLimit())", "articles.remove(Integer.valueOf(page.getLimit()))"));
            for (var method : source.path("methods")) ((ObjectNode) method).put("sourceCode", method.path("sourceCode").asText()
                    .replace("articles.remove(page.getLimit())", "articles.remove(Integer.valueOf(page.getLimit()))"));
        }
        var cases = json.treeToValue(f.get("response"), TestCaseResponseDto.class).cases();
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), new TestCaseResponseDto(List.of(cases.get(1))))).isEmpty();
    }

    @Test void stopsInvalidSavedBusinessRulesBeforeAnyTestCaseProviderCall(@TempDir java.nio.file.Path directory) throws Exception {
        var f = fixture("billing-upstream-message");
        var br = brContext(f);
        var bad = json.treeToValue(f.get("response"), BusinessRuleResponseDto.class).rules().get(0);
        var rule = new BusinessRuleContextDto(4974L, bad.methodId(), "BR-028", bad.description(), null, "AI_GENERATED", "APPROVED", false, bad.branchId());
        var plan = new TestPlanContextItemDto(2598L, 4974L, List.of(4974L), "TP-019", "Missing invoice", "Check missing invoice", "EXCEPTION", "APPROVED", false);
        var context = new TestCaseContextDto(br.project(), br.analysis(), br.classes(), List.of(rule), List.of(plan), List.of());
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(1L, Set.of(2598L))).thenReturn(context);
        var client = mock(LlmClient.class);
        var agent = new AIAgentService(contexts, new PromptManager(json), client,
                new GenerationResponseParser(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator()),
                new AiContextLogService(json, directory.toString(), false, false));
        assertThatThrownBy(() -> agent.validateTestCasePrerequisites(1L, Set.of(2598L))).isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("4974").hasMessageContaining("review");
        assertThatThrownBy(() -> agent.generateTestCases(1L, Set.of(2598L))).isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("4974");
        verifyNoInteractions(client);
    }

    @Test void preservesAnApprovedCaseExplicitlyMarkedAsSourceMismatch() throws Exception {
        var f = fixture("realworld-upstream-collection");
        var context = tcContext(f);
        var generated = json.treeToValue(f.get("response"), TestCaseResponseDto.class).cases().get(0);
        var saved = new TestCaseContextItemDto(3124L, generated.planId(), "TC-001", generated.testType(), generated.description(),
                generated.preconditions(), generated.testData(), generated.expectedResult(), generated.priority(),
                generated.traceSource() + " SOURCE_MISMATCH", "APPROVED", false);
        var unit = new UnitTestContextDto(context.project(), context.analysis(), context.classes(), context.approvedBusinessRules(),
                context.approvedTestPlans(), List.of(saved), List.of(), List.of(), List.of());
        assertThat(GenerationValidationGate.validatePrerequisites(unit)).isEmpty();
        var unmarked = new TestCaseContextItemDto(3124L, generated.planId(), "TC-001", generated.testType(), generated.description(),
                generated.preconditions(), generated.testData(), generated.expectedResult(), generated.priority(), generated.traceSource(), "APPROVED", false);
        unit = new UnitTestContextDto(context.project(), context.analysis(), context.classes(), context.approvedBusinessRules(),
                context.approvedTestPlans(), List.of(unmarked), List.of(), List.of(), List.of());
        assertThat(GenerationValidationGate.validatePrerequisites(unit)).hasValueSatisfying(p -> assertThat(p).contains("3124", "review"));
    }

    @Test void aNewCaseCannotBypassEvidenceByInventingItsOwnSourceMismatchMarker() throws Exception {
        var f = fixture("realworld-upstream-collection");
        for (var item : f.path("response").path("cases")) ((ObjectNode) item).put("trace_source", "SOURCE_MISMATCH");
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isPresent();
    }

    @Test void preflightDoesNotTreatMissingEvidenceAsAProvenContradictionInAnApprovedCase() throws Exception {
        var f = fixture("realworld-upstream-collection");
        correctArguments(f, true);
        var item = (ObjectNode) f.path("response").path("cases").get(0);
        item.put("expected_result", item.path("expected_result").asText() + " IllegalArgumentException message \"runtime\"");
        var context = tcContext(f);
        var generated = json.treeToValue(f.get("response"), TestCaseResponseDto.class).cases().get(0);
        assertThat(GenerationValidationGate.validateTestCases(context, new TestCaseResponseDto(List.of(generated)))).isPresent();
        var saved = new TestCaseContextItemDto(3124L, generated.planId(), "TC-001", generated.testType(), generated.description(),
                generated.preconditions(), generated.testData(), generated.expectedResult(), generated.priority(), generated.traceSource(), "APPROVED", false);
        var unit = new UnitTestContextDto(context.project(), context.analysis(), context.classes(), context.approvedBusinessRules(),
                context.approvedTestPlans(), List.of(saved), List.of(), List.of(), List.of());
        assertThat(GenerationValidationGate.validatePrerequisites(unit)).isEmpty();
    }

    @Test void mentioningAnUnrelatedApprovedDependencyDoesNotDisableOtherChecks() throws Exception {
        var f = fixture("realworld-upstream-collection");
        var rule = (ObjectNode) f.path("context").path("approvedBusinessRules").get(0);
        rule.put("description", rule.path("description").asText() + " userRelationshipQueryService.followedUsers");
        assertThat(GenerationValidationGate.validateTestCases(tcContext(f), json.treeToValue(f.get("response"), TestCaseResponseDto.class))).isPresent();
    }

    @Test void correctsAnUnsupportedBusinessRuleThroughTheExistingGateway(@TempDir java.nio.file.Path root) throws Exception {
        var f = fixture("billing-upstream-message");
        String invalid = json.writeValueAsString(f.get("response"));
        var rule = (ObjectNode) f.path("response").path("rules").get(0);
        rule.put("description", rule.path("description").asText().replace("'<appointmentId>'.", "'<appointmentId>'"));
        String corrected = json.writeValueAsString(f.get("response"));
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildBusinessRuleGenerationContext(1L)).thenReturn(brContext(f));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(invalid, corrected);
        var agent = agent(contexts, client, root);
        assertThat(agent.generateBusinessRules(1L).rules()).hasSize(1);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("# Generation validation correction", "source exception message");
    }

    @Test void correctsCollectionSetupThroughTheExistingGateway(@TempDir java.nio.file.Path root) throws Exception {
        var f = fixture("realworld-upstream-collection");
        String invalid = json.writeValueAsString(f.get("response"));
        correctArguments(f, true);
        String corrected = json.writeValueAsString(f.get("response"));
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(1L, Set.of(2443L))).thenReturn(tcContext(f));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any(LlmRequestOptions.class))).thenReturn(invalid, corrected);
        assertThat(agent(contexts, client, root).generateTestCases(1L, Set.of(2443L)).cases()).hasSize(2);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("# Test Case semantic correction", "source calls", "before mutation");
    }

    private AIAgentService agent(GenerationContextBuilder contexts, LlmClient client, java.nio.file.Path root) {
        return new AIAgentService(contexts, new PromptManager(json), client,
                new GenerationResponseParser(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator()),
                new AiContextLogService(json, root.toString(), false, false));
    }

    private void correctArguments(JsonNode f, boolean removeInventedClaim) {
        for (var item : f.path("response").path("cases")) {
            var mocks = item.path("test_data").path("mocks");
            for (String key : List.of("articleFavoritesReadService.articlesFavoriteCount", "articleFavoritesReadService.userFavorites"))
                ((ArrayNode) mocks.path(key).path("arguments")).set(0, json.valueToTree(List.of("article-2", "article-1")));
            ((ArrayNode) mocks.path("userRelationshipQueryService.followingAuthors").path("arguments")).set(1, json.valueToTree(List.of("author-1", "author-1")));
            if (removeInventedClaim) ((ObjectNode) item).put("expected_result", item.path("expected_result").asText().split(" Các dependency", 2)[0]);
        }
    }

    private BusinessRuleGenerationContextDto brContext(JsonNode f) throws Exception {
        var c = json.treeToValue(f.get("context"), BusinessRuleGenerationContextDto.class);
        return new BusinessRuleGenerationContextDto(c.project(), c.analysis(), fullSources(c.classes()), c.serviceRepositoryRelations(),
                c.controllerServiceRelations(), c.dependencyCalls(), c.existingTests(), c.supportingResources(), c.contextWarnings());
    }
    private TestCaseContextDto tcContext(JsonNode f) throws Exception {
        var c = json.treeToValue(f.get("context"), TestCaseContextDto.class);
        return new TestCaseContextDto(c.project(), c.analysis(), fullSources(c.classes()), c.approvedBusinessRules(),
                c.approvedTestPlans(), c.existingTests(), c.supportingResources(), c.contextWarnings());
    }
    private UnitTestContextDto unitContext(JsonNode f) throws Exception {
        var c = json.treeToValue(f.get("context"), UnitTestContextDto.class);
        return new UnitTestContextDto(c.project(), c.analysis(), fullSources(c.classes()), c.approvedBusinessRules(),
                c.approvedTestPlans(), c.approvedTestCases(), c.existingApprovedTestCases(), c.previousGeneratedUnitTests(),
                c.existingTests(), c.supportingResources(), c.contextWarnings());
    }
    private List<ClassContextDto> fullSources(List<ClassContextDto> classes) {
        return classes.stream().map(c -> new ClassContextDto(c.id(), c.packageName(), c.className(), c.qualifiedName(),
                c.classType(), c.filePath(), c.sourceCode(), c.annotations(), c.methods())).toList();
    }
    private JsonNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/generated-contract/" + name + ".json")) { return json.readTree(input); }
    }
}
