package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.service.agent.LlmStageTokenLimits;

class UnitTestDependencyVerificationRegressionTest {

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationFingerprintService service = new GenerationFingerprintService(
            mapper, null, LlmStageTokenLimits.defaults(), new MockEnvironment());

    @BeforeEach
    void setupSourceTree() throws Exception {
        // Module 1: product-service
        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri/app/domain/AbstractMappedEntity.java",
                "package com.selimhorri.app.domain;\n" +
                "import java.io.Serializable;\n" +
                "import java.time.Instant;\n" +
                "import javax.persistence.MappedSuperclass;\n" +
                "import lombok.*;\n" +
                "@MappedSuperclass\n" +
                "@Data\n" +
                "@NoArgsConstructor(access = AccessLevel.PROTECTED)\n" +
                "@AllArgsConstructor(access = AccessLevel.PROTECTED)\n" +
                "public abstract class AbstractMappedEntity implements Serializable {\n" +
                "    private Instant createdAt;\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri/app/domain/Category.java",
                "package com.selimhorri.app.domain;\n" +
                "import java.util.Set;\n" +
                "import javax.persistence.*;\n" +
                "import lombok.*;\n" +
                "@Entity\n" +
                "@Table(name = \"categories\")\n" +
                "@Data\n" +
                "@NoArgsConstructor(access = AccessLevel.PROTECTED)\n" +
                "@AllArgsConstructor(access = AccessLevel.PROTECTED)\n" +
                "@EqualsAndHashCode(callSuper = true, exclude = {\"subCategories\", \"products\"})\n" +
                "@Builder\n" +
                "public class Category extends AbstractMappedEntity {\n" +
                "    @Id @GeneratedValue private Integer categoryId;\n" +
                "    private String categoryTitle;\n" +
                "    private String imageUrl;\n" +
                "    @ManyToOne private Category parentCategory;\n" +
                "    @OneToMany private Set<Category> subCategories;\n" +
                "    @OneToMany private Set<Product> products;\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/domain/Product.java",
                "package com.selimhorri.app.domain;\n" +
                "import javax.persistence.*;\n" +
                "import lombok.*;\n" +
                "@Entity\n" +
                "@Table(name = \"products\")\n" +
                "@Data\n" +
                "@NoArgsConstructor(access = AccessLevel.PROTECTED)\n" +
                "@AllArgsConstructor(access = AccessLevel.PROTECTED)\n" +
                "@EqualsAndHashCode(callSuper = true, exclude = {\"category\"})\n" +
                "@Builder\n" +
                "public class Product extends AbstractMappedEntity {\n" +
                "    @Id @GeneratedValue private Integer productId;\n" +
                "    private String productTitle;\n" +
                "    private String imageUrl;\n" +
                "    private String sku;\n" +
                "    private Double priceUnit;\n" +
                "    private Integer quantity;\n" +
                "    @ManyToOne private Category category;\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/dto/CategoryDto.java",
                "package com.selimhorri.app.dto;\n" +
                "import java.io.Serializable;\n" +
                "import lombok.*;\n" +
                "@Data\n" +
                "@NoArgsConstructor\n" +
                "@AllArgsConstructor\n" +
                "@Builder\n" +
                "public class CategoryDto implements Serializable {\n" +
                "    private Integer categoryId;\n" +
                "    private String categoryTitle;\n" +
                "    private String imageUrl;\n" +
                "    private CategoryDto parentCategoryDto;\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/dto/ProductDto.java",
                "package com.selimhorri.app.dto;\n" +
                "import java.io.Serializable;\n" +
                "import lombok.*;\n" +
                "@Data\n" +
                "@NoArgsConstructor\n" +
                "@AllArgsConstructor\n" +
                "@Builder\n" +
                "public class ProductDto implements Serializable {\n" +
                "    private Integer productId;\n" +
                "    private String productTitle;\n" +
                "    private String imageUrl;\n" +
                "    private String sku;\n" +
                "    private Double priceUnit;\n" +
                "    private Integer quantity;\n" +
                "    private CategoryDto categoryDto;\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/repository/ProductRepository.java",
                "package com.selimhorri.app.repository;\n" +
                "import org.springframework.data.jpa.repository.JpaRepository;\n" +
                "import org.springframework.stereotype.Repository;\n" +
                "import com.selimhorri.app.domain.Product;\n" +
                "@Repository\n" +
                "public interface ProductRepository extends JpaRepository<Product, Integer> {}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/repository/CategoryRepository.java",
                "package com.selimhorri.app.repository;\n" +
                "import org.springframework.data.jpa.repository.JpaRepository;\n" +
                "import org.springframework.stereotype.Repository;\n" +
                "import com.selimhorri.app.domain.Category;\n" +
                "@Repository\n" +
                "public interface CategoryRepository extends JpaRepository<Category, Integer> {}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/exception/wrapper/ProductNotFoundException.java",
                "package com.selimhorri.app.exception.wrapper;\n" +
                "public class ProductNotFoundException extends RuntimeException {\n" +
                "    public ProductNotFoundException(String message) { super(message); }\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/helper/ProductMappingHelper.java",
                "package com.selimhorri.app.helper;\n" +
                "import com.selimhorri.app.domain.Product;\n" +
                "import com.selimhorri.app.dto.ProductDto;\n" +
                "public interface ProductMappingHelper {\n" +
                "    static ProductDto map(Product product) {\n" +
                "        return ProductDto.builder().productId(product.getProductId()).productTitle(product.getProductTitle()).imageUrl(product.getImageUrl()).sku(product.getSku()).priceUnit(product.getPriceUnit()).quantity(product.getQuantity()).build();\n" +
                "    }\n" +
                "    static Product map(ProductDto dto) {\n" +
                "        return Product.builder().productId(dto.getProductId()).productTitle(dto.getProductTitle()).imageUrl(dto.getImageUrl()).sku(dto.getSku()).priceUnit(dto.getPriceUnit()).quantity(dto.getQuantity()).build();\n" +
                "    }\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/helper/CategoryMappingHelper.java",
                "package com.selimhorri.app.helper;\n" +
                "import com.selimhorri.app.domain.Category;\n" +
                "import com.selimhorri.app.dto.CategoryDto;\n" +
                "public interface CategoryMappingHelper {\n" +
                "    static CategoryDto map(Category category) {\n" +
                "        return CategoryDto.builder().categoryId(category.getCategoryId()).categoryTitle(category.getCategoryTitle()).imageUrl(category.getImageUrl()).build();\n" +
                "    }\n" +
                "    static Category map(CategoryDto dto) {\n" +
                "        return Category.builder().categoryId(dto.getCategoryId()).categoryTitle(dto.getCategoryTitle()).imageUrl(dto.getImageUrl()).build();\n" +
                "    }\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/service/ProductService.java",
                "package com.selimhorri.app.service;\n" +
                "import com.selimhorri.app.dto.ProductDto;\n" +
                "public interface ProductService {\n" +
                "    ProductDto findById(Integer productId);\n" +
                "    ProductDto save(ProductDto productDto);\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/service/impl/ProductServiceImpl.java",
                "package com.selimhorri.app.service.impl;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "import org.springframework.transaction.annotation.Transactional;\n" +
                "import lombok.RequiredArgsConstructor;\n" +
                "import lombok.extern.slf4j.Slf4j;\n" +
                "import com.selimhorri.app.domain.Product;\n" +
                "import com.selimhorri.app.dto.ProductDto;\n" +
                "import com.selimhorri.app.exception.wrapper.ProductNotFoundException;\n" +
                "import com.selimhorri.app.helper.ProductMappingHelper;\n" +
                "import com.selimhorri.app.repository.ProductRepository;\n" +
                "import com.selimhorri.app.service.ProductService;\n" +
                "@Service\n" +
                "@Transactional\n" +
                "@Slf4j\n" +
                "@RequiredArgsConstructor\n" +
                "public class ProductServiceImpl implements ProductService {\n" +
                "    private final ProductRepository productRepository;\n" +
                "    @Override\n" +
                "    public ProductDto findById(Integer productId) {\n" +
                "        log.info(\"Find product by id: {}\", productId);\n" +
                "        return this.productRepository.findById(productId)\n" +
                "                .map(ProductMappingHelper::map)\n" +
                "                .orElseThrow(() -> new ProductNotFoundException(String.format(\"Product with id: %d not found\", productId)));\n" +
                "    }\n" +
                "    @Override\n" +
                "    public ProductDto save(ProductDto productDto) {\n" +
                "        return ProductMappingHelper.map(this.productRepository.save(ProductMappingHelper.map(productDto)));\n" +
                "    }\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/service/CategoryService.java",
                "package com.selimhorri.app.service;\n" +
                "import com.selimhorri.app.dto.CategoryDto;\n" +
                "public interface CategoryService {\n" +
                "    CategoryDto save(CategoryDto categoryDto);\n" +
                "    CategoryDto update(CategoryDto categoryDto);\n" +
                "}\n");

