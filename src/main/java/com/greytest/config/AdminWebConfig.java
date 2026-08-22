package com.greytest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Đăng ký RBAC chung cho mọi endpoint thuộc module admin. */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAuthorizationInterceptor interceptor;

    public AdminWebConfig(AdminAuthorizationInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/admin/**");
    }
}

