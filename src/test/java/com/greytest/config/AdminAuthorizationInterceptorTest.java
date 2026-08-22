package com.greytest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AdminAuthorizationInterceptorTest {

    @Test
    void rejectsAuthenticatedUserWithoutAdminRole() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthUser user = user(UserRole.USER);
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(auth.currentUser("Bearer token")).thenReturn(user);

        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(auth);

        assertThatThrownBy(() -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("quyền");
    }

    @Test
    void allowsAdminAndSharesIdentityWithController() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthUser admin = user(UserRole.ADMIN);
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(auth.currentUser("Bearer token")).thenReturn(admin);

        boolean allowed = new AdminAuthorizationInterceptor(auth)
                .preHandle(request, mock(HttpServletResponse.class), new Object());

        assertThat(allowed).isTrue();
        org.mockito.Mockito.verify(request).setAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, admin);
    }

    private AuthUser user(UserRole role) {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }
}