        write("ecommerce-microservice-backend-app/product-service/src/main/java/com/selimhorri.app/service/impl/CategoryServiceImpl.java",
                "package com.selimhorri.app.service.impl;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "import org.springframework.transaction.annotation.Transactional;\n" +
                "import lombok.RequiredArgsConstructor;\n" +
                "import lombok.extern.slf4j.Slf4j;\n" +
                "import com.selimhorri.app.domain.Category;\n" +
                "import com.selimhorri.app.dto.CategoryDto;\n" +
                "import com.selimhorri.app.helper.CategoryMappingHelper;\n" +
                "import com.selimhorri.app.repository.CategoryRepository;\n" +
                "import com.selimhorri.app.service.CategoryService;\n" +
                "@Service\n" +
                "@Transactional\n" +
                "@Slf4j\n" +
                "@RequiredArgsConstructor\n" +
                "public class CategoryServiceImpl implements CategoryService {\n" +
                "    private final CategoryRepository categoryRepository;\n" +
                "    @Override\n" +
                "    public CategoryDto save(CategoryDto categoryDto) {\n" +
                "        return CategoryMappingHelper.map(this.categoryRepository.save(CategoryMappingHelper.map(categoryDto)));\n" +
                "    }\n" +
                "    @Override\n" +
                "    public CategoryDto update(CategoryDto categoryDto) {\n" +
                "        return CategoryMappingHelper.map(this.categoryRepository.save(CategoryMappingHelper.map(categoryDto)));\n" +
                "    }\n" +
                "}\n");

