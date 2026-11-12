package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Thư viện ngoài cố ý mang tên giả để bảo đảm quy tắc không phụ thuộc framework cụ thể:
 * không có JAR thì chỉ chưa kiểm chứng được phần đi qua thư viện, phần trong source vẫn kiểm tra chặt.
 */
class ExternalAncestorOutputDependencyTest {
    @TempDir Path root;

    @BeforeEach void writeProject() throws Exception {
        Path app = root.resolve("src/main/java/com/acme/shop/app"); Files.createDirectories(app);
        Path core = root.resolve("src/main/java/com/acme/shop/core"); Files.createDirectories(core);
        Files.writeString(core.resolve("Item.java"), "package com.acme.shop.core; public class Item { public Long getId() { return 1L; } }");
        Files.writeString(core.resolve("ItemStore.java"), "package com.acme.shop.core; import vendor.persist.GenericStore; "
                + "public interface ItemStore extends GenericStore<Item> { Item own(Long id); }");
        Files.writeString(core.resolve("Plain.java"), "package com.acme.shop.core; import vendor.persist.*; public class Plain implements java.io.Serializable { "
                + "public <T> boolean wild(Filter filter, T value) { return true; } public java.util.Set<String> names() { return null; } "
                + "private String label; public Item fetch(Long id) { return null; } "
                + "public boolean accept(vendor.persist.Criteria<Item> criteria) { return true; } }");
        Files.writeString(core.resolve("Locator.java"), "package com.acme.shop.core; public class Locator implements vendor.persist.Aware { "
                + "private static Object registry; public void install(Object value) { if (registry == null) registry = value; } "
                + "public void shadow(Object registry) { registry = null; } public static void reset() { registry = null; } }");
        Files.writeString(core.resolve("ItemRepository.java"), "package com.acme.shop.core; public interface ItemRepository "
                + "extends org.springframework.data.repository.CrudRepository<Item, Long> { }");
        Path helpers = root.resolve("src/test/java/com/acme/shop/app"); Files.createDirectories(helpers);
        Files.writeString(helpers.resolve("TestData.java"), "package com.acme.shop.app; class TestData { static Object item() { return null; } }");
        Files.writeString(app.resolve("Shop.java"), "package com.acme.shop.app; import com.acme.shop.core.*; import vendor.persist.BaseService; "
                + "public class Shop extends BaseService<ItemStore, Item> { private Plain plain; public Item find(Long id) { return plain.fetch(id); } }");
    }

