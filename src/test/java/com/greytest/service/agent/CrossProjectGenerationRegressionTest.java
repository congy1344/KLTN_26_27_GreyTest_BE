package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

/** Tái hiện lỗi từ input/response thật, giữ nguyên oracle và dữ liệu nghiệp vụ đã duyệt. */
class CrossProjectGenerationRegressionTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test void rejectsOneInvocationForDuplicateInputsEnrichedBeforeDistinct() throws Exception {
        assertThat(validate("ecommerce-stream-verify", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("invocation count"));
    }

    @Test void reportsActualParserErrorSoCorrectionCanRepairDuplicateImportKeyword() throws Exception {
        assertThat(validate("ecommerce-stream-verify", code -> code.replace("import java.util.List;", "import import java.util.List;")))
                .hasValueSatisfying(message -> assertThat(message).contains("Parse error", "import"));
    }

    @Test void rejectsMockitoWhenOnATestLocalNullPlaceholder() throws Exception {
        assertThat(validate("realworld-placeholder-stub", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("placeholder helper", "Mockito.when"));
    }

    @Test void acceptsRealMapperSpyAsAnInjectableCollaborator() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, false, false))).isEmpty();
    }

    @Test void rejectsMockWhenApprovedSetupExplicitlyRequiresARealAppointmentMapper() throws Exception {
        assertThat(validate("hms-explicit-real-mapper", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("Approved fixture contract", "AppointmentMapper", "real"));
    }

    @Test void rejectsMockWhenApprovedSetupExplicitlyRequiresARealBillMapper() throws Exception {
        assertThat(validate("hms-explicit-real-mapper-second", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("Approved fixture contract", "BillMapper", "real"));
    }

    @Test void acceptsRealMapperWithoutChangingApprovedInputsOrAssertions() throws Exception {
        assertThat(validate("hms-explicit-real-mapper", code -> {
            var unit = new com.github.javaparser.JavaParser().parse(code).getResult().orElseThrow();
            var mapper = unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                    .filter(field -> field.getElementType().asString().equals("AppointmentMapper")).findFirst().orElseThrow();
            mapper.getAnnotationByName("Mock").orElseThrow().remove();
            mapper.addAnnotation("org.mockito.Spy");
            mapper.getVariable(0).setInitializer("new AppointmentMapper()");
            return unit.toString();
        })).isEmpty();
    }

    @Test void acceptsExplicitRealMapperConstructionDespiteAnUnusedMockField() throws Exception {
        assertThat(validate("hms-explicit-real-mapper", code -> explicitMapperConstruction(code, false))).isEmpty();
    }

    @Test void acceptsExplicitRealMapperRebindingWithInjectMocksStillPresent() throws Exception {
        assertThat(validate("hms-explicit-real-mapper", code -> explicitMapperConstruction(code, true))).isEmpty();
    }

    @Test void doesNotUseAnUnrelatedSameNameInjectMocksSutToInferMapperWiring() throws Exception {
        assertThat(validate("hms-explicit-real-mapper", code -> {
            var unit = new com.github.javaparser.JavaParser().parse(explicitMapperConstruction(code, false)).getResult().orElseThrow();
            unit.getType(0).addMember(com.github.javaparser.StaticJavaParser.parseBodyDeclaration(
                    "@InjectMocks com.other.AppointmentService unrelated;"));
            return unit.toString();
        }, UnaryOperator.identity(), fixture -> {
            var other = json.createObjectNode();
            other.put("id", 900004L).put("packageName", "com.other").put("className", "AppointmentService")
                    .put("qualifiedName", "com.other.AppointmentService").put("classType", "SERVICE")
                    .put("filePath", "Hospital-Management-System/HMS/src/main/java/com/other/AppointmentService.java")
                    .put("sourceCode", "package com.other; public class AppointmentService {}");
            other.putArray("annotations"); other.putArray("methods");
            ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.path("context").path("classes")).add(other);
        })).isEmpty();
    }

    private String explicitMapperConstruction(String code, boolean retainAnnotation) {
        var unit = new com.github.javaparser.JavaParser().parse(code).getResult().orElseThrow();
        var sut = unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .filter(field -> field.getElementType().asString().equals("AppointmentService")).findFirst().orElseThrow();
        if (!retainAnnotation) sut.getAnnotationByName("InjectMocks").orElseThrow().remove();
        unit.getType(0).addMember(com.github.javaparser.StaticJavaParser.parseBodyDeclaration(
                "@org.junit.jupiter.api.BeforeEach void setUp() { this.appointmentService = new AppointmentService(appointmentRepository, doctorRepository, patientRepository, new AppointmentMapper()); }"));
        return unit.toString();
    }

    @Test void doesNotGuessWhichSameNameProjectMapperTheRealRequirementMeans() throws Exception {
        assertThat(validate("hms-explicit-real-mapper", UnaryOperator.identity(), UnaryOperator.identity(), fixture -> {
            var other = json.createObjectNode();
            other.put("id", 900003L).put("packageName", "com.other").put("className", "AppointmentMapper")
                    .put("qualifiedName", "com.other.AppointmentMapper").put("classType", "CLASS")
                    .put("filePath", "Hospital-Management-System/HMS/src/main/java/com/other/AppointmentMapper.java")
                    .put("sourceCode", "package com.other; public class AppointmentMapper {}");
            other.putArray("annotations"); other.putArray("methods");
            ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.path("context").path("classes")).add(other);
        })).isEmpty();
    }

    @Test void rejectsRawMillisLowerBoundForApproximateJwtExpiration() throws Exception {
        assertThat(validate("realworld-jwt-millis", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("JWT", "seconds", "lower bound"));
        assertThat(validate("realworld-jwt-millis-second", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("JWT", "seconds", "lower bound"));
    }

    @Test void acceptsWholeSecondsForApproximateJwtExpiration() throws Exception {
        assertThat(validate("realworld-jwt-millis", code -> code.replace(
                "expiration.getTime() >= beforeGeneration + sessionTime * 1000L",
                "expiration.getTime() / 1000L >= (beforeGeneration + sessionTime * 1000L) / 1000L"))).isEmpty();
    }

    @Test void doesNotTreatAnUnassertedJwtComparisonAsAFailingLowerBound() throws Exception {
        assertThat(validate("realworld-jwt-millis", code -> code.replace(
                "assertTrue(expiration.getTime() >= beforeGeneration + sessionTime * 1000L);",
                "boolean diagnostic = expiration.getTime() >= beforeGeneration + sessionTime * 1000L;"))).isEmpty();
    }

    @Test void recognizesFullyQualifiedJUnitTestWhenCheckingJwtTimeBounds() throws Exception {
        assertThat(validate("realworld-jwt-millis", code -> code.replace("@Test", "@org.junit.jupiter.api.Test")))
                .hasValueSatisfying(message -> assertThat(message).contains("JWT", "lower bound"));
    }

    @Test void doesNotRejectANegatedJwtComparisonAsAPositiveLowerBound() throws Exception {
        assertThat(validate("realworld-jwt-millis", code -> code.replace(
                "assertTrue(expiration.getTime() >= beforeGeneration + sessionTime * 1000L);",
                "assertFalse(expiration.getTime() >= beforeGeneration + sessionTime * 1000L);")
                .replace("import static org.junit.jupiter.api.Assertions.assertTrue;", "import static org.junit.jupiter.api.Assertions.*;"))).isEmpty();
        assertThat(validate("realworld-jwt-millis", code -> code.replace(
                "assertTrue(expiration.getTime() >= beforeGeneration + sessionTime * 1000L);",
                "assertTrue(!(expiration.getTime() >= beforeGeneration + sessionTime * 1000L));"))).isEmpty();
    }

    @Test void acceptsSubsecondRoundingToleranceWithoutChangingJwtSessionTime() throws Exception {
        assertThat(validate("realworld-jwt-millis-second", code -> code.replace(
                "expiration.getTime() >= beforeGeneration + 1000L",
                "expiration.getTime() >= beforeGeneration + 1000L - 1000L"))).isEmpty();
    }

    @Test void preservesAnApprovedExactMillisecondsOracle() throws Exception {
        assertThat(validate("realworld-jwt-millis", UnaryOperator.identity(), UnaryOperator.identity(), fixture -> {
            var cases = (com.fasterxml.jackson.databind.node.ArrayNode) fixture.path("context").path("approvedTestCases");
            for (var item : cases) if (item.path("id").asLong() == 3453L)
                ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("expectedResult", "Expiration must preserve exact millisecond precision without rounding.");
        })).isEmpty();
    }

    @Test void acceptsSpySubclassForAnInjectedBaseMapper() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> {
            var unit = new com.github.javaparser.JavaParser().parse(realMapperSpy(code, false, false)).getResult().orElseThrow();
            var mapper = unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                    .filter(f -> f.getElementType().asString().equals("AppointmentMapper")).findFirst().orElseThrow();
            mapper.setAllTypes(com.github.javaparser.StaticJavaParser.parseType("AppointmentMapperImpl"));
            mapper.getVariable(0).setInitializer("new AppointmentMapperImpl()");
            unit.getType(0).addMember(com.github.javaparser.StaticJavaParser.parseBodyDeclaration(
                    "static class AppointmentMapperImpl extends AppointmentMapper {}"));
            return unit.toString();
        })).isEmpty();
    }

    @Test void acceptsSourceProvenSpyImplementationForAnInjectedInterface() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, false, false),
                source -> mapperContract(source).replace("class AppointmentMapper {", "class AppointmentMapper implements MapperContract {"),
                this::addMapperContract)).isEmpty();
    }

    @Test void doesNotCountAnUnrelatedSpyAsAnInjectedInterface() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, false, false),
                this::mapperContract, this::addMapperContract))
                .hasValueSatisfying(message -> assertThat(message).contains("require @Mock or @Spy dependencies: MapperContract"));
    }

    @Test void doesNotConfuseSpyParentInterfacesWithTheSameNameInDifferentPackages() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, false, false),
                source -> mapperContract(source).replace("class AppointmentMapper {",
                        "class AppointmentMapper implements com.other.MapperContract {"), fixture -> {
                    addMapperContract(fixture);
                    addOtherMapperContract(fixture);
                })).hasValueSatisfying(message -> assertThat(message).contains("require @Mock or @Spy dependencies: MapperContract"));
    }

    @Test void resolvesSamePackageSpyParentBeforeWildcardImportedNames() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, false, false),
                source -> mapperContract(source)
                        .replace("class AppointmentMapper {", "class AppointmentMapper implements MapperContract {")
                        .replace("package com.example.HMS.dto;", "package com.example.HMS.dto; import com.other.*;"),
                fixture -> { addMapperContract(fixture); addOtherMapperContract(fixture); })).isEmpty();
    }

    @Test void ignoresUnrelatedSameNameTypesOutsideInjectedDependencies() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, false, false),
                source -> mapperContract(source)
                        .replace("class AppointmentMapper {", "class AppointmentMapper implements MapperContract {")
                        .replace("class AppointmentService {", "class AppointmentService { public void unrelated(com.other.MapperContract unused) {}"),
                this::addMapperContract)).isEmpty();
    }

    private String mapperContract(String source) {
        return source.replace("import com.example.HMS.dto.AppointmentMapper;", "import com.example.HMS.dto.MapperContract;")
                .replace("AppointmentMapper appointmentMapper", "MapperContract appointmentMapper");
    }

    private void addMapperContract(com.fasterxml.jackson.databind.JsonNode fixture) {
        var contract = json.createObjectNode();
        contract.put("id", 900001L).put("packageName", "com.example.HMS.dto").put("className", "MapperContract")
                .put("qualifiedName", "com.example.HMS.dto.MapperContract").put("classType", "INTERFACE")
                .put("filePath", "Hospital-Management-System/HMS/src/main/java/com/example/HMS/dto/MapperContract.java")
                .put("sourceCode", "package com.example.HMS.dto; import com.example.HMS.entity.Appointment; "
                        + "public interface MapperContract { Appointment toEntity(AppointmentDto dto); AppointmentDto toDto(Appointment entity); }");
        contract.putArray("annotations");
        contract.putArray("methods");
        ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.path("context").path("classes")).add(contract);
    }

    private void addOtherMapperContract(com.fasterxml.jackson.databind.JsonNode fixture) {
        var classes = (com.fasterxml.jackson.databind.node.ArrayNode) fixture.path("context").path("classes");
        var other = ((com.fasterxml.jackson.databind.node.ObjectNode) classes.get(classes.size() - 1)).deepCopy();
        other.put("id", 900002L).put("packageName", "com.other").put("qualifiedName", "com.other.MapperContract")
                .put("filePath", "Hospital-Management-System/HMS/src/main/java/com/other/MapperContract.java")
                .put("sourceCode", "package com.other; import com.example.HMS.dto.AppointmentDto; "
                        + "import com.example.HMS.entity.Appointment; public interface MapperContract { "
                        + "Appointment toEntity(AppointmentDto dto); AppointmentDto toDto(Appointment entity); }");
        classes.add(other);
    }

    @Test void rejectsTestLocalAnnotationShadowingMockitoSpy() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, true, false)))
                .hasValueSatisfying(message -> assertThat(message).contains("AppointmentMapper", "@Spy"));
    }

    @Test void acceptsFullyQualifiedMockitoSpyDespiteTestLocalAnnotation() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> realMapperSpy(code, true, true))).isEmpty();
    }

    @Test void doesNotTreatUnknownInheritedSpyBindingAsAMissingCollaborator() throws Exception {
        assertThat(validate("hms-real-mapper-spy", code -> {
            var unit = new com.github.javaparser.JavaParser().parse(realMapperSpy(code, false, false)).getResult().orElseThrow();
            unit.getType(0).asClassOrInterfaceDeclaration().addExtendedType("EmptyBase");
            unit.addType(new com.github.javaparser.ast.body.ClassOrInterfaceDeclaration().setName("EmptyBase"));
            return unit.toString();
        })).isEmpty();
    }

    private String realMapperSpy(String code, boolean shadow, boolean qualified) {
        var unit = new com.github.javaparser.JavaParser().parse(code).getResult().orElseThrow();
        var mapper = unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .filter(f -> f.getElementType().asString().equals("AppointmentMapper")).findFirst().orElseThrow();
        mapper.getAnnotationByName("Mock").orElseThrow().remove();
        mapper.addAnnotation(qualified ? "org.mockito.Spy" : "Spy");
        mapper.getVariable(0).setInitializer("new AppointmentMapper()");
        unit.addImport("org.mockito.Spy");
        if (shadow) unit.getType(0).addMember(new com.github.javaparser.ast.body.AnnotationDeclaration().setName("Spy"));
        return unit.toString();
    }

    @Test void rejectsAssigningResponseDtoToAnUnrelatedEntityVariable() throws Exception {
        assertThat(validate("hospital-wrong-return-type", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("Source type contract", "DoctorResponseDto", "Doctor"));
    }

    @Test void acceptsTheActualDeclaredReturnTypeWithoutChangingAssertions() throws Exception {
        assertThat(validate("hospital-wrong-return-type", code -> code
                .replace("Doctor own", "com.hospital.doctor.dto.DoctorResponseDto own")
                .replace("Doctor missing", "com.hospital.doctor.dto.DoctorResponseDto missing"))).isEmpty();
    }

    @Test void acceptsSourceSupportedPerInputInvocationCount() throws Exception {
        assertThat(validate("ecommerce-stream-verify", code -> code
                .replace("verify(restTemplate)", "verify(restTemplate, org.mockito.Mockito.times(2))"))).isEmpty();
    }

    @Test void rejectsRepeatedDefaultVerifyForSharedIdInMappedHelper() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("invocation count"));
    }

    @Test void acceptsHelperInvocationCountAndSeparateDoctorIds() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", this::countedHelper)).isEmpty();
    }

    @Test void doesNotChooseBetweenDifferentLookupStubs() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", code -> countedHelper(code).replace(
                "when(appointmentRepository.findByPatientId(71L))",
                "Appointment comparison = new Appointment(); comparison.setPatientId(99L); comparison.setDoctorId(99L);\n"
                        + "when(appointmentRepository.findByPatientId(72L)).thenReturn(Arrays.asList(comparison, comparison));\n"
                        + "when(appointmentRepository.findByPatientId(71L))"), source -> source.replace(
                                "return appointmentRepository.findByPatientId(patientId)",
                                "appointmentRepository.findByPatientId(72L); return appointmentRepository.findByPatientId(patientId)"))).isEmpty();
    }

    @Test void doesNotExecuteDeferredFixtureSetters() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", code -> code.replace("first.setPatientId(71L);",
                "Runnable deferred = () -> first.setPatientId(71L);"))).isEmpty();
    }

    @Test void doesNotInferThroughUnknownFixtureSetterSideEffects() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", UnaryOperator.identity(), source -> source.replace(
                "private Long patientId;", "private Long patientId; public void setReason(String reason) { this.reason = reason; this.patientId = null; }"))).isEmpty();
    }

    @Test void doesNotResolveReassignedScalarIdFromItsOldInitializer() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", code -> countedHelper(code)
                .replace("first.setPatientId(71L);", "Long sharedId = 70L; sharedId = 71L; first.setPatientId(sharedId);")
                .replace("second.setPatientId(71L);", "second.setPatientId(sharedId);"))).isEmpty();
    }

    @Test void doesNotInferAllHelperCallsAfterAnAnswerThrows() throws Exception {
        assertThat(validate("hospital-helper-stream-verify", code -> countedHelper(code).replace(
                "when(patientServiceClient.getPatientById(71L)).thenReturn(",
                "when(patientServiceClient.getPatientById(71L)).thenAnswer(inv -> { throw new IllegalStateException(); }); //")
                .replace("verify(doctorServiceClient)", "verify(doctorServiceClient, org.mockito.Mockito.times(0))"))).isEmpty();
    }

    private String countedHelper(String code) {
        return code.replace("verify(patientServiceClient).getPatientById(71L);\n        verify(patientServiceClient).getPatientById(71L);",
                "verify(patientServiceClient, org.mockito.Mockito.times(2)).getPatientById(71L);");
    }

    @Test void doesNotInferFullTraversalWhenStreamShortCircuits() throws Exception {
        assertThat(validate("ecommerce-stream-verify", UnaryOperator.identity(),
                source -> source.replace("}).distinct()", "}).limit(1).distinct()"))).isEmpty();
    }

    @Test void doesNotInferBuilderValuesAfterFixtureMutation() throws Exception {
        assertThat(validate("ecommerce-stream-verify", code -> code.replace(
                "List<FavouriteDto> result =", "first.setUserId(12);\n        List<FavouriteDto> result ="))).isEmpty();
    }

    @Test void doesNotTreatDifferentInputIdsAsDuplicateCalls() throws Exception {
        assertThat(validate("ecommerce-stream-verify", code -> code.replace(
                "Favourite second = Favourite.builder().userId(11).productId(22)",
                "Favourite second = Favourite.builder().userId(12).productId(23)"))).isEmpty();
    }

    @Test void acceptsHexadecimalIdsWithTheirJavaNumericValue() throws Exception {
        assertThat(validate("ecommerce-stream-verify", code -> code.replace(".userId(11)", ".userId(0xB)")
                .replace("verify(restTemplate)", "verify(restTemplate, org.mockito.Mockito.times(2))"))).isEmpty();
    }

    @Test void preservesInvocationCountRequiredOnlyInApprovedDescription() throws Exception {
        assertThat(validate("ecommerce-stream-verify", UnaryOperator.identity(), UnaryOperator.identity(), node -> {
            for (var item : node.path("context").path("approvedTestCases")) {
                if (item.path("id").asLong() == 2856L) ((com.fasterxml.jackson.databind.node.ObjectNode) item)
                        .put("description", "Call each remote resource exactly once per unique ID.");
            }
        })).isEmpty();
    }

    @Test void preservesInvocationCountRequiredOnlyInApprovedRule() throws Exception {
        assertThat(validate("ecommerce-stream-verify", UnaryOperator.identity(), UnaryOperator.identity(), node -> {
            for (var item : node.path("context").path("approvedBusinessRules")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("description", "Call remote resources exactly once per unique ID.");
            }
        })).isEmpty();
    }

    @Test void preservesVietnameseCountBeyondThree() throws Exception {
        assertThat(validate("ecommerce-stream-verify", code -> code.replace("verify(restTemplate)",
                "verify(restTemplate, org.mockito.Mockito.times(4))"), UnaryOperator.identity(), node -> {
            for (var item : node.path("context").path("approvedTestCases")) {
                if (item.path("id").asLong() == 2856L) ((com.fasterxml.jackson.databind.node.ObjectNode) item)
                        .put("description", "Gọi bốn lần cho mỗi tài nguyên được yêu cầu.");
            }
        })).isEmpty();
    }

    @Test void rejectsOldCaptorValueAfterAnswerMutatesTheSameEntity() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", UnaryOperator.identity()))
                .hasValueSatisfying(message -> assertThat(message).contains("captor", "mutat"));
    }

    @Test void doesNotInferConditionalAnswerMutation() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", code -> code.replace(
                "entity.setOrderId(501);", "if (entity.getOrderId() != null) entity.setOrderId(501);"))).isEmpty();
    }

    @Test void doesNotTreatAnIntermediateAnswerWriteAsFinalState() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", code -> code.replace(
                "entity.setOrderId(501);", "entity.setOrderId(501); entity.setOrderId(null);"))).isEmpty();
    }

    @Test void acceptsCurrentCaptorStateWithoutChangingApprovedOutput() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", code -> code.replace(
                "assertEquals(null, captor.getValue().getOrderId())", "assertEquals(501, captor.getValue().getOrderId())"))).isEmpty();
    }

    @Test void acceptsEquivalentHexadecimalMutationLiteral() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", code -> code.replace("entity.setOrderId(501)", "entity.setOrderId(0x1F5)")
                .replace("assertEquals(null, captor.getValue().getOrderId())", "assertEquals(501, captor.getValue().getOrderId())"))).isEmpty();
    }

    @Test void doesNotInferStateThroughTransformingSetter() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", code -> code.replace(
                "assertEquals(null, captor.getValue().getOrderId())", "assertEquals(502, captor.getValue().getOrderId())"),
                code -> code.replace("private Integer orderId;", "private Integer orderId; public void setOrderId(Integer id) { this.orderId = id == null ? null : id + 1; }"))).isEmpty();
    }

    @Test void doesNotApplyGetAccessorProofToADifferentIsAccessor() throws Exception {
        assertThat(validate("ecommerce-mutated-captor", code -> code.replace(
                "assertEquals(null, captor.getValue().getOrderId())", "assertEquals(502, captor.getValue().isOrderId())"),
                code -> code.replace("private Integer orderId;", "private Integer orderId; public Integer isOrderId() { return orderId + 1; }"))).isEmpty();
    }

    @Test void acceptsTheRealSameModuleEnumImport() throws Exception {
        assertThat(validate("ecommerce-same-module-import", UnaryOperator.identity())).isEmpty();
    }

    @Test void reportsTheActualMissingApprovedRule() throws Exception {
        var fixture = fixture("ecommerce-missing-payment-plan");
        var context = json.treeToValue(fixture.get("context"), TestPlanContextDto.class);
        var response = json.treeToValue(fixture.get("response"), TestPlanResponseDto.class);
        assertThat(GenerationValidationGate.validateReferences(context, response))
                .hasValueSatisfying(message -> assertThat(message).contains("4565", "Missing"));
    }

    @Test void rejectsActualNulInMockKeysBeforeDatabasePersistence() throws Exception {
        var fixture = fixture("realworld-nul-response");
        var context = json.treeToValue(fixture.get("context"), TestCaseContextDto.class);
        var response = json.treeToValue(fixture.get("response"), TestCaseResponseDto.class);
        assertThat(GenerationValidationGate.validateReferences(context, response))
                .hasValueSatisfying(message -> assertThat(message).contains("U+0000", "Regenerate"));
    }

    @Test void acceptsLiteralEscapeTextWithoutDeletingRequiredCodePoint() throws Exception {
        var fixture = fixture("realworld-nul-response");
        var context = json.treeToValue(fixture.get("context"), TestCaseContextDto.class);
        var response = json.treeToValue(fixture.get("response"), TestCaseResponseDto.class);
        String escaped = json.writeValueAsString(response).replace("\\u0000", "\\\\u0000");
        var encoded = json.readValue(escaped, TestCaseResponseDto.class);
        assertThat(GenerationValidationGate.validateReferences(context, encoded)).isEmpty();
        assertThat(encoded.toString()).contains("\\u0000").doesNotContain("\u0000");
    }

    @Test void correctionPromptNamesMissingRuleAndAcceptsCompleteRetry(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var fixture = fixture("ecommerce-missing-payment-plan");
        var context = json.treeToValue(fixture.get("context"), TestPlanContextDto.class);
        var incomplete = json.treeToValue(fixture.get("response"), TestPlanResponseDto.class);
        var missing = context.approvedBusinessRules().stream().filter(r -> r.id().equals(4565L)).findFirst().orElseThrow();
        var plans = new java.util.ArrayList<>(incomplete.plans());
        plans.add(new GeneratedTestPlanDto(missing.methodId(), missing.id(), List.of(missing.id()),
                "Save payment", "Cover the approved save payment rule", "HAPPY_PATH"));
        var contexts = org.mockito.Mockito.mock(GenerationContextBuilder.class);
        org.mockito.Mockito.when(contexts.buildTestPlanContext(1L)).thenReturn(context);
        var client = org.mockito.Mockito.mock(LlmClient.class);
        org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LlmRequestOptions.class))).thenReturn(
                        json.writeValueAsString(incomplete), json.writeValueAsString(new TestPlanResponseDto(plans)));
        var parser = new GenerationResponseParser(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        var agent = new AIAgentService(contexts, new PromptManager(json), client, parser,
                new AiContextLogService(json, root.toString(), false, false));

        assertThat(agent.generateTestPlan(1L).plans()).hasSize(plans.size());
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).complete(prompts.capture(),
                org.mockito.ArgumentMatchers.any(LlmRequestOptions.class));
        assertThat(prompts.getAllValues().get(1)).contains("# Generation validation correction", "4565", "complete plans array");
    }

    private Optional<String> validate(String name, UnaryOperator<String> transform) throws Exception {
        return validate(name, transform, UnaryOperator.identity());
    }

    private Optional<String> validate(String name, UnaryOperator<String> transform, UnaryOperator<String> sourceTransform) throws Exception {
        return validate(name, transform, sourceTransform, node -> { });
    }

    private Optional<String> validate(String name, UnaryOperator<String> transform, UnaryOperator<String> sourceTransform,
            java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> fixtureTransform) throws Exception {
        var fixture = fixture(name);
        fixtureTransform.accept(fixture);
        var context = json.treeToValue(fixture.get("context"), UnitTestContextDto.class);
        var test = json.treeToValue(fixture.get("test"), GeneratedUnitTestDto.class);
        var cases = context.approvedTestCases().stream().filter(c -> c.id().equals(test.caseId())).toList();
        var plans = context.approvedTestPlans().stream().filter(p -> cases.stream().anyMatch(c -> c.testPlanId().equals(p.id()))).toList();
        var rules = context.approvedBusinessRules().stream().filter(r -> plans.stream()
                .anyMatch(p -> p.coveredRuleIds().contains(r.id()) || p.businessRuleId().equals(r.id()))).toList();
        var classes = context.classes().stream().map(c -> new ClassContextDto(c.id(), c.packageName(), c.className(),
                c.qualifiedName(), c.classType(), c.filePath(), sourceTransform.apply(c.sourceCode()), c.annotations(), c.methods())).toList();
        var selected = new UnitTestContextDto(context.project(), context.analysis(), classes, rules, plans, cases,
                List.of(), List.of(), List.of(), context.supportingResources(), context.contextWarnings());
        return GeneratedUnitTestSemanticValidator.validate(selected, new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                test.caseId(), test.testClassName(), test.testMethodName(), test.packageName(), test.generationType(),
                transform.apply(test.sourceCode())))), ProjectJavaVersionDetector.TestFramework.JUNIT5);
    }

    private com.fasterxml.jackson.databind.JsonNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/generated-contract/" + name + ".json")) {
            return json.readTree(input);
        }
    }
}
