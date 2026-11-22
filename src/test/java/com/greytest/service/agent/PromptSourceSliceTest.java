package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Cắt context prompt theo method đích: giữ thứ method chạy qua, rút gọn/bỏ phần không liên quan. */
class PromptSourceSliceTest {
    private final ObjectMapper json = new ObjectMapper();

    private static final String SERVICE = """
            package demo;

            class Service {
                private final Repo repo;
                private final Other other;

                Service(Repo repo, Other other) { this.repo = repo; this.other = other; }

                public Item run(Long id) {
                    Item item = repo.find(id).orElseThrow(() -> new NotFoundException("missing"));
                    check(item);
                    return item;
                }

                private void check(Item item) {
                    if (item.getStatus() == Status.A) throw new NotFoundException("bad");
                }

                public void unrelated() {
                    Unused unused = new Unused();
                    other.call(unused);
                }
            }
            """;

    private ObjectNode context() {
        var classes = List.of(
                Map.of("className", "Service", "sourceCode", SERVICE,
                        "methods", List.of(Map.of("methodName", "run", "parameters", List.of(Map.of("name", "id", "type", "Long"))))),
                Map.of("className", "Item", "sourceCode", "package demo; class Item { Tag tag; Status getStatus() { return null; } }", "methods", List.of()),
                Map.of("className", "Tag", "sourceCode", "package demo; class Tag { String name() { return \"t\"; } }", "methods", List.of()),
                Map.of("className", "Status", "sourceCode", "package demo; enum Status { A, B }", "methods", List.of()),
                Map.of("className", "Repo", "sourceCode", "package demo; interface Repo { java.util.Optional<Item> find(Long id); }", "methods", List.of()),
                Map.of("className", "Other", "sourceCode", "package demo; class Other { void call(Object o) { System.out.println(o); } }", "methods", List.of()),
                Map.of("className", "Unused", "sourceCode", "package demo; class Unused { int x() { return 1; } }", "methods", List.of()),
                Map.of("className", "NotFoundException", "sourceCode",
                        "package demo; class NotFoundException extends RuntimeException { NotFoundException(String m) { super(m); } }", "methods", List.of()));
        return json.valueToTree(Map.of("classes", classes));
    }

    private Map<String, String> sources(ObjectNode context) {
        return StreamSupport.stream(context.path("classes").spliterator(), false)
                .collect(Collectors.toMap(c -> c.path("className").asText(), c -> c.path("sourceCode").asText()));
    }

    @Test void omitsBodiesOfUnreachedMethodsButKeepsLineNumbers() {
        var context = context();
        PromptSourceContext.sliceToTargets(context, true);
        String service = sources(context).get("Service");
        assertThat(service).contains("repo.find(id).orElseThrow", "if (item.getStatus() == Status.A)", "public void unrelated()");
        assertThat(service).doesNotContain("new Unused()", "other.call(unused)");
        assertThat(service.lines().count()).isEqualTo(SERVICE.lines().count());
        assertThat(service.lines().toList().indexOf("    private void check(Item item) {"))
                .isEqualTo(SERVICE.lines().toList().indexOf("    private void check(Item item) {"));
    }

    @Test void keepsTypesReachedByTargetCodeAndDropsUnrelatedClasses() {
        var context = context();
        PromptSourceContext.sliceToTargets(context, true);
        var sources = sources(context);
        assertThat(sources).doesNotContainKey("Unused");
        // Item được dùng trực tiếp, Tag tới qua field của Item, Status và exception xuất hiện trong thân method.
        assertThat(sources.get("Item")).contains("return null;");
        assertThat(sources.get("Tag")).contains("return \"t\";");
        assertThat(sources.get("Status")).contains("A, B");
        assertThat(sources.get("NotFoundException")).contains("super(m)");
        // Other chỉ là field của class đích (dùng trong method bị lược) nên chỉ cần khai báo để mock.
        assertThat(sources.get("Other")).contains("void call(Object o)").doesNotContain("System.out.println");
        assertThat(sources.get("Repo")).contains("find(Long id)");
    }