    private List<String> resolve(String statement) {
        String source = """
                package com.acme.shop.app;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.any;
                import org.mockito.Mock;
                import org.mockito.ArgumentCaptor;
                import org.springframework.test.util.ReflectionTestUtils;
                import com.acme.shop.core.Item;
                import com.acme.shop.core.ItemStore;
                import com.acme.shop.core.Plain;
                import vendor.persist.Criteria;
                import vendor.persist.Helper;
                class ShopTest {
                    @Mock ItemStore store;
                    @Mock com.acme.shop.core.ItemRepository repository;
                    @Mock Plain plain;
                    Shop shop;
                    Criteria<Item> criteria;
                    void test() { %s }
                }
                """.formatted(statement);
        var problems = new ArrayList<String>();
        new GeneratedOutputDependencyResolver(SourceDependencyIndex.load(root), problems, ".").resolve(List.of(source));
        return problems;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "when(store.inherited(1L)).thenReturn(new Item());",
        "verify(store).inherited(1L);",
        "doReturn(new Item()).when(store).inherited(1L);",
        "when(store.inherited(any(Criteria.class))).thenReturn(new Item());",
        "ArgumentCaptor<Criteria> captor = ArgumentCaptor.forClass(Criteria.class); verify(store).inherited(captor.capture());",
        "when(store.query(any(vendor.persist.Criteria.class))).thenReturn(new Item());",
        "shop.save(new Item());",
        "Helper.prepare(Item.class);",
        "vendor.persist.Helper.prepare(Item.class);",
        "criteria.eq(1L);",
        "ReflectionTestUtils.setField(shop, \"inheritedSlot\", store);",
        "new com.acme.shop.core.Locator().install(new Object()); ReflectionTestUtils.setField(com.acme.shop.core.Locator.class, \"registry\", null);",
        "Shop spied = spy(new Shop());",
        "verifyNoMoreInteractions(store);",
        "doReturn(new Item()).when(shop).find(1L);",
        "when(plain.accept(any())).thenReturn(true);",
        "verify(plain).accept(any(Criteria.class));",
        "verify(store).inherited(org.mockito.ArgumentMatchers.argThat(item -> item.getId() == 1L));",
        "when(store.inherited(Item::getId)).thenReturn(new Item());",
        "verify(store).invokeRemote(1L);",
        "when(plain.names()).thenReturn(new java.util.HashSet<>(java.util.Collections.singletonList(\"x\")));",
        "verify(store).own(1L);",
        "when(store.own(1L)).thenReturn(new Item());",
        "doReturn(new Item()).when(store).own(any());",
        "Object fixture = TestData.item();",
        "TestData helper = null;",
        "when(repository.methodOfAnotherSpringDataVersion(1L)).thenReturn(new Item());",
        "when(plain.wild(any(), any())).thenReturn(true);",
        "new com.acme.shop.core.Locator().shadow(new Object());"})
    void acceptsCallsThatOnlyALibraryOutsideTheSourceCouldConfirm(String statement) {
        assertThat(resolve(statement)).as(statement).noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }

    private List<String> verifyPurge(String matcher) throws Exception {
        Files.writeString(root.resolve("src/main/java/com/acme/shop/core/Purger.java"), "package com.acme.shop.core; import java.util.List; "
                + "public interface Purger { Boolean purge(String key); Long purge(List<String> keys); }");
        return resolveSource("""
                package com.acme.shop.app;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.any;
                import org.mockito.ArgumentMatchers;
                import org.mockito.Mock;
                import com.acme.shop.core.Purger;
                class ShopTest {
                    @Mock Purger purger;
                    void test() { verify(purger, never()).purge(%s); }
                }
                """.formatted(matcher));
    }

    @Test void anUntypedMatcherOnAnOverloadTellsTheModelHowToKeepTheBanNullInclusive() throws Exception {
        assertThat(verifyPurge("any()"))
                .anySatisfy(problem -> assertThat(problem).startsWith("unresolved Mockito signature: purge").contains("ArgumentMatchers.<java.util.List<String>>any()"));
        assertThat(verifyPurge("ArgumentMatchers.<java.util.List<String>>any()")).noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
        assertThat(verifyPurge("ArgumentMatchers.<String>any()")).noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }

    private List<String> resolveSource(String source) {
        var problems = new ArrayList<String>();
        new GeneratedOutputDependencyResolver(SourceDependencyIndex.load(root), problems, ".").resolve(List.of(source));
        return problems;
    }

    @Test void acceptsStaticStateRestoredThroughTheOwnerInCleanup() {
        String prefix = "package com.acme.shop.app; import com.acme.shop.core.Locator; class ShopTest { ";
        assertThat(resolveSource(prefix + "@org.junit.jupiter.api.AfterEach void clean() { Locator.reset(); } void test() { new Locator().install(new Object()); } }"))
                .noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
        assertThat(resolveSource(prefix + "void test() { try { new Locator().install(new Object()); } finally { Locator.reset(); } } }"))
                .noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }

    @Test void doesNotRequireRestoreWhenTheOwnerIsTheClassUnderTest() {
        assertThat(resolveSource("package com.acme.shop.app; import com.acme.shop.core.Locator; class LocatorTest { void test() { new Locator().install(new Object()); } }"))
                .noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }

    @Test void acceptsTypesFromAWildcardLibraryImportInTheTest() {
        assertThat(resolveSource("package com.acme.shop.app; import static org.mockito.Mockito.*; import vendor.persist.*; "
                + "class ShopTest { void test() { Criteria<Object> criteria = null; criteria.eq(1L); } }"))
                .noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }

    @Test void treatsASiblingLibraryOfAMultiModuleProjectAsOutsideTheSource(@TempDir Path project) throws Exception {
        for (String module : List.of("first", "second")) {
            Path dir = project.resolve(module + "/src/main/java/com/corp/" + module); Files.createDirectories(dir);
            Files.writeString(dir.resolve("Own.java"), "package com.corp." + module + "; public class Own { }");
        }
        var problems = new ArrayList<String>();
        new GeneratedOutputDependencyResolver(SourceDependencyIndex.load(project), problems, "first").resolve(List.of(
                "package com.corp.first; import com.corp.common.Helper; class OwnTest { void test() { Helper.prepare(1L); } }"));
        assertThat(problems).noneMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "when(plain.missing(1L)).thenReturn(new Item());",
        "when(plain.missing(any(Criteria.class))).thenReturn(new Item());",
        "verify(store).own(\"wrong type\");",
        "when(store.own(1L)).thenReturn(\"wrong type\");",
        "ReflectionTestUtils.invokeMethod(plain, \"fetch\", 1L);",
        "verify(plain).missing(1L);",
        "when(plain.fetch(1L)).thenReturn(\"wrong type\");",
        "when(plain.fetch(\"wrong argument\")).thenReturn(new Item());",
        "java.util.List<Item> items = new ArrayList<Item>();",
        "when(plain.fetch(1L)).thenReturn(Collections.<Item>emptyList().get(0));",
        "when(store.inherited(1L)).thenReturn(new Item(), new ArrayList<Item>());",
        "new com.acme.shop.core.Locator().install(new Object());",
        "Missing.call();",
        "Object value = Missing.CONSTANT;",
        "com.acme.shop.invented.Helper.prepare(Item.class);",
        "ReflectionTestUtils.setField(plain, \"inventedField\", store);",
        "ReflectionTestUtils.setField(this, \"inventedField\", store);"})
    void stillRejectsWhatTheSourceOrJdkCanDisprove(String statement) {
        assertThat(resolve(statement)).as(statement).anyMatch(com.greytest.service.agent.AIAgentService::isLlmOutputProblem);
    }
}
