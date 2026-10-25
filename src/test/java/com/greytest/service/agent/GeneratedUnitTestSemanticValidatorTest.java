package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;
import com.greytest.service.agent.ProjectJavaVersionDetector.MockitoVersionInfo;

class GeneratedUnitTestSemanticValidatorTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findByName(null);",
            "org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(0)).findByName(null);",
            "org.mockito.Mockito.verifyNoInteractions(repository);",
            "org.mockito.Mockito.verifyNoMoreInteractions(repository);"})
    void zeroInteractionVerificationMustStillRunAfterTheExpectedThrow(String verification) {
        String source = exceptionFlowTest("", false)
                .replace("org.mockito.Mockito.when(repository.findByName(\"alice\")).thenReturn(null); ", "")
                .replace("() -> service.check(\"alice\")", "() -> { service.check(null); " + verification + " }");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source),
                TestFramework.JUNIT5, new MockitoVersionInfo("5.11.0", "fixture")))
                .hasValueSatisfying(message -> assertThat(message).contains("Verification", "after", "exception"));
    }

    @Test
    void approvedNoDatabaseAccessContractCanExposeAFaultySourceLookup() {
        var base = exceptionFlowContext(false);
        var original = base.approvedTestCases().get(0);
        var approved = new TestCaseContextItemDto(original.id(), original.testPlanId(), original.caseCode(),
                original.testType(), "Reject null without database access", "Null name", original.testData(),
                "Throw IllegalArgumentException and never query the repository", original.priority(),
                original.traceSource(), "APPROVED", false);
        var context = new UnitTestContextDto(base.project(), base.analysis(), base.classes(),
                base.approvedBusinessRules(), base.approvedTestPlans(), List.of(approved),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
        String test = exceptionFlowTest("org.mockito.Mockito.verifyNoInteractions(repository, notifier);", false)
                .replace("org.mockito.Mockito.when(repository.findByName(\"alice\")).thenReturn(null); ", "")
                .replace("service.check(\"alice\")", "service.check(null)");
        var version = new MockitoVersionInfo("5.11.0", "fixture");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(test), TestFramework.JUNIT5, version)).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response("import org.mockito.junit.jupiter.Strictness; " + test), TestFramework.JUNIT5, version))
                .hasValueSatisfying(message -> assertThat(message).contains("Strictness"));
    }

    @Test
    void preservesAnApprovedFailingOracleWhileStillRejectingInvalidMockitoImports() {
        var base = contextWithServiceSource("demo.Service", "value", "public int value() { return 8; }",
                "package demo; public class Service { public int value() { return 8; } }");
        var original = base.approvedTestCases().get(0);
        var approved = new TestCaseContextItemDto(original.id(), original.testPlanId(), original.caseCode(),
                original.testType(), "Approved value is ten", original.preconditions(), original.testData(),
                "Return 10", original.priority(), original.traceSource(), "APPROVED", false);
        var context = new UnitTestContextDto(base.project(), base.analysis(), base.classes(),
                base.approvedBusinessRules(), base.approvedTestPlans(), List.of(approved),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
        String test = "package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "Service service = new Service(); org.junit.jupiter.api.Assertions.assertEquals(10, service.value()); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(test), TestFramework.JUNIT5)).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(test.replace("package demo;", "package demo; import org.mockito.junit.jupiter.Strictness;")),
                TestFramework.JUNIT5)).hasValueSatisfying(message -> assertThat(message).contains("Strictness"));
    }

    @Test
    void rejectsWrongLombokConstructorArityFromSupportingSource() {
        var base = contextWithServiceSource("demo.Service", "send", "public void send() {}",
                "package demo; class Service { public void send() {} }");
        var profile = new ClassContextDto(2L, "demo", "CustomerProfile", "demo.CustomerProfile", "CLASS",
                "CustomerProfile.java", """
                package demo;
                @lombok.NoArgsConstructor @lombok.AllArgsConstructor
                class CustomerProfile {
                    private String id; private String name; private String bio;
                    private String image; private boolean following;
                }
                """, List.of(), List.of());
        var ctx = withSupportingClass(base, profile);
        String test = "package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "new CustomerProfile(\"id\", \"name\", \"bio\", \"image\"); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(test)))
                .hasValueSatisfying(message -> assertThat(message).contains("CustomerProfile", "constructor", "4"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx,
                response(test.replace("\"image\");", "\"image\", false);")))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx,
                response(test.replace("\"id\", \"name\", \"bio\", \"image\"", "")))).isEmpty();
    }

    @Test
    void rejectsIncompatibleConcreteGenericArgumentsAndReturnAssignments() {
        String method = "public void browse(Cursor<java.time.Instant> page) {}";
        var base = contextWithServiceSource("demo.Service", "browse", method,
                "package demo; class Service { " + method
                        + " public java.util.List<String> list() { return null; } }");
        var cursor = new ClassContextDto(2L, "demo", "Cursor", "demo.Cursor", "CLASS", "Cursor.java",
                "package demo; class Cursor<T> {}", List.of(), List.of());
        var ctx = withSupportingClass(base, cursor);
        String argumentTest = "package demo; class ServiceTest { Service service; "
                + "@org.junit.jupiter.api.Test void testCase() { Cursor<Object> page = new Cursor<>(); "
                + "service.browse(page); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(argumentTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("generic", "browse", "Object", "Instant"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx,
                response(argumentTest.replace("Cursor<Object>", "Cursor<java.time.Instant>")))).isEmpty();
        String returnTest = "package demo; class ServiceTest { Service service; "
                + "@org.junit.jupiter.api.Test void testCase() { java.util.List<Object> items = service.list(); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(returnTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("generic", "list", "Object", "String"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx,
                response(returnTest.replace("List<Object>", "List<String>")))).isEmpty();
    }

    @Test
    void allowsVarargsAndDoesNotGuessMissingOrAmbiguousDeclarations() {
        var base = contextWithServiceSource("demo.Service", "send", "public void send() {}",
                "package demo; class Service { public void send() {} }");
        var values = new ClassContextDto(2L, "demo", "Values", "demo.Values", "CLASS", "Values.java",
                "package demo; class Values { Values(String... names) {} }", List.of(), List.of());
        var ctx = withSupportingClass(base, values);
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response("""
                package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() {
                    new Values(); new Values("a", "b"); new UnknownType("a", "b");
                } }
                """))).isEmpty();
    }

    private UnitTestContextDto withSupportingClass(UnitTestContextDto base, ClassContextDto supporting) {
        return new UnitTestContextDto(base.project(), base.analysis(), List.of(base.classes().get(0), supporting),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
    }

    @Test
    void allowsUnsupportedOverloadsInsteadOfGuessingAConcreteGenericMismatch() {
        String method = "public void browse(java.util.List<String> page) {}";
        for (String declaration : List.of(
                "class Service { " + method + " public void browse(Object... items) {} }",
                "class Service implements Parent { " + method + " }")) {
            var ctx = contextWithServiceSource("demo.Service", "browse", method, "package demo; " + declaration);
            assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response("""
                    package demo; class ServiceTest { Service service;
                        @org.junit.jupiter.api.Test void testCase() {
                            java.util.List<Integer> page = new java.util.ArrayList<>();
                            service.browse(page);
                        }
                    }
                    """))).isEmpty();
        }
    }

    @Test
    void ignoresLocalsOutsideTheReferenceScopeAndLombokImplicitFinalDefaults() {
        String method = "public void browse(Cursor<java.time.Instant> page) {}";
        var base = contextWithServiceSource("demo.Service", "browse", method, "package demo; class Service { " + method + " }");
        var cursor = new ClassContextDto(2L, "demo", "Cursor", "demo.Cursor", "CLASS", "Cursor.java",
                "package demo; class Cursor<T> {}", List.of(), List.of());
        assertThat(GeneratedUnitTestSemanticValidator.validate(withSupportingClass(base, cursor), response("""
                package demo; class ServiceTest { Service service; Cursor<java.time.Instant> page;
                    @org.junit.jupiter.api.Test void testCase() {
                        service.browse(page);
                        { Cursor<Object> page = null; }
                    }
                }
                """))).isEmpty();
        var immutable = new ClassContextDto(3L, "demo", "Immutable", "demo.Immutable", "CLASS", "Immutable.java",
                "package demo; @lombok.Value class Immutable { String id; String label = \"fixed\"; }", List.of(), List.of());
        assertThat(GeneratedUnitTestSemanticValidator.validate(withSupportingClass(base, immutable), response("""
                package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() {
                    new Immutable("id");
                } }
                """))).isEmpty();
    }

    @Test
    void doesNotResolveUnsupportedLoopScopesOrLombokTolerateConstructorsAsCertain() {
        String method = "public void browse(Cursor<java.time.Instant> page) {}";
        var base = contextWithServiceSource("demo.Service", "browse", method, "package demo; class Service { " + method + " }");
        var cursor = new ClassContextDto(2L, "demo", "Cursor", "demo.Cursor", "CLASS", "Cursor.java",
                "package demo; class Cursor<T> {}", List.of(), List.of());
        for (String body : List.of(
                "for (Cursor<Object> page = null; page != null;) {} service.browse(page);",
                "java.util.function.Consumer<Cursor<java.time.Instant>> consumer = page -> service.browse(page);")) {
            String fieldType = body.startsWith("for") ? "java.time.Instant" : "Object";
            assertThat(GeneratedUnitTestSemanticValidator.validate(withSupportingClass(base, cursor), response(
                    "package demo; class ServiceTest { Service service; Cursor<" + fieldType + "> page; "
                            + "@org.junit.jupiter.api.Test void testCase() { " + body + " } }")))
                    .isEmpty();
        }
        var tolerated = new ClassContextDto(3L, "demo", "Tolerated", "demo.Tolerated", "CLASS", "Tolerated.java",
                "package demo; @lombok.Data class Tolerated { final String id; "
                        + "@lombok.experimental.Tolerate Tolerated() { id = \"default\"; } }", List.of(), List.of());
        assertThat(GeneratedUnitTestSemanticValidator.validate(withSupportingClass(base, tolerated), response("""
                package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() {
                    new Tolerated("id");
                } }
                """))).isEmpty();
    }

    @Test
    void allowsValidFixtureWhenAnUnselectedOverloadThrowsUnsupportedOperationException() {
        String source = """
                class ServiceTest {
                    Service service;
                    @org.junit.jupiter.api.Test void testCase() {
                        Object settings = newSettings(1);
                        service.send(settings);
                        org.junit.jupiter.api.Assertions.assertNotNull(settings);
                    }
                    private Object newSettings(int index) { return new Object(); }
                    private Object newSettings(String missing) { throw new UnsupportedOperationException(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send(Object settings) {}"), response(source))).isEmpty();
    }

    @Test
    void rejectsFixtureHelpersThatUnconditionallyThrowUnsupportedOperationException() {
        String source = """
                class ServiceTest {
                    Service service;
                    @org.junit.jupiter.api.Test void testCase() {
                        Object settings = newSettings();
                        service.send(settings);
                        org.junit.jupiter.api.Assertions.assertNotNull(settings);
                    }
                    private Object newSettings() {
                        throw new UnsupportedOperationException("Production DTO API is unavailable");
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send(Object settings) {}"), response(source)))
                .hasValueSatisfying(message -> assertThat(message).contains("placeholder helper", "newSettings"));
    }

    @Test
    void allowsUnsupportedOperationExceptionFromAProductionDependencyStub() {
        String source = """
                class ServiceTest {
                    Service service;
                    @org.junit.jupiter.api.Test void testCase() {
                        org.mockito.Mockito.doThrow(new UnsupportedOperationException("dependency failure"))
                            .when(repository).save();
                        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                            () -> service.send());
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() { repository.save(); }"), response(source))).isEmpty();
    }

    @Test
    void rejectsReflectionAndAssertThrowsUsedAsEnumFixtures() {
        String method = "public void send(Enum<?> event) {}";
        var context = contextWithServiceSource("demo.Service", "send", method,
                "package demo; class Service { " + method + " }");
        for (String fixture : List.of(
                "Enum event = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {});",
                "Enum<?> event = (Enum<?>) Class.forName(\"demo.Status\").getEnumConstants()[0];",
                "Enum event; event = (Enum) Class.forName(\"demo.Status\").getField(\"READY\").get(null);",
                "this.event = (Enum<?>) Class.forName(\"demo.Status\").getEnumConstants()[0];",
                "service.send((Enum<?>) Class.forName(\"demo.Status\").getEnumConstants()[0]);")) {
            String source = "class ServiceTest { Service service; Enum<?> event; @org.junit.jupiter.api.Test "
                    + "void testCase() throws Exception { " + fixture + " } }";
            assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source)))
                    .as(fixture)
                    .hasValueSatisfying(message -> assertThat(message).contains("Enum", "reflection", "assertThrows"));
        }
    }

    @Test
    void acceptsDeclaredEnumConstantAndNullWithoutRejectingExceptionAssertions() {
        String method = "public void send(Enum<?> event) {}";
        var base = contextWithServiceSource("demo.Service", "send", method,
                "package demo; class Service { " + method + " }");
        var status = new ClassContextDto(2L, "demo.enums", "Status", "demo.enums.Status", "ENUM",
                "Status.java", "package demo.enums; enum Status { READY }", List.of(), List.of());
        var context = new UnitTestContextDto(base.project(), base.analysis(),
                List.of(base.classes().get(0), status), base.approvedBusinessRules(), base.approvedTestPlans(),
                base.approvedTestCases(), base.existingApprovedTestCases(), base.previousGeneratedUnitTests(),
                base.existingTests());
        String source = "class ServiceTest { Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Enum<?> event = demo.enums.Status.READY; service.send(event); "
                + "org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, "
                + "() -> service.send(null)); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source))).isEmpty();
    }

    @Test
    void acceptsEnumResultReturnedByService() {
        String method = "public Enum<?> getStatus() { return null; }";
        var context = contextWithServiceSource("demo.Service", "getStatus", method,
                "package demo; class Service { " + method + " }");
        String source = "class ServiceTest { Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Enum<?> actual = service.getStatus(); org.junit.jupiter.api.Assertions.assertNull(actual); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source))).isEmpty();
    }

    @Test
    void rejectsAtStartOfDayOnLocalDateTimeChain() {
        String invalid = "class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "java.time.LocalTime.of(23, 59).atDate(java.time.LocalDate.now())"
                + ".plusDays(1).atStartOfDay(); } }";
        String valid = "class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "java.time.LocalDate.now().plusDays(1).atStartOfDay(); } }";
        String convertedToDate = "class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "java.time.LocalDateTime.now().toLocalDate().atStartOfDay(); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(invalid)))
                .hasValueSatisfying(message -> assertThat(message).contains("LocalDateTime", "atStartOfDay"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(valid))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(convertedToDate))).isEmpty();
    }

    @Test
    void rejectsAtStartOfDayOnGetterDeclaredAsLocalDateTime() {
        UnitTestContextDto base = context("testCase", "public void testCase() {}");
        ClassContextDto appointment = new ClassContextDto(2L, "demo", "Appointment", "demo.Appointment",
                "ENTITY", "Appointment.java",
                "package demo; class Appointment { java.time.LocalDateTime cancelledAt; "
                        + "public java.time.LocalDateTime getCancelledAt() { return cancelledAt; } }",
                List.of(), List.of());
        UnitTestContextDto context = new UnitTestContextDto(base.project(), base.analysis(),
                List.of(base.classes().get(0), appointment), base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), base.existingApprovedTestCases(),
                base.previousGeneratedUnitTests(), base.existingTests());
        String generated = "package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "Appointment appointment = null; appointment.getCancelledAt().atStartOfDay(); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated)))
                .hasValueSatisfying(message -> assertThat(message).contains("LocalDateTime", "atStartOfDay"));
    }

    @Test
    void acceptsApprovedNullCancelledTimestampDespiteProductionMutation() {
        String method = "public Response updateStatus(Status status) { "
                + "Response result = new Response(); if (status == Status.CANCELLED) "
                + "result.setCancelledAt(java.time.LocalDateTime.now()); return result; }";
        String invalid = "class ServiceTest { Service service; "
                + "@org.junit.jupiter.api.Test void testCase() { "
                + "Response result = service.updateStatus(Status.CANCELLED); "
                + "org.junit.jupiter.api.Assertions.assertNull(result.getCancelledAt()); } }";
        String valid = invalid.replace("assertNull", "assertNotNull");
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "updateStatus", method,
                "package demo; class Service { " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(invalid)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(valid))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(invalid.replace("service.updateStatus(Status.CANCELLED)",
                        "service.updateStatus(Status.SCHEDULED)")))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(invalid.replace("result.getCancelledAt()", "original.getCancelledAt()")
                        .replace("Response result =", "Response original = new Response(); Response result ="))))
                .isEmpty();
    }

    @Test
    void acceptsApprovedRepositoryFixturesWithoutForcingProductionSuccess() {
        String method = "public Response getById(Long id) { return repository.findById(id)"
                + ".orElseThrow(() -> new RuntimeException()); }";
        String source = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Response result = service.getById(5001L); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        String stubbed = source.replace("Response result =", "org.mockito.Mockito.when(repository.findById(5001L))"
                + ".thenReturn(java.util.Optional.of(new Response())); Response result =");
        String emptyStub = stubbed.replace("java.util.Optional.of(new Response())", "java.util.Optional.empty()");
        String wrongIdStub = stubbed.replace("repository.findById(5001L)", "repository.findById(42L)");
        String siblingStub = source.replace("@org.junit.jupiter.api.Test void testCase()",
                "@org.junit.jupiter.api.Test void otherCase() { "
                + "org.mockito.Mockito.when(repository.findById(5001L))"
                + ".thenReturn(java.util.Optional.of(new Response())); } "
                + "@org.junit.jupiter.api.Test void testCase()");
        String lateStub = source.replace("org.junit.jupiter.api.Assertions.assertNotNull(result);",
                "org.junit.jupiter.api.Assertions.assertNotNull(result); "
                + "org.mockito.Mockito.when(repository.findById(5001L))"
                + ".thenReturn(java.util.Optional.of(new Response()));");
        String doReturnStubbed = source.replace("Response result =", "org.mockito.Mockito.doReturn("
                + "java.util.Optional.of(new Response())).when(repository).findById(5001L); Response result =");
        String localId = stubbed.replace("Response result = service.getById(5001L);",
                "Long appointmentId = 5001L; Response result = service.getById(appointmentId);");
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(emptyStub)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(wrongIdStub)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(siblingStub)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(lateStub)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(stubbed))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(doReturnStubbed))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(localId))).isEmpty();
    }

    @Test
    void allowsSuccessBranchThatReturnsBeforeRepositoryLookup() {
        String method = "public Response getById(Long id) { if (id == null) return new Response(); "
                + "return repository.findById(id).orElseThrow(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Response result = service.getById(null); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(generated.replace("service.getById(null)", "service.getById(5001L)"))))
                .isEmpty();
    }

    @Test
    void allowsRepositoryLookupUsingDtoIdInsteadOfFirstServiceArgument() {
        String method = "public Doctor update(Long appointmentId, Dto dto) { "
                + "return doctorRepository.findById(dto.getDoctorId()).orElseThrow(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock DoctorRepository doctorRepository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Dto dto = new Dto(); dto.setDoctorId(42L); Doctor doctor = new Doctor(); "
                + "org.mockito.Mockito.when(doctorRepository.findById(42L))"
                + ".thenReturn(java.util.Optional.of(doctor)); "
                + "Doctor result = service.update(5001L, dto); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "update", method,
                "package demo; class Service { DoctorRepository doctorRepository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated))).isEmpty();
    }

    @Test
    void acceptsApprovedFixturesWhenSourceChecksOptionalIsEmpty() {
        String method = "public Response getById(Long id) { java.util.Optional<Response> found = "
                + "repository.findById(id); if (found.isEmpty()) throw new RuntimeException(); "
                + "return found.get(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Response result = service.getById(5001L); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(generated.replace("Response result =", "org.mockito.Mockito.when(repository.findById(5001L))"
                        + ".thenReturn(java.util.Optional.of(new Response())); Response result =")))).isEmpty();
    }

    @Test
    void preservesApprovedFixturesAfterSeparateAssertThrows() {
        String method = "public Response getById(Long id) { if (id < 0) "
                + "throw new IllegalArgumentException(); return repository.findById(id).orElseThrow(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, "
                + "() -> service.getById(-1L)); Response result = service.getById(5001L); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated)))
                .isEmpty();
    }

    @Test
    void allowsOptionalEmptyWhenProductionHasFallbackInsteadOfGuaranteedThrow() {
        String method = "public Response getById(Long id, boolean strict) { "
                + "java.util.Optional<Response> found = repository.findById(id); "
                + "if (found.isEmpty()) { if (strict) throw new RuntimeException(); "
                + "return new Response(); } return found.get(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "org.mockito.Mockito.when(repository.findById(5001L))"
                + ".thenReturn(java.util.Optional.empty()); "
                + "Response result = service.getById(5001L, false); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated))).isEmpty();
    }

    @Test
    void preservesApprovedFixturesAfterNamedExceptionLambda() {
        String method = "public Response getById(Long id) { if (id == null) "
                + "throw new IllegalArgumentException(); return repository.findById(id).orElseThrow(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "org.junit.jupiter.api.function.Executable missing = () -> service.getById(null); "
                + "org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, missing); "
                + "Response result = service.getById(5001L); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated)))
                .isEmpty();
    }

    @Test
    void preservesApprovedFixturesWhenSourceLogsThenThrows() {
        String method = "public Response getById(Long id) { java.util.Optional<Response> found = "
                + "repository.findById(id); if (found.isEmpty()) { log.warn(\"Missing\"); "
                + "throw new RuntimeException(); } return found.get(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Response result = service.getById(5001L); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Logger log; Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated)))
                .isEmpty();
    }

    @Test
    void preservesApprovedFixturesWhenSourceChecksFindByIdDirectly() {
        String method = "public Response getById(Long id) { "
                + "if (repository.findById(id).isEmpty()) throw new RuntimeException(); "
                + "return new Response(); }";
        String generated = "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; "
                + "@org.mockito.InjectMocks Service service; @org.junit.jupiter.api.Test void testCase() { "
                + "Response result = service.getById(5001L); "
                + "org.junit.jupiter.api.Assertions.assertNotNull(result); } }";
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "getById", method,
                "package demo; class Service { Repository repository; " + method + " }");

        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(generated)))
                .isEmpty();
    }

    @Test
    void rejectsCallToMethodMissingFromProductionService() {
        String serviceSource = "package demo; class Service { public void cancel(Long id) {} }";
        String generatedSource = "class ServiceTest { Service service; "
                + "@org.junit.jupiter.api.Test void testCase() { service.complete(1L); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "cancel", "public void cancel(Long id) {}", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("complete", "does not exist"));
    }

    @Test
    void rejectsMissingServiceMethodInAnotherTestMethodOfSameFile() {
        String serviceSource = "package demo; class Service { public void cancel(Long id) {} }";
        String generatedSource = "class ServiceTest { Service service; "
                + "@org.junit.jupiter.api.Test void testCase() { service.cancel(1L); } "
                + "@org.junit.jupiter.api.Test void otherCase() { service.completeAppointment(1L); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "cancel", "public void cancel(Long id) {}", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("completeAppointment", "does not exist"));
    }

    @Test
    void rejectsRepositoryTypeMatcherPassedToSave() {
        String generatedSource = "class ServiceTest { DoctorRepository doctorRepository; "
                + "@org.junit.jupiter.api.Test void testCase() { "
                + "org.mockito.Mockito.verify(doctorRepository, org.mockito.Mockito.never())"
                + ".save(org.mockito.ArgumentMatchers.any(DoctorRepository.class)); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("save", "DoctorRepository", "entity"));
    }

    @Test
    void rejectsPositionalMockitoSettingsStrictness() {
        String generatedSource = "@org.mockito.junit.jupiter.MockitoSettings(org.mockito.quality.Strictness.STRICT_STUBS) "
                + "class ServiceTest { @org.junit.jupiter.api.Test void testCase() {} }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("MockitoSettings", "strictness"));
    }

    @Test
    void rejectsEnumValueOfUnknownConstantAndMissingImport() {
        UnitTestContextDto base = contextWithServiceSource("demo.Service", "send", "public void send() {}",
                "package demo; import demo.enums.Status; class Service { public void send() {} }");
        ClassContextDto status = new ClassContextDto(2L, "demo.enums", "Status", "demo.enums.Status", "ENUM",
                "Status.java", "package demo.enums; enum Status { SCHEDULED, CANCELLED }", List.of(), List.of());
        UnitTestContextDto ctx = new UnitTestContextDto(base.project(), base.analysis(),
                List.of(base.classes().get(0), status), base.approvedBusinessRules(), base.approvedTestPlans(),
                base.approvedTestCases(), base.existingApprovedTestCases(), base.previousGeneratedUnitTests(),
                base.existingTests());
        String invalid = "package demo; class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "Status status = Status.valueOf(\"CONFIRMED\"); } }";
        String valid = "package demo; import demo.enums.Status; class ServiceTest { "
                + "@org.junit.jupiter.api.Test void testCase() { Status status = Status.SCHEDULED; } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(invalid)))
                .hasValueSatisfying(message -> assertThat(message).contains("CONFIRMED", "demo.enums.Status", "import"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(valid))).isEmpty();
    }

    @Test
    void rejectsMissingEnumImportWhenEnumAppearsOnlyInExpression() {
        UnitTestContextDto base = contextWithServiceSource("demo.Service", "send", "public void send() {}",
                "package demo; import demo.enums.Status; class Service { public void send() {} }");
        ClassContextDto status = new ClassContextDto(2L, "demo.enums", "Status", "demo.enums.Status", "ENUM",
                "Status.java", "package demo.enums; enum Status { SCHEDULED }", List.of(), List.of());
        UnitTestContextDto ctx = new UnitTestContextDto(base.project(), base.analysis(),
                List.of(base.classes().get(0), status), base.approvedBusinessRules(), base.approvedTestPlans(),
                base.approvedTestCases(), base.existingApprovedTestCases(), base.previousGeneratedUnitTests(),
                base.existingTests());
        String generated = "package demo; class ServiceTest { "
                + "@org.junit.jupiter.api.Test void testCase() { Status.valueOf(\"SCHEDULED\"); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(generated)))
                .hasValueSatisfying(message -> assertThat(message).contains("import demo.enums.Status"));
    }

    @Test
    void acceptsExplicitImportWhenTwoEnumsShareSimpleName() {
        UnitTestContextDto base = contextWithServiceSource("demo.Service", "send", "public void send() {}",
                "package demo; import demo.alpha.Status; class Service { public void send() {} }");
        ClassContextDto alpha = new ClassContextDto(2L, "demo.alpha", "Status", "demo.alpha.Status", "ENUM",
                "AlphaStatus.java", "package demo.alpha; enum Status { READY }", List.of(), List.of());
        ClassContextDto beta = new ClassContextDto(3L, "demo.beta", "Status", "demo.beta.Status", "ENUM",
                "BetaStatus.java", "package demo.beta; enum Status { WAITING }", List.of(), List.of());
        UnitTestContextDto ctx = new UnitTestContextDto(base.project(), base.analysis(),
                List.of(base.classes().get(0), alpha, beta), base.approvedBusinessRules(), base.approvedTestPlans(),
                base.approvedTestCases(), base.existingApprovedTestCases(), base.previousGeneratedUnitTests(),
                base.existingTests());
        String generated = "package demo; import demo.alpha.Status; class ServiceTest { "
                + "@org.junit.jupiter.api.Test void testCase() { Status.valueOf(\"READY\"); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(generated))).isEmpty();
    }

    @Test
    void rejectsStaleApprovedMethodMissingFromProductionService() {
        String serviceSource = "package demo; class Service { public void cancel(Long id) {} }";
        String generatedSource = "class ServiceTest { Service service; "
                + "@org.junit.jupiter.api.Test void testCase() { service.complete(1L); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "complete", "public void complete(Long id) {}", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("approved Test Case", "complete"));
        assertThat(GeneratedUnitTestSemanticValidator.validateContext(
                contextWithServiceSource("demo.Service", "complete", "public void complete(Long id) {}", serviceSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("approved Test Case", "complete"));
    }

    @Test
    void rejectsWrongQualifiedEnumAndUnknownConstantInTestBody() {
        UnitTestContextDto base = contextWithServiceSource("demo.Service", "send", "public void send() {}",
                "package demo; import demo.enums.Status; class Service { public void send() {} }");
        ClassContextDto status = new ClassContextDto(2L, "demo.enums", "Status", "demo.enums.Status", "ENUM",
                "Status.java", "package demo.enums; enum Status { SCHEDULED, CANCELLED }", List.of(), List.of());
        UnitTestContextDto ctx = new UnitTestContextDto(base.project(), base.analysis(),
                List.of(base.classes().get(0), status), base.approvedBusinessRules(), base.approvedTestPlans(),
                base.approvedTestCases(), base.existingApprovedTestCases(), base.previousGeneratedUnitTests(),
                base.existingTests());
        String wrongFqn = "class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "demo.entity.Status status = demo.entity.Status.SCHEDULED; } }";
        String wrongConstant = "class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "demo.enums.Status status = demo.enums.Status.CONFIRMED; } }";
        String validEnum = "class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "demo.enums.Status status = demo.enums.Status.SCHEDULED; } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(wrongFqn)))
                .hasValueSatisfying(message -> assertThat(message).contains("demo.entity.Status", "demo.enums.Status"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(wrongConstant)))
                .hasValueSatisfying(message -> assertThat(message).contains("CONFIRMED", "Status"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(validEnum))).isEmpty();
    }

    @Test
    void rejectsInstantiationOfEnumDeclaredInGeneratedTest() {
        String generatedSource = "class ServiceTest { private enum Events { CREATED } "
                + "@org.junit.jupiter.api.Test void testCase() { Enum event = new Events() {}; } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("Events", "enum"));
    }

    @Test
    void rejectsMockedEnumAndJavaMailContentByteArrayCast() {
        String generatedSource = """
                import javax.mail.*;
                class ServiceTest {
                    Part part;
                    void testCase() throws Exception {
                        NotificationType type = org.mockito.Mockito.mock(NotificationType.class);
                        byte[] bytes = (byte[]) part.getContent();
                    }
                }
                """;
        UnitTestContextDto base = context("send", "public void send() {}");
        ClassContextDto enumType = new ClassContextDto(
                2L, "demo", "NotificationType", "demo.NotificationType", "ENUM",
                "src/main/java/demo/NotificationType.java", "enum NotificationType { BACKUP, REMIND }",
                List.of(), List.of());
        UnitTestContextDto withEnum = new UnitTestContextDto(
                base.project(), base.analysis(), List.of(base.classes().get(0), enumType),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());

        var error = GeneratedUnitTestSemanticValidator.validate(withEnum, response(generatedSource));

        assertThat(error).hasValueSatisfying(message -> assertThat(message)
                .contains("Do not mock enum NotificationType", "getInputStream()", "byte[]"));
    }

    @Test
    void ignoresTrailingMessageOnCustomHelperButRejectsJUnit4ImportInJunit5Project() {
        String customHelper = "class ServiceTest { @org.junit.Test void testCase() { assertEmail(actual, \"message\"); "
                + "byte[] bytes = (byte[]) domain.getContent(); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(customHelper), TestFramework.JUNIT4)).isEmpty();

        String junit4Wildcard = "import org.junit.*; class ServiceTest { @Test public @org.junit.Test void testCase() {} }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(junit4Wildcard), TestFramework.JUNIT5))
                .hasValueSatisfying(message -> assertThat(message).contains("project uses JUnit 5"));
    }

    @Test
    void rejectsJUnit5AndRemovedMockitoApisForJUnit4Project() {
        String generatedSource = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class ServiceTest {
                    Service service;
                    @Test void testCase() {
                        assertThrows(IllegalArgumentException.class, () -> service.send(null));
                        assertNotNull(service, "message");
                        service.send(org.mockito.ArgumentMatchers.isNull(String.class));
                    }
                }
                """;

        var error = GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send(String value) {}"),
                response(generatedSource), TestFramework.JUNIT4);

        assertThat(error).hasValueSatisfying(message -> assertThat(message)
                .contains("JUnit 4", "org.junit.jupiter", "isNull(Class)"));
    }

    @Test
    void rejectsRemovedMockitoVerifyZeroInteractionsApi() {
        String generatedSource = "class ServiceTest { @org.junit.Test public void testCase() { "
                + "org.mockito.Mockito.verifyZeroInteractions(new Object()); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response("testCase", generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("verifyNoInteractions", "verifyZeroInteractions"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "2,verifyZeroInteractions,true", "2,verifyNoInteractions,false", "2,verifyNoMoreInteractions,true",
            "3,verifyZeroInteractions,true", "3,verifyNoInteractions,true",
            "4,verifyZeroInteractions,false", "4,verifyNoInteractions,true",
            "5,verifyZeroInteractions,false", "5,verifyNoInteractions,true",
            "0,verifyNoMoreInteractions,true", "0,verifyNoInteractions,false", "0,verifyZeroInteractions,false"})
    void validatesInteractionApiAgainstTargetMockitoMajor(int major, String api, boolean accepted) {
        String source = "import static org.mockito.Mockito." + api + "; class ServiceTest { "
                + "@org.junit.Test public void testCase() { Object dependency = org.mockito.Mockito.mock(Object.class); " + api + "(dependency); } }";

        var result = GeneratedUnitTestSemanticValidator.validate(context("send", "public void send() {}"),
                response("testCase", source), TestFramework.JUNIT4, major == 0 ? null : new MockitoVersionInfo(major + ".1.0", "fixture"));

        if (accepted) assertThat(result).isEmpty();
        else assertThat(result).hasValueSatisfying(message -> assertThat(message).contains(api, "verifyNoMoreInteractions"));
    }

    @Test
    void rejectsUnsupportedMockitoStaticImportEvenWhenUnused() {
        String source = "import static org.mockito.Mockito.verifyNoInteractions; class ServiceTest { "
                + "@org.junit.Test public void testCase() { org.junit.Assert.assertTrue(true); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(context("send", "public void send() {}"),
                response("testCase", source), TestFramework.JUNIT4, new MockitoVersionInfo("2.15.0", "fixture")))
                .hasValueSatisfying(message -> assertThat(message).contains("verifyNoInteractions"));
    }

    @Test
    void rejectsGuessedProductionImportPackages() {
        String serviceSource = """
                package eshop.inventoryservice.service;
                import eshop.constants.RabbitMQConstants;
                import org.springframework.amqp.AmqpRejectAndDontRequeueException;
                class Service { public void send() {} }
                """;
        String generatedSource = """
                import eshop.inventoryservice.rabbitmq.constants.RabbitMQConstants;
                import org.springframework.amqp.rabbit.listener.exception.AmqpRejectAndDontRequeueException;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("eshop.inventoryservice.service.Service", "send",
                        "public void send() {}", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("RabbitMQConstants", "exact FQN"));

        String wildcardSource = """
                import eshop.inventoryservice.rabbitmq.constants.*;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("eshop.inventoryservice.service.Service", "send",
                        "public void send() {}", serviceSource), response(wildcardSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("wildcard", "exact"));

        String unknownSource = """
                import eshop.inventoryservice.rabbitmq.constants.UnknownConstant;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("eshop.inventoryservice.service.Service", "send",
                        "public void send() {}", serviceSource), response(unknownSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("Unknown project import"));
    }

    @Test
    void rejectsMixedMockitoMatchersInOneInvocation() {
        String generatedSource = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.when(repository.find("raw", org.mockito.ArgumentMatchers.any()))
                                .thenReturn(null);
                        org.mockito.Mockito.verify(repository).save(org.mockito.ArgumentMatchers.any(), "raw");
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("all arguments must use Mockito matchers"));
    }

    @Test
    void rejectsMixedMatchersInDoReturnWhenAndIgnoresApplicationMethodsWithMatcherNames() {
        String invalidSource = """
                import static org.mockito.Mockito.*;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        doReturn(null).when(repository).find(org.mockito.ArgumentMatchers.any(), "raw");
                    }
                }
                """;
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(invalidSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("all arguments must use Mockito matchers"));

        String validSource = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.verify(repository).find(criteria.contains("x"), "raw");
                    }
                }
                """;
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(validSource))).isEmpty();
    }

    @Test
    void rejectsTransactionTemplateMockThatNeverExecutesCallback() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.executeWithoutResult(status -> {}); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.doNothing().when(transactions)
                                .executeWithoutResult(org.mockito.ArgumentMatchers.any());
                        service.send();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.executeWithoutResult(status -> {}); }", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("doAnswer", "executeWithoutResult", "Consumer"));
    }

    @Test
    void acceptsUnstubbedTransactionTemplateForAValidPreTransactionBranch() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send(boolean invalid) {
                        if (invalid) return;
                        transactions.executeWithoutResult(status -> {});
                    }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() { service.send(true); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send(boolean invalid) { if (invalid) return; transactions.executeWithoutResult(status -> {}); }",
                        serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsUnstubbedTransactionWhenNestedGuardDoesNotPreventEntry() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send(boolean enabled, boolean invalid) {
                        if (enabled) {
                            if (invalid) return;
                        }
                        transactions.executeWithoutResult(status -> {});
                    }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() { service.send(false, true); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send(boolean enabled, boolean invalid) { if (enabled) { if (invalid) return; } transactions.executeWithoutResult(status -> {}); }",
                        serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("doAnswer", "callback"));
    }

    @Test
    void acceptsUnstubbedTransactionWhenElseBranchReturnsBeforeIt() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send(boolean skip) {
                        if (skip) {
                            int marker = 1;
                        } else {
                            return;
                        }
                        transactions.executeWithoutResult(status -> {});
                    }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() { service.send(false); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send(boolean skip) { if (skip) { int marker = 1; } else { return; } transactions.executeWithoutResult(status -> {}); }",
                        serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsApprovedExceptionContractThroughSutHelper() {
        String serviceSource = """
                package demo;
                class Service {
                    public void findReadyToNotify(String value) {
                        switch (value) {
                            case "READY" -> { }
                            default -> { }
                        }
                    }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @InjectMocks Service service;
                    Service sut() { return service; }
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> sut().findReadyToNotify(null));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "findReadyToNotify",
                        "public void findReadyToNotify(String value) { switch (value) { case \"READY\" -> { } default -> { } } }",
                        serviceSource),
                response(generatedSource)))
                .isEmpty();
    }

    @Test
    void acceptsUnstubbedTransactionWhenNullableGuardReturnsBeforeIt() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send(Boolean invalid) {
                        if (invalid == null || invalid) return;
                        transactions.executeWithoutResult(status -> {});
                    }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() { service.send(null); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send(Boolean invalid) { if (invalid == null || invalid) return; transactions.executeWithoutResult(status -> {}); }",
                        serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsTransactionTemplateWithoutCallbackWhenTestEntersTransaction() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.executeWithoutResult(status -> {}); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() { service.send(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.executeWithoutResult(status -> {}); }", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("doAnswer", "callback"));
    }

    @Test
    void rejectsTransactionExecuteStubThatDoesNotInvokeTransactionCallback() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.execute(status -> null); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.doReturn(null).when(transactions)
                                .execute(org.mockito.ArgumentMatchers.any());
                        service.send();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.execute(status -> null); }", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("doInTransaction", "doAnswer"));
    }

    @Test
    void rejectsTransactionCallbackPassedAsDataInsteadOfBeingInvoked() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.executeWithoutResult(status -> {}); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.doAnswer(invocation -> {
                            java.util.function.Function factory = value ->
                                    org.mockito.Mockito.mock(java.util.function.Consumer.class);
                            ((java.util.function.Consumer) factory.apply(invocation.getArgument(0))).accept(null);
                            return null;
                        }).when(transactions).executeWithoutResult(org.mockito.ArgumentMatchers.any());
                        service.send();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.executeWithoutResult(status -> {}); }", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("doAnswer", "callback"));
    }

    @Test
    void rejectsTransactionAnswerAttachedToTheWrongMethod() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.executeWithoutResult(status -> {}); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.doAnswer(invocation -> {
                            ((java.util.function.Consumer) invocation.getArgument(0)).accept(null);
                            return null;
                        }).when(transactions).execute(org.mockito.ArgumentMatchers.any());
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.executeWithoutResult(status -> {}); }", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("executeWithoutResult", "callback"));
    }

    @Test
    void rejectsTransactionStubWithoutCallbackEvenWhenAnotherTransactionMockIsCorrect() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.executeWithoutResult(status -> {}); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @Mock TransactionTemplate otherTransactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.doNothing().when(transactions)
                                .executeWithoutResult(org.mockito.ArgumentMatchers.any());
                        org.mockito.Mockito.doAnswer(invocation -> {
                            ((java.util.function.Consumer) invocation.getArgument(0)).accept(null);
                            return null;
                        }).when(otherTransactions)
                                .executeWithoutResult(org.mockito.ArgumentMatchers.any());
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.executeWithoutResult(status -> {}); }", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("doAnswer", "callback"));
    }

    @Test
    void acceptsShortAndQualifiedTransactionTemplateClassMocksWithCallback() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.executeWithoutResult(status -> {}); }
                }
                """;
        String common = """
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        %s
                        org.mockito.Mockito.doAnswer(invocation -> {
                            ((java.util.function.Consumer) invocation.getArgument(0)).accept(null);
                            return null;
                        }).when(transactions).executeWithoutResult(org.mockito.ArgumentMatchers.any());
                    }
                }
                """;
        String shortType = "org.springframework.transaction.support.TransactionTemplate transactions = "
                + "org.mockito.Mockito.mock(TransactionTemplate.class);";
        String qualifiedType = "org.springframework.transaction.support.TransactionTemplate transactions = "
                + "org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(String.format("import org.springframework.transaction.support.TransactionTemplate;\n" + common,
                        shortType)))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(String.format(common, qualifiedType)))).isEmpty();
    }

    @Test
    void acceptsTransactionExecuteCallback() {
        String serviceSource = """
                package demo;
                class Service {
                    private final TransactionTemplate transactions;
                    Service(TransactionTemplate transactions) { this.transactions = transactions; }
                    public void send() { transactions.execute(status -> null); }
                }
                """;
        String generatedSource = """
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock TransactionTemplate transactions;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.doAnswer(invocation -> {
                            ((org.springframework.transaction.support.TransactionCallback) invocation.getArgument(0))
                                    .doInTransaction(null);
                            return null;
                        }).when(transactions).execute(org.mockito.ArgumentMatchers.any());
                        service.send();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send",
                        "public void send() { transactions.execute(status -> null); }", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void reportsUnexpectedCaseIdWithoutCrashingOnProjectImportValidation() {
        String generatedSource = """
                import demo.UnknownFixture;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;
        UnitTestResponseDto unknownCaseResponse = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                999L, "ServiceTest", "testCase", "demo", "NEW_TEST", generatedSource)));

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}",
                        "package demo; class Service { public void send() {} }"),
                unknownCaseResponse))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("unexpected case IDs", "Unknown project import"));
    }

    @Test
    void rejectsAmbiguousSimpleNameImportInsteadOfUsingLastDeclaration() {
        String serviceSource = """
                package demo.service;
                import demo.alpha.Status;
                class Service { public void send() {} }
                """;
        UnitTestContextDto base = contextWithServiceSource(
                "demo.service.Service", "send", "public void send() {}", serviceSource);
        ClassContextDto alphaStatus = new ClassContextDto(
                2L, "demo.alpha", "Status", "demo.alpha.Status", "ENUM", "Status.java",
                "enum Status { READY }", List.of(), List.of());
        ClassContextDto betaStatus = new ClassContextDto(
                3L, "demo.beta", "Status", "demo.beta.Status", "ENUM", "Status.java",
                "enum Status { READY }", List.of(), List.of());
        UnitTestContextDto withDuplicateNames = new UnitTestContextDto(
                base.project(), base.analysis(), List.of(base.classes().get(0), alphaStatus, betaStatus),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
        String generatedSource = """
                import demo.beta.Status;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                withDuplicateNames, response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("ambiguous", "exact import"));
    }

    @Test
    void rejectsProjectWildcardImportWithoutMicroservicePackage() {
        String serviceSource = """
                package demo;
                class Service { public void send() {} }
                """;
        String generatedSource = """
                import demo.*;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("wildcard"));
    }

    @Test
    void rejectsNearPrefixImportWhenProductionUsesAnotherType() {
        String serviceSource = """
                package demo.service;
                import demo.alpha.TypeExtra;
                class Service { public void send() {} }
                """;
        String generatedSource = """
                import demo.alpha.Type;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.service.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("Unknown project import"));
    }

    @Test
    void acceptsSpringDataFixtureImportsFromProductionDependency() {
        String serviceSource = """
                package demo.service;
                import org.springframework.data.domain.Page;
                class Service { public void send() {} }
                """;
        String generatedSource = """
                import org.springframework.data.domain.PageImpl;
                import org.springframework.data.domain.PageRequest;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.service.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsLibraryImportWhenProductionUsesItsFqnDespiteProjectNameCollision() {
        String serviceSource = """
                package demo.service;
                class Service {
                    public org.springframework.data.domain.PageImpl send() {
                        return new org.springframework.data.domain.PageImpl(java.util.List.of());
                    }
                }
                """;
        UnitTestContextDto base = contextWithServiceSource(
                "demo.service.Service", "send", "public void send() {}", serviceSource);
        ClassContextDto projectPageImpl = new ClassContextDto(
                2L, "demo", "PageImpl", "demo.PageImpl", "CLASS", "PageImpl.java",
                "package demo; class PageImpl {}", List.of(), List.of());
        UnitTestContextDto collisionContext = new UnitTestContextDto(
                base.project(), base.analysis(), List.of(base.classes().get(0), projectPageImpl),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
        String generatedSource = """
                import org.springframework.data.domain.PageImpl;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(collisionContext, response(generatedSource))).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "org.slf4j.Logger,ch.qos.logback.classic.Logger",
            "java.util.Date,java.sql.Date",
            "java.util.List,java.awt.List"})
    void libraryImportsWithSameSimpleNameAreNotProjectDeclarations(String productionImport, String fixtureImport) {
        String serviceSource = "package demo; import " + productionImport + "; class Service { public void send() {} }";
        String source = "import " + fixtureImport + "; class ServiceTest { @org.junit.jupiter.api.Test void testCase() {} }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource), response(source))).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"catch", "expected", "assertThrowsExactly", "assertThrows"})
    void missingLookupExceptionDoesNotRequirePresentOptional(String style) {
        String production = "public Object load(String id) { return repository.findById(id).orElseThrow(() -> new IllegalArgumentException(id)); }";
        String assertion = switch (style) {
            case "catch" -> "try { service.load(\"missing\"); org.junit.Assert.fail(\"Expected exception\"); } "
                    + "catch (IllegalArgumentException exception) { org.junit.Assert.assertEquals(\"missing\", exception.getMessage()); } "
                    + "org.mockito.Mockito.verify(repository).findById(\"missing\");";
            case "expected" -> "try { service.load(\"missing\"); } finally { org.mockito.Mockito.verify(repository).findById(\"missing\"); }";
            default -> "org.junit.jupiter.api.Assertions." + style + "(IllegalArgumentException.class, () -> service.load(\"missing\")); "
                    + "org.mockito.Mockito.verify(repository).findById(\"missing\");";
        };
        boolean junit4 = style.equals("catch") || style.equals("expected");
        String annotation = style.equals("expected") ? "@org.junit.Test(expected=IllegalArgumentException.class)"
                : junit4 ? "@org.junit.Test" : "@org.junit.jupiter.api.Test";
        String source = (junit4 ? "@org.junit.runner.RunWith(org.mockito.junit.MockitoJUnitRunner.class) "
                : "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) ")
                + "class ServiceTest { @org.mockito.Mock Repository repository; @org.mockito.InjectMocks Service service; "
                + annotation + " public void testCase() { org.mockito.Mockito.when(repository.findById(\"missing\"))"
                + ".thenReturn(java.util.Optional.empty()); " + assertion + " } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(contextWithServiceSource("demo.Service", "load", production,
                "package demo; class Service { Repository repository; " + production + " }"), response(source))).isEmpty();
    }

    @Test
    void approvedSuccessAssertionIsNotReplacedBySourceDerivedRepositoryFixtures() {
        String production = "public Object load(String id) { return repository.findById(id).orElseThrow(() -> new IllegalArgumentException(id)); }";
        String source = "@org.junit.runner.RunWith(org.mockito.junit.MockitoJUnitRunner.class) "
                + "class ServiceTest { @org.mockito.Mock Repository repository; @org.mockito.InjectMocks Service service; "
                + "@org.junit.Test public void testCase() { try { Object result = service.load(\"present\"); org.junit.Assert.assertNotNull(result); } "
                + "catch (Exception exception) { org.junit.Assert.fail(\"Unexpected exception\"); } } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(contextWithServiceSource("demo.Service", "load", production,
                "package demo; class Service { Repository repository; " + production + " }"), response(source)))
                .isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void approvedFixtureSequenceIsNotRewrittenToManufactureSourceSuccess(boolean expectedAnnotation) {
        String production = "public Object load(String id) { return repository.findById(id).orElseThrow(() -> new IllegalArgumentException(id)); }";
        String verify = "org.mockito.Mockito.verify(repository).findById(\"present\"); org.mockito.Mockito.verify(repository).findById(\"missing\");";
        String body = expectedAnnotation
                ? "try { service.load(\"present\"); service.load(\"missing\"); } finally { " + verify + " }"
                : "try { service.load(\"present\"); service.load(\"missing\"); org.junit.Assert.fail(\"Expected exception\"); } "
                        + "catch (IllegalArgumentException exception) { org.junit.Assert.assertEquals(\"missing\", exception.getMessage()); } " + verify;
        String source = "@org.junit.runner.RunWith(org.mockito.junit.MockitoJUnitRunner.class) class ServiceTest { "
                + "@org.mockito.Mock Repository repository; @org.mockito.InjectMocks Service service; "
                + (expectedAnnotation ? "@org.junit.Test(expected=IllegalArgumentException.class)" : "@org.junit.Test")
                + " public void testCase() { org.mockito.Mockito.when(repository.findById(\"missing\")).thenReturn(java.util.Optional.empty()); " + body + " } }";
        var context = contextWithServiceSource("demo.Service", "load", production, "package demo; class Service { Repository repository; " + production + " }");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source)))
                .isEmpty();
        String corrected = source.replace("try { service.load(\"present\")", "org.mockito.Mockito.when(repository.findById(\"present\"))"
                + ".thenReturn(java.util.Optional.of(new Object())); try { service.load(\"present\")");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(corrected))).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TestFramework.class)
    void detectsUnboundAssertionButAllowsImportedQualifiedOrDeclaredAssertions(TestFramework framework) {
        String owner = framework == TestFramework.JUNIT4 ? "org.junit.Assert" : "org.junit.jupiter.api.Assertions";
        String annotation = framework == TestFramework.JUNIT4 ? "@org.junit.Test" : "@org.junit.jupiter.api.Test";
        String source = "class ServiceTest { " + annotation + " public void testCase() { assertTrue(true); } }";
        var context = contextWithServiceSource("demo.Service", "send", "public void send() {}", "package demo; class Service { public void send() {} }");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source), framework))
                .hasValueSatisfying(message -> assertThat(message).contains("assertTrue", "static import"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response("import static " + owner + ".assertTrue; " + source), framework)).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response("import static " + owner + ".*; " + source), framework)).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source.replace("assertTrue(true)", owner + ".assertTrue(true)")), framework)).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(source.replace("class ServiceTest {", "class ServiceTest { void assertTrue(boolean value) {}")), framework)).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context,
                response(source.replace("class ServiceTest {", "class ServiceTest extends ExistingAssertions {")), framework)).isEmpty();
    }

    @Test
    void rejectsGuessedProjectStaticImportButAcceptsExactProductionStaticImport() {
        String serviceSource = """
                package demo.service;
                import static demo.Constants.VALUE;
                class Service { public void send() {} }
                """;
        String wrongImport = """
                import static demo.Constants.OTHER;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;
        String exactImport = """
                import static demo.Constants.VALUE;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;
        UnitTestContextDto ctx = contextWithServiceSource(
                "demo.service.Service", "send", "public void send() {}", serviceSource);

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(wrongImport)))
                .hasValueSatisfying(message -> assertThat(message).contains("static import"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(exactImport))).isEmpty();
    }

    @Test
    void rejectsInjectMocksWhenConstructorDependencyIsMissing() {
        String serviceSource = """
                package demo;
                class Service {
                    Service(Repository repository, Client client) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @InjectMocks Service service;
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).hasValueSatisfying(message -> assertThat(message)
                        .contains("Client", "@Mock"));
    }

    @Test
    void acceptsInjectMocksWhenEveryConstructorDependencyIsMocked() {
        String serviceSource = """
                package demo;
                class Service {
                    Service(Repository repository, Client client) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @Mock Client client;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test void testCase() { service.send(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsImportFromSiblingMicroservice() {
        String serviceSource = """
                package com.hospital.doctor.service;
                class DoctorService { public void send() {} }
                """;
        String generatedSource = """
                import com.hospital.billing.dto.InvoiceDto;
                class DoctorServiceTest {}
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("com.hospital.doctor.service.DoctorService", "send",
                        "public void send() {}", serviceSource), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("another microservice", "billing"));
    }

    @Test
    void acceptsManualConstructionWithNonNullConfigurationValue() {
        String serviceSource = """
                package demo;
                class Service {
                    Service(Repository repository, String baseUrl) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    Service service;
                    @BeforeEach void setUp() { service = new Service(repository, "https://example.test"); }
                    @org.junit.jupiter.api.Test void testCase() { service.send(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsInjectMocksWhenLargestConstructorDependencyIsMissing() {
        String serviceSource = """
                package demo;
                class Service {
                    @Autowired Service(Repository repository) {}
                    Service(Repository repository, Client client) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @InjectMocks Service service;
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).hasValueSatisfying(message -> assertThat(message).contains("Client"));
    }

    @Test
    void acceptsRequiredArgsConstructorWithInitializedFinalAndNonNullFields() {
        String serviceSource = """
                package demo;
                @RequiredArgsConstructor
                class Service {
                    private final Repository repository;
                    private final Map cache = new HashMap<>();
                    @NonNull private Client client;
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @Mock Client client;
                    @InjectMocks Service service;
                    @org.junit.jupiter.api.Test void testCase() { service.send(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsImportAlreadyUsedByTargetService() {
        String serviceSource = """
                package com.hospital.doctor.service;
                import com.hospital.common.dto.DoctorProfileDto;
                class DoctorService { public void send() {} }
                """;
        String generatedSource = """
                import com.hospital.common.dto.DoctorProfileDto;
                class DoctorServiceTest { @org.junit.jupiter.api.Test void testCase() {} }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("com.hospital.doctor.service.DoctorService", "send",
                        "public void send() {}", serviceSource), response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsVerifyNoInteractionsAfterPositiveVerificationOfSameMock() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    void testCase() {
                        org.mockito.Mockito.verify(repository).findById(1L);
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("verifyNoInteractions(repository)", "verifyNoMoreInteractions"));
    }

    @Test
    void acceptsVerifyNoMoreInteractionsAndNoInteractionsOnDifferentMock() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    Client client;
                    @org.junit.Test void testCase() {
                        org.mockito.Mockito.when(repository.findById(1L)).thenReturn(null);
                        org.mockito.Mockito.verify(repository).findById(1L);
                        org.mockito.Mockito.verifyNoMoreInteractions(repository);
                        org.mockito.Mockito.verifyNoInteractions(client);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource), null, new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsAfterStubbingAndZeroCountVerification() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    @org.junit.Test void testCase() {
                        org.mockito.Mockito.when(repository.findById(1L)).thenReturn(null);
                        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).deleteById(1L);
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource), null, new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsOnAlternativeBranch() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    @org.junit.Test void testCase() {
                        boolean shouldSave = false;
                        if (shouldSave) {
                            org.mockito.Mockito.verify(repository).save();
                        } else {
                            org.mockito.Mockito.verifyNoInteractions(repository);
                        }
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource), null, new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsAfterClearingMockHistory() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    @org.junit.Test void testCase() {
                        org.mockito.Mockito.verify(repository).save();
                        org.mockito.Mockito.clearInvocations(repository);
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource), null, new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsWhenAnonymousCallbackIsNotInvoked() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    @org.junit.Test void testCase() {
                        Runnable callback = new Runnable() {
                            @Override public void run() {
                                org.mockito.Mockito.verify(repository).save();
                            }
                        };
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource), null, new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsOnDifferentHolderField() {
        String generatedSource = """
                class ServiceTest {
                    Holder first;
                    Holder second;
                    @org.junit.Test void testCase() {
                        org.mockito.Mockito.verify(first.repository).save();
                        org.mockito.Mockito.verifyNoInteractions(second.repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource), null, new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void acceptsApprovedIllegalArgumentContractWhenNullReachesSwitchSelector() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) {
                        case BACKUP: return "backup";
                        default: throw new IllegalArgumentException();
                    }
                }
                """;
        String generatedSource = """
                class RecipientServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;

        var error = GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource), response("rejectsNull", generatedSource));

        assertThat(error).isEmpty();
    }

    @Test
    void acceptsNullPointerExpectationForNullSwitchSelector() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) {
                        case BACKUP: return "backup";
                        default: throw new IllegalArgumentException();
                    }
                }
                """;
        String generatedSource = """
                class RecipientServiceTest {
                    @org.junit.Test(expected = NullPointerException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource), response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void acceptsApprovedPlainContentExpectationDespiteMultipartImplementation() {
        String generatedSource = """
                import javax.mail.internet.MimeMessage;
                class EmailServiceTest {
                    @org.junit.Test public void sends() throws Exception {
                        String subject = "Nhắc nhở tài khoản";
                        MimeMessage message = capturedMessage();
                        org.junit.Assert.assertTrue(message.getContent() instanceof String);
                    }
                    MimeMessage capturedMessage() { return null; }
                }
                """;

        var error = GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() { new MimeMessageHelper(message, true); }"),
                response("sends", generatedSource));

        assertThat(error).isEmpty();
    }

    @Test
    void acceptsUnicodeWhenThereIsNoProvenMultipartMistake() {
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test public void sends() {
                        org.junit.Assert.assertEquals("Nhắc nhở", service.send("Nhắc nhở"));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public String send(String value) { return value; }"),
                response("sends", generatedSource))).isEmpty();
    }

    @Test
    void acceptsPlainContentAssertionWhenHelperIsNotMultipart() {
        String generatedSource = """
                import javax.mail.internet.MimeMessage;
                class ServiceTest {
                    @org.junit.Test public void sends() throws Exception {
                        MimeMessage message = capturedMessage();
                        org.junit.Assert.assertTrue(message.getContent() instanceof String);
                    }
                    MimeMessage capturedMessage() { return null; }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() { new MimeMessageHelper(message, false); }"),
                response("sends", generatedSource))).isEmpty();
    }

    @Test
    void acceptsPlainContentAssertionWhenProductionHasMixedMultipartModes() {
        String generatedSource = """
                import javax.mail.internet.MimeMessage;
                class ServiceTest {
                    @org.junit.Test public void sends() throws Exception {
                        MimeMessage message = capturedMessage();
                        org.junit.Assert.assertTrue(message.getContent() instanceof String);
                    }
                    MimeMessage capturedMessage() { return null; }
                }
                """;
        String productionSource = """
                public void send(boolean attachment) {
                    if (attachment) new MimeMessageHelper(message, true);
                    else new MimeMessageHelper(message, false);
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", productionSource), response("sends", generatedSource))).isEmpty();
    }

    @Test
    void doesNotCombineDifferentAssertThrowsBlocks() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    Other other;
                    @org.junit.Test public void rejectsNull() {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                () -> other.validate());
                        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                                () -> service.findReadyToNotify(null));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource), response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void acceptsApprovedIllegalArgumentContractForSwitchExpression() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    return switch (type) {
                        case BACKUP -> "backup";
                        default -> throw new IllegalArgumentException();
                    };
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test public void rejectsNull() {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                () -> service.findReadyToNotify(null));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void skipsCorrectionWhenNullGuardReturnsBeforeSwitch() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    if (type == null) return "fallback";
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void rejectsStubbingOrVerifyingOnInjectMocks() {
        String stubbedSut = """
                class ServiceTest {
                    @org.mockito.InjectMocks
                    Service service;
                    @org.junit.Test public void testCase() {
                        org.mockito.Mockito.when(service.internalMethod()).thenReturn("mocked");
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(stubbedSut)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("Do not stub methods on the @InjectMocks instance 'service'"));
    }

    @Test
    void rejectsDirectInvocationOfPrivateMethod() {
        MethodContextDto publicMethod = new MethodContextDto(
                10L, "demo.Service", "publicMethod", "void", List.of(), List.of(),
                "PUBLIC", "public void publicMethod() {}", 1, 5, List.of(), List.of(), List.of());
        MethodContextDto privateMethod = new MethodContextDto(
                11L, "demo.Service", "privateHelper", "void", List.of(), List.of(),
                "PRIVATE", "private void privateHelper() {}", 6, 10, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", null, List.of(), List.of(publicMethod, privateMethod));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(
                20L, 11L, "BR-001", "rule", null, "AI", "APPROVED", false, "STMT-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(
                30L, 20L, List.of(20L), "TP-001", "plan", "plan", "NORMAL", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(
                40L, 30L, "TC-001", "NORMAL", "case", "setup", java.util.Map.of(),
                "result", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        UnitTestContextDto ctx = new UnitTestContextDto(null, null, List.of(javaClass), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());

        String callingPrivate = """
                class ServiceTest {
                    @org.mockito.InjectMocks
                    Service service;
                    @org.junit.Test public void testCase() {
                        service.privateHelper();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(callingPrivate)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("Method 'privateHelper' is private in Service"));
    }

    @Test
    void rejectsAnonymousSubclassOverrideOfPrivateMethod() {
        MethodContextDto publicMethod = new MethodContextDto(
                10L, "demo.Service", "publicMethod", "void", List.of(), List.of(),
                "PUBLIC", "public void publicMethod() {}", 1, 5, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", """
                        class Service {
                            public void publicMethod() {}
                            private Object privateHelper() { return null; }
                        }
                        """, List.of(), List.of(publicMethod));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(
                20L, 10L, "BR-001", "rule", null, "AI", "APPROVED", false, "STMT-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(
                30L, 20L, List.of(20L), "TP-001", "plan", "plan", "NORMAL", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(
                40L, 30L, "TC-001", "NORMAL", "case", "setup", java.util.Map.of(),
                "result", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        UnitTestContextDto ctx = new UnitTestContextDto(null, null, List.of(javaClass), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());
        String anonymousOverride = """
                import demo.Service;
                class ServiceTest {
                    void testCase() {
                        Service service = new Service() {
                            @Override
                            Object privateHelper() { return null; }
                        };
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(anonymousOverride)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("anonymous subclass", "privateHelper", "private"));
    }

    @Test
    void acceptsAnonymousSubclassOfNonSutCollaborator() {
        UnitTestContextDto base = context("publicMethod", "public void publicMethod() {}");
        MethodContextDto privateMethod = new MethodContextDto(
                11L, "demo.Collaborator", "privateHelper", "void", List.of(), List.of(),
                "PRIVATE", "private void privateHelper() {}", 1, 5, List.of(), List.of(), List.of());
        ClassContextDto collaborator = new ClassContextDto(
                2L, "demo", "Collaborator", "demo.Collaborator", "OTHER",
                "src/main/java/demo/Collaborator.java", null, List.of(), List.of(privateMethod));
        UnitTestContextDto contextWithCollaborator = new UnitTestContextDto(
                base.project(), base.analysis(), List.of(base.classes().get(0), collaborator),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
        String collaboratorOverride = """
                class ServiceTest {
                    @org.junit.Test void testCase() {
                        Collaborator collaborator = new Collaborator() {
                            @Override
                            void privateHelper() {}
                        };
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(contextWithCollaborator, response(collaboratorOverride)))
                .isEmpty();
    }

    @Test
    void acceptsAnonymousSubclassOfSameNamedTypeFromAnotherPackage() {
        String collaboratorOverride = """
                class ServiceTest {
                    @org.junit.Test void testCase() {
                        other.Service collaborator = new other.Service() {
                            @Override
                            void privateHelper() {}
                        };
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("publicMethod", "public void publicMethod() {}"), response(collaboratorOverride)))
                .isEmpty();
    }

    @Test
    void rejectsMockWithoutRunnerOrExtension() {
        String testWithoutRunner = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                import org.mockito.InjectMocks;
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @InjectMocks
                    private Service service;
                    @Test
                    void testMethod() {}
                }
                """;
        var ctx = context("test", "public void test() {}");
        var error = GeneratedUnitTestSemanticValidator.validate(ctx, response(testWithoutRunner), TestFramework.JUNIT5);
        assertThat(error).hasValueSatisfying(msg -> assertThat(msg)
                .contains("@ExtendWith(MockitoExtension.class)"));

        var errorJunit4 = GeneratedUnitTestSemanticValidator.validate(ctx, response(testWithoutRunner), TestFramework.JUNIT4);
        assertThat(errorJunit4).hasValueSatisfying(msg -> assertThat(msg)
                .contains("@RunWith(MockitoJUnitRunner.class)"));
    }

    @Test
    void acceptsMockWithExtendWithOrOpenMocks() {
        String testWithExtendWith = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @Test
                    void testMethod() {}
                }
                """;
        var ctx = context("test", "public void test() {}");
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response("testMethod", testWithExtendWith), TestFramework.JUNIT5)).isEmpty();

        String testWithOpenMocks = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @BeforeEach
                    void setup() { MockitoAnnotations.openMocks(this); }
                    @Test
                    void testMethod() {}
                }
                """;
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response("testMethod", testWithOpenMocks), TestFramework.JUNIT5)).isEmpty();
    }

    @Test
    void rejectsGeneratedTestMarkedAsSkipped() {
        String skippedTest = """
                class ServiceTest {
                    // SKIPPED: missing entity details
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(skippedTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void rejectsDisabledGeneratedTest() {
        String disabledTest = """
                import org.junit.jupiter.api.Disabled;
                class ServiceTest {
                    @Disabled
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(disabledTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void rejectsSkippedCommentWithoutWhitespaceOrUppercase() {
        String skippedTest = """
                class ServiceTest {
                    //skipped because context is incomplete
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(skippedTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void rejectsCommentedOutTestAnnotation() {
        String commentedOutTest = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                    // @Test
                    // void omittedTestCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(commentedOutTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void acceptsDisabledMarkerInsideStringFixture() {
        String validTest = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        String fixture = "@Disabled";
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(validTest))).isEmpty();
    }

    @Test
    void rejectsLenientStubbing() {
        String lenientTest = """
                class ServiceTest {
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.lenient().when(repository.findById(1L)).thenReturn(null);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsGeneratedMethodWithoutTestAnnotation() {
        String missingAnnotation = """
                class ServiceTest {
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(missingAnnotation)))
                .hasValueSatisfying(message -> assertThat(message).contains("must declare @Test"));
    }

    @Test
    void acceptsTraceCommentThatMentionsSkippedRecords() {
        String validTest = """
                class ServiceTest {
                    // GreyTest trace: process | skipped records | BR-001 -> TP-001 -> TC-001
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(validTest))).isEmpty();
    }

    @Test
    void rejectsMockAnnotationThatDisablesStrictStubbing() {
        String lenientMock = """
                class ServiceTest {
                    @org.mockito.Mock(lenient = true)
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientMock)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsMockitoSettingsThatDisableStrictStubbing() {
        String lenientSettings = """
                @org.mockito.junit.jupiter.MockitoSettings(
                    strictness = org.mockito.quality.Strictness.LENIENT)
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSettings)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsMalformedGeneratedTestSource() {
        String malformedSource = "class ServiceTest { void testCase( }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(malformedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("valid Java test source"));
    }

    @Test
    void rejectsMockStrictnessThatDisablesStrictStubbing() {
        String lenientMock = """
                class ServiceTest {
                    @org.mockito.Mock(strictness = org.mockito.quality.Strictness.LENIENT)
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientMock)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsMockitoSessionWithLenientStrictness() {
        String lenientSession = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.mockitoSession()
                            .strictness(org.mockito.quality.Strictness.LENIENT)
                            .startMocking();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSession)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsTestFactoryInsteadOfTestMethod() {
        String testFactory = """
                class ServiceTest {
                    @org.junit.jupiter.api.TestFactory
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(testFactory)))
                .hasValueSatisfying(message -> assertThat(message).contains("must declare @Test"));
    }

    @Test
    void rejectsLenientMockSettings() {
        String lenientSettings = """
                import static org.mockito.Mockito.withSettings;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.mock(Repository.class, withSettings().lenient());
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSettings)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsStaticallyImportedLenientStrictness() {
        String lenientMock = """
                import static org.mockito.quality.Strictness.LENIENT;
                class ServiceTest {
                    @org.mockito.Mock(strictness = LENIENT)
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientMock)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsWarnMockitoSettings() {
        String warnSettings = """
                @org.mockito.junit.jupiter.MockitoSettings(
                    strictness = org.mockito.quality.Strictness.WARN)
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(warnSettings)))
                .hasValueSatisfying(message -> assertThat(message).contains("strict stubbing"));
    }

    @Test
    void rejectsJUnit4SilentMockitoRunner() {
        String silentRunner = """
                import org.junit.runner.RunWith;
                import org.mockito.Mock;
                import org.mockito.junit.MockitoJUnitRunner;
                @RunWith(MockitoJUnitRunner.Silent.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @org.junit.Test void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(silentRunner), TestFramework.JUNIT4))
                .hasValueSatisfying(message -> assertThat(message).contains("Silent", "strict stubbing"));
    }

    @Test
    void rejectsStaticallyImportedLenientMockitoSession() {
        String lenientSession = """
                import static org.mockito.quality.Strictness.LENIENT;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.mockitoSession().strictness(LENIENT).startMocking();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSession)))
                .hasValueSatisfying(message -> assertThat(message).contains("strict stubbing"));
    }

    @Test
    void rejectsMissingAndDuplicateApprovedTestCases() {
        UnitTestContextDto base = context("testCase", "public void testCase() {}");
        TestCaseContextItemDto secondCase = new TestCaseContextItemDto(
                41L, 30L, "TC-002", "EXCEPTION", "case 2", "setup", java.util.Map.of(),
                "throws", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        UnitTestContextDto twoCases = new UnitTestContextDto(
                base.project(), base.analysis(), base.classes(), base.approvedBusinessRules(),
                base.approvedTestPlans(), List.of(base.approvedTestCases().get(0), secondCase),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                twoCases, response("testCase", "class ServiceTest { @org.junit.jupiter.api.Test void testCase() {} }")))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("exactly one generated test", "missing case IDs", "41"));

        UnitTestResponseDto duplicate = new UnitTestResponseDto(List.of(
                new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", "demo", "NEW_TEST",
                        "class ServiceTest { @org.junit.jupiter.api.Test void testCase() {} }"),
                new GeneratedUnitTestDto(40L, "ServiceTest", "testCase2", "demo", "NEW_TEST",
                        "class ServiceTest { @org.junit.jupiter.api.Test void testCase2() {} }")));
        assertThat(GeneratedUnitTestSemanticValidator.validate(base, duplicate))
                .hasValueSatisfying(message -> assertThat(message).contains("duplicate case IDs", "40"));

        UnitTestResponseDto nullItem = new UnitTestResponseDto(java.util.Arrays.asList(
                new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", "demo", "NEW_TEST",
                        "class ServiceTest { @org.junit.jupiter.api.Test void testCase() {} }"),
                null));
        assertThat(GeneratedUnitTestSemanticValidator.validate(twoCases, nullItem))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("null items", "missing case IDs"));
    }

    @Test
    void rejectsJavaAssertionsAndUnsupportedIntegrationTestInfrastructure() {
        String generatedSource = """
                import org.springframework.boot.test.context.SpringBootTest;
                import org.mockito.MockedStatic;
                @SpringBootTest
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        assert true;
                        org.mockito.Mockito.mockStatic(System.class);
                        org.mockito.Mockito.mock(org.springframework.data.domain.Page.class);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("Java assert", "@SpringBootTest", "static mocking", "Page/Pageable"));
    }

    @Test
    void rejectsPageMocksWithExtraArgumentsAndMockFields() {
        String generatedSource = """
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                import org.springframework.data.domain.Page;
                class ServiceTest {
                    @Mock Page<Object> page;
                    @Test
                    void testCase() {
                        org.mockito.Mockito.mock(Page.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("Page/Pageable"));
    }

    @Test
    void rejectsPageableMockDeclaredOnTestMethodParameter() {
        String generatedSource = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase(@org.mockito.Mock org.springframework.data.domain.Pageable pageable) {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("Page/Pageable"));
    }

    @Test
    void ignoresForbiddenInfrastructureNamesInsideCommentsAndStringFixtures() {
        String generatedSource = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        // @SpringBootTest and mockStatic( are forbidden in generated code, not comments.
                        String fixture = "@SpringBootTest mockStatic(";
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource))).isEmpty();
    }

    @Test
    void doesNotRejectApplicationMethodsThatOnlyShareMockitoNames() {
        String generatedSource = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        service.mockStatic();
                        String fixture = "MockedStatic";
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(generatedSource))).isEmpty();
    }

    private UnitTestContextDto context(String methodName, String methodSource) {
        MethodContextDto method = new MethodContextDto(
                10L, "demo.Service", methodName, "Object", List.of(), List.of(),
                "public", methodSource, 1, 10, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", null, List.of(), List.of(method));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(
                20L, 10L, "BR-001", "rule", null, "AI", "APPROVED", false, "SWITCH-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(
                30L, 20L, List.of(20L), "TP-001", "plan", "plan", "EXCEPTION", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(
                40L, 30L, "TC-001", "EXCEPTION", "case", "setup", java.util.Map.of(),
                "throws", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(null, null, List.of(javaClass), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2.15.0,verifyZeroInteractions", "5.11.0,verifyNoInteractions", "5.11.0,verifyNoMoreInteractions"})
    void approvedZeroInteractionsCanExposeAFaultyLookupBeforeValidation(String version, String api) {
        String source = exceptionFlowTest("org.mockito.Mockito." + api + "(repository);", false);
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source), TestFramework.JUNIT5,
                new MockitoVersionInfo(version, "fixture")))
                .isEmpty();
    }

    @Test
    void doesNotInventAdditionalPositiveVerificationFromSource() {
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false),
                response(exceptionFlowTest("org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());", false)),
                TestFramework.JUNIT5, new MockitoVersionInfo("5.11.0", "fixture")))
                .isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void acceptsLookupVerificationAndOnlyUnreachedDependenciesHaveZeroInteractions(boolean junit4) {
        String verification = "org.mockito.Mockito.verify(repository).findByName(\"alice\"); "
                + "org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any()); "
                + "org.mockito.Mockito." + (junit4 ? "verifyZeroInteractions" : "verifyNoInteractions") + "(notifier);";
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(exceptionFlowTest(verification, junit4)),
                junit4 ? TestFramework.JUNIT4 : TestFramework.JUNIT5, new MockitoVersionInfo(junit4 ? "2.15.0" : "5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void preservesApprovedLookupArgumentWhenSourceUsesAnother() {
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false),
                response(exceptionFlowTest("org.mockito.Mockito.verify(repository).findByName(\"bob\");", false))))
                .isEmpty();
    }

    @Test
    void preservesApprovedZeroCountVerificationDespiteASourceLookup() {
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false),
                response(exceptionFlowTest("org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findByName(\"alice\");", false))))
                .isEmpty();
    }

    @Test
    void verificationInsideThrowingLambdaAfterActIsNotReachable() {
        String source = exceptionFlowTest("", false).replace("() -> service.check(\"alice\")",
                "() -> { service.check(\"alice\"); org.mockito.Mockito.verify(repository).findByName(\"alice\"); }");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source)))
                .hasValueSatisfying(message -> assertThat(message).contains("findByName", "after"));
    }

    @Test
    void junit4ExpectedAnnotationCannotVerifyLookupAfterThrownException() {
        String source = exceptionFlowTest("", true)
                .replace("@org.junit.Test public void testCase()", "@org.junit.Test(expected=IllegalArgumentException.class) public void testCase()")
                .replace("try { service.check(\"alice\"); org.junit.Assert.fail(\"Expected exception\"); } catch (IllegalArgumentException expected) {}",
                        "service.check(\"alice\"); org.mockito.Mockito.verify(repository).findByName(\"alice\");");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source)))
                .hasValueSatisfying(message -> assertThat(message).contains("after", "findByName"));
    }

    @Test
    void acceptsZeroInteractionsWhenValidationThrowsBeforeLookup() {
        String source = exceptionFlowTest("org.mockito.Mockito.verifyNoInteractions(repository, notifier);", false)
                .replace("service.check(\"alice\")", "service.check(null)")
                .replace("org.mockito.Mockito.when(repository.findByName(\"alice\")).thenReturn(null); ", "");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(true), response(source), TestFramework.JUNIT5,
                new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void preservesApprovedInteractionContractWhenAnEarlierGuardPasses() {
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(true),
                response(exceptionFlowTest("", false))))
                .isEmpty();
    }

    @Test
    void approvedExceptionDoesNotRequireAdditionalLookupVerification() {
        UnitTestContextDto context = contextWithServiceSource("demo.Service", "check",
                "public Object check(String name) { return repository.findById(name).orElseThrow(() -> new IllegalArgumentException()); }",
                "package demo; class Service { Repository repository; Object check(String name) { return repository.findById(name).orElseThrow(() -> new IllegalArgumentException()); } }");
        String source = exceptionFlowTest("", false).replace("findByName", "findById").replace(".thenReturn(null)", ".thenReturn(java.util.Optional.empty())");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source)))
                .isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "for (int i = 0; i < 0; i++) { repository.findByName(name); } throw new IllegalArgumentException();|IllegalArgumentException",
            "Object value = false ? repository.findByName(name) : null; throw new IllegalArgumentException();|IllegalArgumentException",
            "int[] values = null; int value = values[0]; return repository.findByName(name);|NullPointerException",
            "int divisor = 0; int value = 1 / divisor; return repository.findByName(name);|ArithmeticException",
            "if (name != \"disabled\") throw new IllegalArgumentException(); return repository.findByName(name);|IllegalArgumentException",
            "{ if (name != null) throw new IllegalArgumentException(); repository.findByName(name); } return null;|IllegalArgumentException",
            "switch (name) { case \"alice\": throw new IllegalArgumentException(); default: repository.findByName(name); } return null;|IllegalArgumentException"})
    void doesNotInventLookupBeforeExceptionInUnprovenControlFlow(String body, String exception) {
        String method = "public Object check(String name) { " + body + " }";
        var context = contextWithServiceSource("demo.Service", "check", method,
                "package demo; class Service { Repository repository; " + method + " }");
        String source = exceptionFlowTest("org.mockito.Mockito.verifyNoInteractions(repository, notifier);", false)
                .replace("org.mockito.Mockito.when(repository.findByName(\"alice\")).thenReturn(null); ", "")
                .replace("assertThrows(IllegalArgumentException.class", "assertThrows(" + exception + ".class");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source), TestFramework.JUNIT5,
                new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void approvedVerificationExpressionIsNotReplacedBySourceArguments() {
        String source = exceptionFlowTest("org.mockito.Mockito.verify(repository).findByName(\"bob\");", false)
                .replace("org.junit.jupiter.api.Assertions.assertThrows", "String name = new String(\"alice\"); org.junit.jupiter.api.Assertions.assertThrows")
                .replace("service.check(\"alice\")", "service.check(name)");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source)))
                .isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false),
                response(source.replace("findByName(\"bob\")", "findByName(name)")))).isEmpty();
    }

    @Test
    void verificationInJUnit4TryAfterActIsUnreachable() {
        String source = exceptionFlowTest("", true).replace("service.check(\"alice\"); org.junit.Assert.fail",
                "service.check(\"alice\"); org.mockito.Mockito.verify(repository).findByName(\"alice\"); org.junit.Assert.fail");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source)))
                .hasValueSatisfying(message -> assertThat(message).contains("findByName", "after"));
        String valid = exceptionFlowTest("", true).replace("catch (IllegalArgumentException expected) {}",
                "catch (IllegalArgumentException expected) { org.mockito.Mockito.verify(repository).findByName(\"alice\"); }");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(valid))).isEmpty();
    }

    @Test
    void expectedAnnotationMayVerifyLookupInFinally() {
        String source = exceptionFlowTest("", true)
                .replace("@org.junit.Test public void testCase()", "@org.junit.Test(expected=IllegalArgumentException.class) public void testCase()")
                .replace("try { service.check(\"alice\"); org.junit.Assert.fail(\"Expected exception\"); } catch (IllegalArgumentException expected) {}",
                        "try { service.check(\"alice\"); } finally { org.mockito.Mockito.verify(repository).findByName(\"alice\"); }");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source))).isEmpty();
    }

    @Test
    void allowsZeroInteractionsBeforeActThenPositiveLookupVerification() {
        String source = exceptionFlowTest("org.mockito.Mockito.verify(repository).findByName(\"alice\");", false)
                .replace("org.junit.jupiter.api.Assertions.assertThrows",
                        "org.mockito.Mockito.verifyNoInteractions(repository); org.junit.jupiter.api.Assertions.assertThrows");
        assertThat(GeneratedUnitTestSemanticValidator.validate(exceptionFlowContext(false), response(source), TestFramework.JUNIT5,
                new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
    }

    @Test
    void preservesApprovedNoLookupContractForNullableAndNonNullInputs() {
        String method = "public Object check(Long id) { return repository.findById(id); }";
        var context = contextWithServiceSource("demo.Service", "check", method,
                "package demo; class Service { Repository repository; " + method + " } interface Repository { Object findById(long id); }");
        String source = exceptionFlowTest("org.mockito.Mockito.verifyNoInteractions(repository, notifier);", false)
                .replace("org.mockito.Mockito.when(repository.findByName(\"alice\")).thenReturn(null); ", "")
                .replace("service.check(\"alice\")", "service.check(null)")
                .replace("assertThrows(IllegalArgumentException.class", "assertThrows(NullPointerException.class");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source), TestFramework.JUNIT5,
                new MockitoVersionInfo("5.11.0", "fixture"))).isEmpty();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response(source.replace("service.check(null)", "service.check(1L)")),
                TestFramework.JUNIT5, new MockitoVersionInfo("5.11.0", "fixture")))
                .isEmpty();
    }

    @Test
    void accountSaveChangesKeepsApprovedInteractionContractsDespiteSourceLookup() {
        // Luồng lấy từ AccountServiceImpl.saveChanges trong snapshot source người dùng cung cấp.
        String production = "public void saveChanges(String name, Account update) { "
                + "Account account = repository.findByName(name); Assert.notNull(account, \"can't find account with name \" + name); "
                + "account.setIncomes(update.getIncomes()); account.setExpenses(update.getExpenses()); account.setSaving(update.getSaving()); "
                + "account.setNote(update.getNote()); account.setLastSeen(new Date()); repository.save(account); "
                + "log.debug(\"account {} changes has been saved\", name); statisticsClient.updateStatistics(name, account); }";
        var context = contextWithServiceSource("com.piggymetrics.account.service.AccountServiceImpl", "saveChanges", production,
                "package com.piggymetrics.account.service; import org.springframework.util.Assert; import java.util.Date; "
                        + "import com.piggymetrics.account.domain.Account; import com.piggymetrics.account.repository.AccountRepository; "
                        + "import com.piggymetrics.account.client.StatisticsServiceClient; class AccountServiceImpl { "
                        + "AccountRepository repository; StatisticsServiceClient statisticsClient; " + production + " }");
        String source = """
                package com.piggymetrics.account.service;
                import com.piggymetrics.account.domain.Account;
                import com.piggymetrics.account.repository.AccountRepository;
                import com.piggymetrics.account.client.StatisticsServiceClient;
                @org.junit.runner.RunWith(org.mockito.junit.MockitoJUnitRunner.class)
                public class AccountServiceImplTest {
                    @org.mockito.Mock AccountRepository repository;
                    @org.mockito.Mock StatisticsServiceClient statisticsClient;
                    @org.mockito.InjectMocks AccountServiceImpl service;
                    @org.junit.Test public void testCase() {
                        org.mockito.Mockito.when(repository.findByName("missing")).thenReturn(null);
                        try { service.saveChanges("missing", null); org.junit.Assert.fail("Expected exception"); }
                        catch (IllegalArgumentException exception) {
                            org.junit.Assert.assertEquals("can't find account with name missing", exception.getMessage());
                        }
                        org.mockito.Mockito.verify(repository).findByName("missing");
                        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(Account.class));
                        org.mockito.Mockito.verifyZeroInteractions(statisticsClient);
                    }
                }
                """;
        var validResponse = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "AccountServiceImplTest", "testCase", "com.piggymetrics.account.service", "NEW_TEST", source)));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, validResponse, TestFramework.JUNIT4,
                new MockitoVersionInfo("2.15.0", "fixture"))).isEmpty();
        String invalid = source.replace("org.mockito.Mockito.verify(repository).findByName(\"missing\");",
                "org.mockito.Mockito.verifyZeroInteractions(repository);");
        var invalidResponse = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "AccountServiceImplTest", "testCase", "com.piggymetrics.account.service", "NEW_TEST", invalid)));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, invalidResponse, TestFramework.JUNIT4,
                new MockitoVersionInfo("2.15.0", "fixture")))
                .isEmpty();
    }

    private UnitTestContextDto exceptionFlowContext(boolean earlyGuard) {
        String method = "public Object check(String name) { " + (earlyGuard ? "if (name == null) throw new IllegalArgumentException(); " : "")
                + "Object value = repository.findByName(name); if (value == null) throw new IllegalArgumentException(); "
                + "repository.save(value); notifier.send(value); return value; }";
        return contextWithServiceSource("demo.Service", "check", method,
                "package demo; class Service { Repository repository; Client notifier; " + method + " }");
    }

    private String exceptionFlowTest(String verification, boolean junit4) {
        return (junit4 ? "@org.junit.runner.RunWith(org.mockito.junit.MockitoJUnitRunner.class) "
                : "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) ")
                + "class ServiceTest { @org.mockito.Mock Repository repository; @org.mockito.Mock Client notifier; "
                + "@org.mockito.InjectMocks Service service; "
                + (junit4 ? "@org.junit.Test public void testCase() { " : "@org.junit.jupiter.api.Test void testCase() { ")
                + "org.mockito.Mockito.when(repository.findByName(\"alice\")).thenReturn(null); "
                + (junit4 ? "try { service.check(\"alice\"); org.junit.Assert.fail(\"Expected exception\"); } catch (IllegalArgumentException expected) {} "
                        : "org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.check(\"alice\")); ")
                + verification + " } }";
    }

    private UnitTestContextDto contextWithServiceSource(
            String qualifiedName, String methodName, String methodSource, String serviceSource) {
        UnitTestContextDto base = context(methodName, methodSource);
        ClassContextDto previous = base.classes().get(0);
        int lastDot = qualifiedName.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
        String className = lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
        MethodContextDto method = new MethodContextDto(
                previous.methods().get(0).id(), qualifiedName, methodName, previous.methods().get(0).returnType(),
                previous.methods().get(0).parameters(), previous.methods().get(0).throwsList(),
                previous.methods().get(0).visibility(), methodSource, previous.methods().get(0).lineStart(),
                previous.methods().get(0).lineEnd(), previous.methods().get(0).annotations(),
                previous.methods().get(0).endpoints(), previous.methods().get(0).branches());
        ClassContextDto service = new ClassContextDto(
                previous.id(), packageName, className, qualifiedName, previous.classType(), previous.filePath(),
                serviceSource, previous.annotations(), List.of(method));
        return new UnitTestContextDto(
                base.project(), base.analysis(), List.of(service), base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), base.existingApprovedTestCases(),
                base.previousGeneratedUnitTests(), base.existingTests());
    }

    private UnitTestResponseDto response(String source) {
        return response("testCase", source);
    }

    private UnitTestResponseDto response(String methodName, String source) {
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "ServiceTest", methodName, "demo", "NEW_TEST", source)));
    }
}
