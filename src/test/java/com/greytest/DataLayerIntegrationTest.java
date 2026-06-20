package com.greytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.MethodParam;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.SourceType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Kiểm tra data layer trên Postgres thật (docker-compose, localhost:5432):
 * Flyway chạy hết migration, Hibernate validate entity khớp schema, JSONB round-trip đúng.
 *
 * <p>Yêu cầu: chạy {@code docker compose up -d postgres} trước khi test.
 * Mỗi test bọc trong transaction và rollback nên không để lại dữ liệu.
 */
@SpringBootTest
@Transactional
class DataLayerIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private JavaClassRepository javaClassRepository;
    @Autowired
    private JavaMethodRepository javaMethodRepository;
    @Autowired
    private BusinessRuleRepository businessRuleRepository;
    @PersistenceContext
    private EntityManager em;

    @Test
    void savesProjectWithTimestamp() {
        Project saved = projectRepository.save(newProject(ProjectStatus.UPLOADED));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(projectRepository.findByStatus(ProjectStatus.UPLOADED))
                .extracting(Project::getId)
                .contains(saved.getId());
    }

    @Test
    void roundTripsJsonbParameters() {
        Project project = projectRepository.save(newProject(ProjectStatus.ANALYZED));

        JavaClass javaClass = new JavaClass();
        javaClass.setProjectId(project.getId());
        javaClass.setClassName("UserService");
        javaClass = javaClassRepository.save(javaClass);

        JavaMethod method = new JavaMethod();
        method.setClassId(javaClass.getId());
        method.setMethodName("createUser");
        method.setParameters(List.of(new MethodParam("name", "String"), new MethodParam("age", "int")));
        method.setThrowsList(List.of("IllegalArgumentException"));
        javaMethodRepository.save(method);

        // Flush + clear để lần đọc sau lấy từ DB, kiểm tra JSONB serialize/deserialize thật.
        em.flush();
        em.clear();

        JavaMethod loaded = javaMethodRepository.findByClassId(javaClass.getId()).get(0);
        assertThat(loaded.getParameters()).containsExactly(
                new MethodParam("name", "String"), new MethodParam("age", "int"));
        assertThat(loaded.getThrowsList()).containsExactly("IllegalArgumentException");
    }

    @Test
    void filtersBusinessRulesByStatusAndModified() {
        Project project = projectRepository.save(newProject(ProjectStatus.ANALYZED));

        businessRuleRepository.save(newRule(project.getId(), "BR-001", ReviewStatus.APPROVED, false));
        businessRuleRepository.save(newRule(project.getId(), "BR-002", ReviewStatus.APPROVED, true));

        assertThat(businessRuleRepository.findByProjectIdAndStatus(project.getId(), ReviewStatus.APPROVED))
                .hasSize(2);
        assertThat(businessRuleRepository.findModifiedRules(project.getId()))
                .extracting(BusinessRule::getRuleCode)
                .containsExactly("BR-002");
    }

    private Project newProject(ProjectStatus status) {
        Project p = new Project();
        p.setName("demo-app");
        p.setSourceType(SourceType.ZIP);
        p.setStatus(status);
        return p;
    }

    private BusinessRule newRule(Long projectId, String code, ReviewStatus status, boolean modified) {
        BusinessRule br = new BusinessRule();
        br.setProjectId(projectId);
        br.setRuleCode(code);
        br.setDescription("rule " + code);
        br.setSource(RuleSource.AI_GENERATED);
        br.setStatus(status);
        br.setIsModified(modified);
        return br;
    }
}
