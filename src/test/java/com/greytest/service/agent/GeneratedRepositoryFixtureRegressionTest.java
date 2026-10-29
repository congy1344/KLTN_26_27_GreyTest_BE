package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.greytest.dto.SourceBranchDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

/** Kiểm tra setup theo hợp đồng mock đã duyệt, không thay assertion bằng kết quả hiện tại của source. */
class GeneratedRepositoryFixtureRegressionTest {
    @Test
    void rejectsAnIncompleteSeparateSaveReturnWhenApprovedMockRequiresRetainingFields() {
        assertThat(validate(context(false, "Return the saved entity retaining mapped fields", "Valid request"),
                source("", "thenReturn(saved)", "")))
                .hasValueSatisfying(message -> assertThat(message).contains("save-return fixture", "saved", "label"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"thenAnswer(invocation -> invocation.getArgument(0))", "thenReturn(current)"})
    void acceptsTheActualSubmittedEntityAsTheSaveReturn(String saveReturn) {
        assertThat(validate(context(false, "Return the saved entity retaining mapped fields", "Valid request"),
                source("", saveReturn, ""))).isEmpty();
    }

    @Test
    void acceptsAFullyPopulatedSeparateReturnedEntity() {
        assertThat(validate(context(false, "Return the saved entity retaining mapped fields", "Valid request"),
                source("", "thenReturn(saved)", "saved.setLabel(\"An\"); saved.setEnabled(true);"))).isEmpty();
    }

    @Test
    void preservesAnApprovedEmptyReturnEvenWhenTheBusinessAssertionWouldFailOnSource() {
        assertThat(validate(context(false, "Return a separate empty entity; production must restore the approved label", "Valid request"),
                source("", "thenReturn(saved)", ""))).isEmpty();
    }

    @Test
    void doesNotGuessFieldsPopulatedByACustomHelper() {
        String test = source("", "thenReturn(saved)", "populate(saved);")
                .replace("class ServiceTest {", "class ServiceTest { void populate(Member value) { value.setLabel(\"An\"); }");
        assertThat(validate(context(false, "Return the saved entity retaining mapped fields", "Valid request"), test)).isEmpty();
    }

    @Test
    void doesNotGuessFieldsPopulatedThroughAnAlias() {
        assertThat(validate(context(false, "Return the saved entity retaining mapped fields", "Valid request"),
                source("", "thenReturn(saved)", "Member alias = saved; alias.setLabel(\"An\");"))).isEmpty();
    }

    @Test
    void rejectsAMappingFixtureWhoseInitialValueAlreadyEqualsTheApprovedInput() {
        assertThat(validate(context(true, "Return the submitted entity", "Existing member has ID 1"),
                source("", "thenAnswer(invocation -> invocation.getArgument(0))", "")))
                .hasValueSatisfying(message -> assertThat(message).contains("mapping fixture", "enabled", "initial"));
    }

    @Test
    void checksInitialStateWhenSaveIsStubbedWithTheExactExistingEntity() {
        String code = source("", "thenReturn(current)", "").replace("store.save(any(Member.class))", "store.save(current)");
        assertThat(validate(context(true, "Return the submitted entity", "Existing member has ID 1"), code))
                .hasValueSatisfying(message -> assertThat(message).contains("mapping fixture", "enabled", "initial"));
    }

    @Test
    void acceptsADistinctInitialValueWithoutChangingTheApprovedRequestOrAssertion() {
        assertThat(validate(context(true, "Return the submitted entity", "Existing member has ID 1"),
                source("current.setEnabled(false);", "thenAnswer(invocation -> invocation.getArgument(0))", ""))).isEmpty();
    }

    @Test
    void preservesAnInitialValueExplicitlyFixedByApprovedPreconditions() {
        assertThat(validate(context(true, "Return the submitted entity", "Existing enabled is true"),
                source("", "thenAnswer(invocation -> invocation.getArgument(0))", ""))).isEmpty();
    }

    @Test
    void preservesAnInitialValueExplicitlyFixedByTheLookupMock() {
        var base = context(true, "Return the submitted entity", "Existing member has ID 1");
        var approved = base.approvedTestCases().get(0);
        var data = Map.<String, Object>of("input", Map.of("id", 1, "request", Map.of("label", "An", "enabled", true)),
                "mocks", Map.of("store.save", "Return the submitted entity", "store.find(1)", "Member(id=1, enabled=true)"));
        var changed = new TestCaseContextItemDto(approved.id(), approved.testPlanId(), approved.caseCode(), approved.testType(),
                approved.description(), approved.preconditions(), data, approved.expectedResult(), approved.priority(),
                approved.traceSource(), approved.status(), approved.isModified());
        var context = new UnitTestContextDto(base.project(), null, base.classes(), base.approvedBusinessRules(), base.approvedTestPlans(),
                List.of(changed), List.of(), List.of(), List.of());
        assertThat(validate(context, source("", "thenAnswer(invocation -> invocation.getArgument(0))", ""))).isEmpty();
    }

    @Test
    void doesNotRejectAnExplicitIdempotenceScenario() {
        var base = context(true, "Return the submitted entity", "Existing member has ID 1");
        var approved = base.approvedTestCases().get(0);
        var changed = new TestCaseContextItemDto(approved.id(), approved.testPlanId(), approved.caseCode(), approved.testType(),
                "An idempotent update keeps the same value", approved.preconditions(), approved.testData(), approved.expectedResult(),
                approved.priority(), approved.traceSource(), approved.status(), approved.isModified());
        var context = new UnitTestContextDto(base.project(), null, base.classes(), base.approvedBusinessRules(), base.approvedTestPlans(),
                List.of(changed), List.of(), List.of(), List.of());
        assertThat(validate(context, source("", "thenAnswer(invocation -> invocation.getArgument(0))", ""))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"initialise(current);", "Member alias = current; alias.setEnabled(false);",
            "current.setEnabled(false); Runnable later = () -> current.setEnabled(true);"})
    void doesNotGuessInitialStateAfterHelperAliasOrDeferredSetup(String initialState) {
        String code = source(initialState, "thenAnswer(invocation -> invocation.getArgument(0))", "")
                .replace("class ServiceTest {", "class ServiceTest { void initialise(Member value) { value.setEnabled(false); }");
        assertThat(validate(context(true, "Return the submitted entity", "Existing member has ID 1"), code)).isEmpty();
    }

    @Test
    void doesNotTreatCleanupAfterActAsTheInitialState() {
        String code = source("current.setEnabled(false);", "thenAnswer(invocation -> invocation.getArgument(0))", "")
                .replace("View result = service.update(1L, request);", "View result = service.update(1L, request); current.setEnabled(true);");
        assertThat(validate(context(true, "Return the submitted entity", "Existing member has ID 1"), code)).isEmpty();
    }

    @Test
    void preservesAnInitialValueFixedOnlyInStructuredTestData() {
        var base = context(true, "Return the submitted entity", "Existing member has ID 1");
        var data = new java.util.LinkedHashMap<>(base.approvedTestCases().get(0).testData());
        var input = new java.util.LinkedHashMap<>((Map<String, Object>) data.get("input"));
        input.put("existingMember", Map.of("enabled", true));
        data.put("input", input);

        assertThat(validate(withData(base, data), source("", "thenAnswer(invocation -> invocation.getArgument(0))", ""))).isEmpty();
    }

    @Test
    void preservesANullableFieldUpdateWhenOldDataIsNonNull() {
        var base = context(false, "Return the saved entity retaining mapped fields", "Existing member has ID 1");
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("label", null); request.put("enabled", true);
        var data = new java.util.LinkedHashMap<>(base.approvedTestCases().get(0).testData());
        data.put("input", Map.of("id", 1, "existingMember", Map.of("label", "old"), "request", request));
        String code = source("current.setLabel(\"old\");", "thenReturn(saved)", "")
                .replace("request.setLabel(\"An\")", "request.setLabel(null)")
                .replace("assertEquals(\"An\", result.getLabel())", "assertNull(result.getLabel())");

        assertThat(validate(withData(base, data), code)).isEmpty();
    }

    @Test
    void acceptsASeparateFixturePopulatedAfterStubbingBeforeAct() {
        String code = source("", "thenReturn(saved)", "")
                .replace("View result =", "saved.setLabel(\"An\"); View result =");
        assertThat(validate(context(false, "Return the saved entity retaining mapped fields", "Valid request"), code)).isEmpty();
    }

    @Test
    void doesNotAllowChangingTheApprovedRequestToEvadeTheMappingCheck() {
        String code = source("", "thenAnswer(invocation -> invocation.getArgument(0))", "")
                .replace("request.setEnabled(true)", "request.setEnabled(false)");
        assertThat(validate(context(true, "Return the submitted entity", "Existing member has ID 1"), code))
                .hasValueSatisfying(message -> assertThat(message).contains("approved request", "enabled", "true"));
    }

    @Test
    void preservesANonNullInputIntentionallyMappedToANullOutput() {
        var base = context(false, "Return the saved entity retaining mapped fields", "Valid request");
        var data = new java.util.LinkedHashMap<>(base.approvedTestCases().get(0).testData());
        data.put("input", Map.of("id", 1, "request", Map.of("label", "   ", "enabled", true)));
        var changed = withData(base, data);
        var approved = changed.approvedTestCases().get(0);
        var normalized = new TestCaseContextItemDto(approved.id(), approved.testPlanId(), approved.caseCode(), approved.testType(),
                "Normalize a blank label to null", approved.preconditions(), approved.testData(), "The returned label is null", approved.priority(),
                approved.traceSource(), approved.status(), approved.isModified());
        var context = new UnitTestContextDto(base.project(), null, base.classes(), base.approvedBusinessRules(), base.approvedTestPlans(),
                List.of(normalized), List.of(), List.of(), List.of());
        String code = source("", "thenReturn(saved)", "").replace("request.setLabel(\"An\")", "request.setLabel(\"   \")")
                .replace("assertEquals(\"An\", result.getLabel())", "assertNull(result.getLabel())");

        assertThat(validate(context, code)).isEmpty();
    }

    @Test
    void treatsACustomSetHelperAsUnknownWithoutCrashing() {
        var base = context(false, "Return the saved entity retaining mapped fields", "Valid request");
        var classes = base.classes().stream().map(type -> type.className().equals("Member") ? new ClassContextDto(type.id(),
                type.packageName(), type.className(), type.qualifiedName(), type.classType(), type.filePath(),
                type.sourceCode().replace("class Member {", "class Member { public void set(String value) { this.label = value; }"),
                type.annotations(), type.methods()) : type).toList();
        var context = new UnitTestContextDto(base.project(), null, classes, base.approvedBusinessRules(), base.approvedTestPlans(),
                base.approvedTestCases(), List.of(), List.of(), List.of());

        assertThatCode(() -> assertThat(validate(context, source("", "thenReturn(saved)", "saved.set(\"An\");"))).isEmpty())
                .doesNotThrowAnyException();
    }

    private UnitTestContextDto withData(UnitTestContextDto base, Map<String, Object> data) {
        var approved = base.approvedTestCases().get(0);
        var changed = new TestCaseContextItemDto(approved.id(), approved.testPlanId(), approved.caseCode(), approved.testType(),
                approved.description(), approved.preconditions(), data, approved.expectedResult(), approved.priority(),
                approved.traceSource(), approved.status(), approved.isModified());
        return new UnitTestContextDto(base.project(), null, base.classes(), base.approvedBusinessRules(), base.approvedTestPlans(),
                List.of(changed), List.of(), List.of(), List.of());
    }

    private Optional<String> validate(UnitTestContextDto context, String source) {
        return GeneratedUnitTestSemanticValidator.validate(context, response(source));
    }

    static UnitTestResponseDto response(String source) {
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(40L, "ServiceTest", "updates", "demo", "NEW_TEST", source)));
    }

    static UnitTestContextDto context(boolean mapping, String saveDescription, String preconditions) {
        String mappingBody = "private void map(Request request, Member member) { member.setLabel(request.getLabel()); member.setEnabled(request.getEnabled()); }";
        String updateBody = "public View update(Long id, Request request) { Member current = store.find(id).orElseThrow(); map(request, current); return new View(store.save(current)); }";
        var method = new MethodContextDto(10L, "demo.Service", mapping ? "map" : "update", mapping ? "void" : "View",
                List.of(), List.of(), mapping ? "private" : "public", mapping ? mappingBody : updateBody, 1, 1, List.of(), List.of(),
                mapping ? List.of(new SourceBranchDto("STMT-2", "STATEMENT", "EXECUTABLE", "statement", 1, 1)) : List.of());
        var service = type(1L, "Service", "class Service { private final Store store; Service(Store store) { this.store = store; } "
                + updateBody + mappingBody + " }", List.of(method));
        var member = type(2L, "Member", "class Member { private Long id; private String label; private Boolean enabled = true; public Member() {} "
                + "public Long getId() { return id; } public void setId(Long value) { this.id = value; } "
                + "public String getLabel() { return label; } public void setLabel(String value) { this.label = value; } "
                + "public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { this.enabled = value; } }", List.of());
        var request = type(3L, "Request", "class Request { private String label; private Boolean enabled; "
                + "public String getLabel() { return label; } public void setLabel(String value) { this.label = value; } "
                + "public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { this.enabled = value; } }", List.of());
        var view = type(4L, "View", "class View { private String label; private Boolean enabled; public View(Member value) { "
                + "this.label = value.getLabel(); this.enabled = value.getEnabled(); } public String getLabel() { return label; } "
                + "public Boolean getEnabled() { return enabled; } }", List.of());
        var store = type(5L, "Store", "interface Store { java.util.Optional<Member> find(Long id); Member save(Member value); }", List.of());
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-001", mapping ? "Map availability from the request" : "Update and return a member",
                null, "USER", "APPROVED", false, mapping ? "STMT-2" : null);
        var plan = new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-001", "Update member", "Update mapped fields", "HAPPY_PATH", "APPROVED", false);
        var data = Map.<String, Object>of("input", Map.of("id", 1, "request", Map.of("label", "An", "enabled", true)),
                "mocks", Map.of("store.save", saveDescription, "store.find(1)", "Optional containing member with ID 1"));
        var approved = new TestCaseContextItemDto(40L, 30L, "TC-001", "HAPPY_PATH", "Map the request fields", preconditions, data,
                "The returned label is An and enabled is true", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(new ProjectContextDto(1L, "demo", "ANALYZED"), null, List.of(service, member, request, view, store),
                List.of(rule), List.of(plan), List.of(approved), List.of(), List.of(), List.of());
    }

    private static ClassContextDto type(Long id, String name, String source, List<MethodContextDto> methods) {
        return new ClassContextDto(id, "demo", name, "demo." + name, "OTHER", "src/main/java/demo/" + name + ".java",
                "package demo; " + source, List.of(), methods);
    }

    static String source(String initialState, String saveReturn, String savedSetup) {
        return "package demo; import java.util.Optional; import org.junit.jupiter.api.Test; "
                + "import org.junit.jupiter.api.extension.ExtendWith; import org.mockito.Mock; import org.mockito.InjectMocks; "
                + "import org.mockito.junit.jupiter.MockitoExtension; import static org.mockito.Mockito.*; "
                + "import static org.junit.jupiter.api.Assertions.*; @ExtendWith(MockitoExtension.class) "
                + "class ServiceTest { @Mock Store store; @InjectMocks Service service; @Test void updates() { "
                + "Request request = new Request(); request.setLabel(\"An\"); request.setEnabled(true); "
                + "Member current = new Member(); current.setId(1L); " + initialState
                + "Member saved = new Member(); saved.setId(1L); " + savedSetup
                + "when(store.find(1L)).thenReturn(Optional.of(current)); when(store.save(any(Member.class)))." + saveReturn + "; "
                + "View result = service.update(1L, request); assertEquals(\"An\", result.getLabel()); assertEquals(true, result.getEnabled()); } }";
    }
}
