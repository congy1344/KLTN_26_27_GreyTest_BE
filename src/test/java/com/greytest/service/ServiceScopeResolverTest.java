package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.enums.ClassType;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;

@ExtendWith(MockitoExtension.class)
class ServiceScopeResolverTest {

    @Mock private ProjectRepository projects;
    @Mock private JavaClassRepository classes;
    @Mock private JavaMethodRepository methods;
    @InjectMocks private ServiceScopeResolver resolver;

    @Test
    void discoversRootAndNestedModulesFromSourcePaths() {
        JavaClass rootClass = javaClass(1L, "src/main/java/com/example/RootService.java");
        JavaClass authClass = javaClass(2L, "auth-service\\src\\main\\java\\com\\example\\AuthService.java");
        JavaMethod rootMethod = javaMethod(11L, 1L);
        JavaMethod authMethod = javaMethod(22L, 2L);

        when(projects.existsById(7L)).thenReturn(true);
        when(classes.findByProjectId(7L)).thenReturn(List.of(authClass, rootClass));
        when(methods.findByClassIdIn(List.of(1L))).thenReturn(List.of(rootMethod));
        when(methods.findByClassIdIn(List.of(2L))).thenReturn(List.of(authMethod));

        var scopes = resolver.listScopes(7L);

        assertThat(scopes).extracting(ServiceScopeResolver.ServiceScope::servicePath)
                .containsExactly(".", "auth-service");
        assertThat(scopes.get(0).methodIds()).containsExactly(11L);
        assertThat(scopes.get(1).methodIds()).containsExactly(22L);
    }

    @Test
    void excludesInfrastructureModulesWithoutServiceClasses() {
        JavaClass configClass = javaClass(1L, "config/src/main/java/com/example/ConfigApplication.java");
        configClass.setClassType(ClassType.OTHER);
        JavaClass accountClass = javaClass(2L, "account-service/src/main/java/com/example/AccountService.java");
        accountClass.setClassType(ClassType.SERVICE);
        JavaMethod accountMethod = javaMethod(22L, 2L);

        when(projects.existsById(7L)).thenReturn(true);
        when(classes.findByProjectId(7L)).thenReturn(List.of(configClass, accountClass));
        when(methods.findByClassIdIn(List.of(2L))).thenReturn(List.of(accountMethod));

        assertThat(resolver.listScopes(7L)).extracting(ServiceScopeResolver.ServiceScope::servicePath)
                .containsExactly("account-service");
    }

    @Test
    void requiresExplicitSelectionForMultiModuleProject() {
        when(projects.existsById(7L)).thenReturn(true);
        when(classes.findByProjectId(7L)).thenReturn(List.of(
                javaClass(1L, "account-service/src/main/java/com/example/Account.java"),
                javaClass(2L, "auth-service/src/main/java/com/example/Auth.java")));
        when(methods.findByClassIdIn(List.of(1L))).thenReturn(List.of());
        when(methods.findByClassIdIn(List.of(2L))).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(7L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("servicePath");

        assertThat(resolver.resolve(7L, "./auth-service/").servicePath()).isEqualTo("auth-service");
    }

    @Test
    void keepsSingleScopeForMonolithicProjectWithMultipleServiceClasses() {
        JavaClass doctorClass = javaClass(1L, "src/main/java/com/example/DoctorService.java");
        doctorClass.setClassName("DoctorService");
        JavaClass patientClass = javaClass(2L, "src/main/java/com/example/PatientService.java");
        patientClass.setClassName("PatientService");
        JavaMethod doctorMethod = javaMethod(11L, 1L);
        JavaMethod patientMethod = javaMethod(22L, 2L);

        when(projects.existsById(7L)).thenReturn(true);
        when(classes.findByProjectId(7L)).thenReturn(List.of(doctorClass, patientClass));
        when(methods.findByClassIdIn(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(doctorMethod, patientMethod));

        var scopes = resolver.listScopes(7L);

        assertThat(scopes).extracting(ServiceScopeResolver.ServiceScope::servicePath)
                .containsExactly(".");
        assertThat(scopes.get(0).methodIds()).containsExactlyInAnyOrder(11L, 22L);
    }

    @Test
    void keepsSingleScopeWhenOnlyOneServiceClassExistsInSingleModule() {
        JavaClass userClass = javaClass(1L, "src/main/java/com/example/UserService.java");
        userClass.setClassName("UserService");
        JavaMethod userMethod = javaMethod(11L, 1L);

        when(projects.existsById(7L)).thenReturn(true);
        when(classes.findByProjectId(7L)).thenReturn(List.of(userClass));
        when(methods.findByClassIdIn(List.of(1L))).thenReturn(List.of(userMethod));

        var scopes = resolver.listScopes(7L);

        assertThat(scopes).extracting(ServiceScopeResolver.ServiceScope::servicePath)
                .containsExactly(".");
        assertThat(scopes.get(0).methodIds()).containsExactly(11L);
    }

    private JavaClass javaClass(Long id, String filePath) {
        JavaClass javaClass = new JavaClass();
        javaClass.setId(id);
        javaClass.setProjectId(7L);
        javaClass.setFilePath(filePath);
        javaClass.setClassName(filePath.substring(filePath.lastIndexOf('/') + 1).replace(".java", ""));
        javaClass.setClassType(ClassType.SERVICE);
        return javaClass;
    }

    private JavaMethod javaMethod(Long id, Long classId) {
        JavaMethod method = new JavaMethod();
        method.setId(id);
        method.setClassId(classId);
        return method;
    }
}