        // Module 2: proxy-client with unresolved Spring Security parents
        write("ecommerce-microservice-backend-app/proxy-client/src/main/java/com/selimhorri.app/business/auth/service/impl/UserDetailsServiceImpl.java",
                "package com.selimhorri.app.business.auth.service.impl;\n" +
                "class UserDetailsServiceImpl implements org.springframework.security.core.userdetails.UserDetailsService {}\n");

        write("ecommerce-microservice-backend-app/proxy-client/src/main/java/com/selimhorri.app/business/user/model/UserDetailsImpl.java",
                "package com.selimhorri.app.business.user.model;\n" +
                "class UserDetailsImpl implements org.springframework.security.core.userdetails.UserDetails {}\n");

        write("ecommerce-microservice-backend-app/proxy-client/src/main/java/com/selimhorri.app/security/SecurityConfig.java",
                "package com.selimhorri.app.security;\n" +
                "class SecurityConfig extends org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter {}\n");

        // Module 3: order-service with conflicting qualified class name ProductDto
        write("ecommerce-microservice-backend-app/order-service/src/main/java/com/selimhorri.app/dto/ProductDto.java",
                "package com.selimhorri.app.dto;\n" +
                "import lombok.*;\n" +
                "@Data\n" +
                "@Builder\n" +
                "public class ProductDto {\n" +
                "    private Integer orderProductId;\n" +
                "}\n");
    }

    @Test
    void productServiceImplFindById3564ResolvesCleanly() {
        String testSource = "package com.selimhorri.app.service.impl;\n" +
                "import static org.junit.jupiter.api.Assertions.assertEquals;\n" +
                "import static org.junit.jupiter.api.Assertions.assertThrows;\n" +
                "import static org.mockito.Mockito.verify;\n" +
                "import static org.mockito.Mockito.when;\n" +
                "import java.util.Optional;\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.junit.jupiter.api.extension.ExtendWith;\n" +
                "import org.mockito.InjectMocks;\n" +
                "import org.mockito.Mock;\n" +
                "import org.mockito.junit.jupiter.MockitoExtension;\n" +
                "import com.selimhorri.app.exception.wrapper.ProductNotFoundException;\n" +
                "import com.selimhorri.app.repository.ProductRepository;\n" +
                "@ExtendWith(MockitoExtension.class)\n" +
                "class ProductServiceImplFindByIdNotFoundTest {\n" +
                "    @Mock private ProductRepository productRepository;\n" +
                "    @InjectMocks private ProductServiceImpl productService;\n" +
                "    @Test\n" +
                "    void testFindById_whenProductNotExists_throwsProductNotFoundExceptionWithDynamicMessage() {\n" +
                "        final Integer productId = 999;\n" +
                "        when(this.productRepository.findById(productId)).thenReturn(Optional.empty());\n" +
                "        final ProductNotFoundException exception = assertThrows(ProductNotFoundException.class,\n" +
                "                () -> this.productService.findById(productId));\n" +
                "        assertEquals(\"Product with id: 999 not found\", exception.getMessage());\n" +
                "        verify(this.productRepository).findById(productId);\n" +
                "    }\n" +
                "}\n";

        var fp = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", testSource))),
                root, "ecommerce-microservice-backend-app/product-service");
        assertThat(fp.provenance()).doesNotContain("unresolved");
        assertThat(fp.reusable()).isTrue();
    }

    @Test
    void productServiceImplSave3565ResolvesCleanly() {
        String testSource = "package com.selimhorri.app.service.impl;\n" +
                "import static org.junit.jupiter.api.Assertions.assertEquals;\n" +
                "import static org.junit.jupiter.api.Assertions.assertNotNull;\n" +
                "import static org.junit.jupiter.api.Assertions.assertNull;\n" +
                "import static org.mockito.ArgumentMatchers.any;\n" +
                "import static org.mockito.Mockito.times;\n" +
                "import static org.mockito.Mockito.verify;\n" +
                "import static org.mockito.Mockito.when;\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.junit.jupiter.api.extension.ExtendWith;\n" +
                "import org.mockito.ArgumentCaptor;\n" +
                "import org.mockito.InjectMocks;\n" +
                "import org.mockito.Mock;\n" +
                "import org.mockito.junit.jupiter.MockitoExtension;\n" +
                "import com.selimhorri.app.domain.Category;\n" +
                "import com.selimhorri.app.domain.Product;\n" +
                "import com.selimhorri.app.dto.CategoryDto;\n" +
                "import com.selimhorri.app.dto.ProductDto;\n" +
                "import com.selimhorri.app.repository.ProductRepository;\n" +
                "@ExtendWith(MockitoExtension.class)\n" +
                "class ProductServiceImplSaveTest {\n" +
                "    @Mock private ProductRepository productRepository;\n" +
                "    @InjectMocks private ProductServiceImpl productService;\n" +
                "    @Test\n" +
                "    void testSave_whenValidProductDto_returnsMappedProductDtoFromSavedEntity() {\n" +
                "        final CategoryDto categoryDto = CategoryDto.builder()\n" +
                "                .categoryId(5)\n" +
                "                .categoryTitle(\"Thời trang\")\n" +
                "                .imageUrl(\"https://cdn.example.com/thoi-trang.jpg\")\n" +
                "                .build();\n" +
                "        final ProductDto productDto = ProductDto.builder()\n" +
                "                .productId(null)\n" +
                "                .productTitle(\"Áo thun nam\")\n" +
                "                .imageUrl(\"https://cdn.example.com/ao-thun.jpg\")\n" +
                "                .sku(\"SKU-001\")\n" +
                "                .priceUnit(19.99)\n" +
                "                .quantity(10)\n" +
                "                .categoryDto(categoryDto)\n" +
                "                .build();\n" +
                "        final Product savedProduct = Product.builder()\n" +
                "                .productId(1001)\n" +
                "                .productTitle(\"Áo thun nam\")\n" +
                "                .imageUrl(\"https://cdn.example.com/ao-thun.jpg\")\n" +
                "                .sku(\"SKU-001\")\n" +
                "                .priceUnit(19.99)\n" +
                "                .quantity(10)\n" +
                "                .category(Category.builder()\n" +
                "                        .categoryId(5)\n" +
                "                        .categoryTitle(\"Thời trang\")\n" +
                "                        .imageUrl(\"https://cdn.example.com/thoi-trang.jpg\")\n" +
                "                        .build())\n" +
                "                .build();\n" +
                "        when(this.productRepository.save(any(Product.class))).thenReturn(savedProduct);\n" +
                "        final ProductDto result = this.productService.save(productDto);\n" +
                "        assertNotNull(result);\n" +
                "        assertEquals(Integer.valueOf(1001), result.getProductId());\n" +
                "        final ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);\n" +
                "        verify(this.productRepository, times(1)).save(productCaptor.capture());\n" +
                "        final Product submitted = productCaptor.getValue();\n" +
                "        assertNotNull(submitted);\n" +
                "    }\n" +
                "}\n";

        var fp = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", testSource))),
                root, "ecommerce-microservice-backend-app/product-service");
        assertThat(fp.provenance()).doesNotContain("unresolved");
        assertThat(fp.reusable()).isTrue();
    }

    @Test
    void categoryServiceImplSave3567ResolvesCleanly() {
        String testSource = "package com.selimhorri.app.service.impl;\n" +
                "import static org.junit.jupiter.api.Assertions.assertEquals;\n" +
                "import static org.junit.jupiter.api.Assertions.assertNotNull;\n" +
                "import static org.mockito.ArgumentMatchers.any;\n" +
                "import static org.mockito.Mockito.times;\n" +
                "import static org.mockito.Mockito.verify;\n" +
                "import static org.mockito.Mockito.when;\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.junit.jupiter.api.extension.ExtendWith;\n" +
                "import org.mockito.ArgumentCaptor;\n" +
                "import org.mockito.InjectMocks;\n" +
                "import org.mockito.Mock;\n" +
                "import org.mockito.junit.jupiter.MockitoExtension;\n" +
                "import com.selimhorri.app.domain.Category;\n" +
                "import com.selimhorri.app.dto.CategoryDto;\n" +
                "import com.selimhorri.app.repository.CategoryRepository;\n" +
                "@ExtendWith(MockitoExtension.class)\n" +
                "class CategoryServiceImplSaveTest {\n" +
                "    @Mock private CategoryRepository categoryRepository;\n" +
                "    @InjectMocks private CategoryServiceImpl categoryService;\n" +
                "    @Test\n" +
                "    void testSave_whenNewCategoryDto_returnsMappedCategoryDtoWithGeneratedId() {\n" +
                "        final CategoryDto categoryDto = CategoryDto.builder()\n" +
                "                .categoryId(null)\n" +
                "                .categoryTitle(\"Điện tử\")\n" +
                "                .imageUrl(\"/images/electronics.png\")\n" +
                "                .parentCategoryDto(null)\n" +
                "                .build();\n" +
                "        final Category savedCategory = Category.builder()\n" +
                "                .categoryId(1)\n" +
                "                .categoryTitle(\"Điện tử\")\n" +
                "                .imageUrl(\"/images/electronics.png\")\n" +
                "                .parentCategory(new Category())\n" +
                "                .build();\n" +
                "        when(this.categoryRepository.save(any(Category.class))).thenReturn(savedCategory);\n" +
                "        final CategoryDto result = this.categoryService.save(categoryDto);\n" +
                "        assertNotNull(result);\n" +
                "        assertEquals(Integer.valueOf(1), result.getCategoryId());\n" +
                "        final ArgumentCaptor<Category> categoryCaptor = ArgumentCaptor.forClass(Category.class);\n" +
                "        verify(this.categoryRepository, times(1)).save(categoryCaptor.capture());\n" +
                "        final Category submitted = categoryCaptor.getValue();\n" +
                "        assertNotNull(submitted);\n" +
                "    }\n" +
                "}\n";

        var fp = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", testSource))),
                root, "ecommerce-microservice-backend-app/product-service");
        assertThat(fp.provenance()).doesNotContain("unresolved");
        assertThat(fp.reusable()).isTrue();
    }

    @Test
    void categoryServiceImplUpdate3568ResolvesCleanly() {
        String testSource = "package com.selimhorri.app.service.impl;\n" +
                "import static org.junit.jupiter.api.Assertions.assertEquals;\n" +
                "import static org.junit.jupiter.api.Assertions.assertNotNull;\n" +
                "import static org.mockito.ArgumentMatchers.any;\n" +
                "import static org.mockito.Mockito.never;\n" +
                "import static org.mockito.Mockito.times;\n" +
                "import static org.mockito.Mockito.verify;\n" +
                "import static org.mockito.Mockito.when;\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.junit.jupiter.api.extension.ExtendWith;\n" +
                "import org.mockito.ArgumentCaptor;\n" +
                "import org.mockito.ArgumentMatchers;\n" +
                "import org.mockito.InjectMocks;\n" +
                "import org.mockito.Mock;\n" +
                "import org.mockito.junit.jupiter.MockitoExtension;\n" +
                "import com.selimhorri.app.domain.Category;\n" +
                "import com.selimhorri.app.dto.CategoryDto;\n" +
                "import com.selimhorri.app.repository.CategoryRepository;\n" +
                "@ExtendWith(MockitoExtension.class)\n" +
                "class CategoryServiceImplUpdateTest {\n" +
                "    @Mock private CategoryRepository categoryRepository;\n" +
                "    @InjectMocks private CategoryServiceImpl categoryService;\n" +
                "    @Test\n" +
                "    void testUpdate_whenCategoryDtoProvided_savesWithoutExistenceCheck() {\n" +
                "        final CategoryDto categoryDto = CategoryDto.builder()\n" +
                "                .categoryId(5)\n" +
                "                .categoryTitle(\"Điện tử cập nhật\")\n" +
                "                .imageUrl(\"/images/electronics-v2.png\")\n" +
                "                .parentCategoryDto(null)\n" +
                "                .build();\n" +
                "        final Category savedCategory = Category.builder()\n" +
                "                .categoryId(5)\n" +
                "                .categoryTitle(\"Điện tử cập nhật\")\n" +
                "                .imageUrl(\"/images/electronics-v2.png\")\n" +
                "                .parentCategory(new Category())\n" +
                "                .build();\n" +
                "        when(this.categoryRepository.save(any(Category.class))).thenReturn(savedCategory);\n" +
                "        final CategoryDto result = this.categoryService.update(categoryDto);\n" +
                "        assertNotNull(result);\n" +
                "        assertEquals(Integer.valueOf(5), result.getCategoryId());\n" +
                "        final ArgumentCaptor<Category> categoryCaptor = ArgumentCaptor.forClass(Category.class);\n" +
                "        verify(this.categoryRepository, times(1)).save(categoryCaptor.capture());\n" +
                "        final Category submitted = categoryCaptor.getValue();\n" +
                "        assertNotNull(submitted);\n" +
                "    }\n" +
                "}\n";

        var fp = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", testSource))),
                root, "ecommerce-microservice-backend-app/product-service");
        assertThat(fp.provenance()).doesNotContain("unresolved");
        assertThat(fp.reusable()).isTrue();
    }

    @Test
    void negativeTestNonExistentApiOrMissingDependencyRemainsBlocked() {
        // Calling non-existent method on Product
        String invalidTest = "package com.selimhorri.app.service.impl;\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import com.selimhorri.app.domain.Product;\n" +
                "class BadTest {\n" +
                "    @Test void testBad() {\n" +
                "        new Product().nonExistentMethod();\n" +
                "    }\n" +
                "}\n";
        var fp = service.outputFingerprint(Map.of("unit_tests", List.of(Map.of("source_code", invalidTest))),
                root, "ecommerce-microservice-backend-app/product-service");
        assertThat(fp.reusable()).isFalse();
        assertThat(fp.provenance()).contains("unresolved");
    }

    private void write(String relativePath, String source) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
