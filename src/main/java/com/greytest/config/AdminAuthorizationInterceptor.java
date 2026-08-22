package com.greytest.config;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Chặn tập trung toàn bộ API admin, tránh rải logic RBAC trong từng controller. */
@Component
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    public static final String ADMIN_USER_ATTRIBUTE = "greytestAdminUser";
    private final AuthService authService;

    public AdminAuthorizationInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthUser user = authService.currentUser(request.getHeader("Authorization"));
        if (user.getRole() != UserRole.ADMIN) {
            throw new AuthException("ADMIN_REQUIRED", "Bạn không có quyền truy cập trang quản trị", HttpStatus.FORBIDDEN);
        }
        request.setAttribute(ADMIN_USER_ATTRIBUTE, user);
        return true;
    }
}