    @Test void followsMethodReferencesAndCallsQualifiedWithTheTargetClassName() {
        String service = """
                package demo;
                class Svc {
                    java.util.List<String> run(java.util.List<String> items) {
                        return items.stream().map(this::toView).map(Svc::trim).map(x -> Svc.normalize(x)).toList();
                    }
                    String toView(String s) { return "view:" + s; }
                    static String trim(String s) { return s.strip(); }
                    static String normalize(String s) { return s.toLowerCase(); }
                    void unrelated() { System.out.println("unused"); }
                }
                """;
        ObjectNode context = json.valueToTree(Map.of("classes", List.of(Map.of("className", "Svc", "sourceCode", service,
                "methods", List.of(Map.of("methodName", "run"))))));
        PromptSourceContext.sliceToTargets(context, false);
        assertThat(sources(context).get("Svc")).contains("\"view:\" + s", "s.strip()", "s.toLowerCase()")
                .doesNotContain("\"unused\"");
    }

    @Test void keepsEntityBodiesReachedOnlyThroughCollaboratorReturnTypesWhenRequested() {
        String service = "package demo;\nclass Svc {\n  Repo repo;\n  boolean run() { return repo.find().orElseThrow().suspended(); }\n}\n";
        var classes = List.of(
                Map.of("className", "Svc", "sourceCode", service, "methods", List.of(Map.of("methodName", "run"))),
                Map.of("className", "Repo", "classType", "REPOSITORY", "sourceCode",
                        "package demo; interface Repo { java.util.Optional<Member> find(); }", "methods", List.of()),
                Map.of("className", "Member", "classType", "ENTITY", "sourceCode",
                        "package demo; class Member { boolean flag; boolean suspended() { return flag && true; } }", "methods", List.of()));
        ObjectNode forUnitTest = json.valueToTree(Map.of("classes", classes));
        PromptSourceContext.sliceToTargets(forUnitTest, true);
        assertThat(sources(forUnitTest).get("Member")).contains("return flag && true;");
        ObjectNode forRules = json.valueToTree(Map.of("classes", classes));
        PromptSourceContext.sliceToTargets(forRules, false);
        assertThat(sources(forRules).get("Member")).contains("boolean suspended()").doesNotContain("return flag && true;");
    }

    @Test void collapsesPlainAccessorsOfKeptSupportingClassesButKeepsLogic() {
        String service = "package demo;\nclass Svc {\n  boolean run(Loan loan) { return loan.isOverdue(); }\n}\n";
        String loan = """
                package demo;
                class Loan {
                    private String code;
                    private int days;
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                    public int getDays() { return this.days; }
                    public boolean isOverdue() { return days > 14; }
                    public void setDays(int value) { if (value < 0) throw new IllegalArgumentException(); this.days = value; }
                }
                """;
        ObjectNode context = json.valueToTree(Map.of("classes", List.of(
                Map.of("className", "Svc", "sourceCode", service, "methods", List.of(Map.of("methodName", "run"))),
                Map.of("className", "Loan", "classType", "ENTITY", "sourceCode", loan, "methods", List.of()))));
        PromptSourceContext.sliceToTargets(context, true);
        String kept = sources(context).get("Loan");
        assertThat(kept).contains("private String code;", "public String getCode();", "public void setCode(String code);",
                "public int getDays();", "return days > 14;", "throw new IllegalArgumentException()")
                .doesNotContain("return code;", "this.code = code;", "return this.days;");
    }

    @Test void keepsPublicCallersOfAPrivateTargetSoTestsCanReachIt() {
        // Method đích private chỉ test được qua method public gọi tới nó: phải giữ thân các caller đó.
        String service = """
                package demo;
                class Svc {
                    public void borrow(String code) { load(code); assertCanBorrow(code); }
                    private void load(String code) { System.out.println("load"); }
                    private void assertCanBorrow(String code) { if (code == null) throw new IllegalStateException("x"); }
                    public void unrelated() { System.out.println("unused"); }
                }
                """;
        ObjectNode context = json.valueToTree(Map.of("classes", List.of(Map.of("className", "Svc", "sourceCode", service,
                "methods", List.of(Map.of("methodName", "assertCanBorrow"))))));
        PromptSourceContext.sliceToTargets(context, true);
        assertThat(sources(context).get("Svc")).contains("load(code); assertCanBorrow(code);", "System.out.println(\"load\")",
                "throw new IllegalStateException").doesNotContain("\"unused\"");
    }

    @Test void leavesContextUnchangedWhenTargetSourceCannotBeParsed() {
        ObjectNode context = json.valueToTree(Map.of("classes", List.of(
                Map.of("className", "Broken", "sourceCode", "class Broken { void run( {", "methods", List.of(Map.of("methodName", "run"))),
                Map.of("className", "Unused", "sourceCode", "class Unused { }", "methods", List.of()))));
        var before = context.deepCopy();
        PromptSourceContext.sliceToTargets(context, true);
        assertThat(context).isEqualTo(before);
    }
}
