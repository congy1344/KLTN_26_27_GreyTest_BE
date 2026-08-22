package com.greytest.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.config.AdminAuthorizationInterceptor;
import com.greytest.dto.admin.AdminDtos.ActivityDto;
import com.greytest.dto.admin.AdminDtos.HealthDto;
import com.greytest.dto.admin.AdminDtos.OverviewDto;
import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.QuotaDto;
import com.greytest.dto.admin.AdminDtos.TopUserDto;
import com.greytest.dto.admin.AdminDtos.TrendPointDto;
import com.greytest.dto.admin.AdminDtos.UserDetailDto;
import com.greytest.dto.admin.AdminDtos.UserSummaryDto;
import com.greytest.dto.admin.UpdateQuotaRequest;
import com.greytest.dto.admin.UpdateUserRoleRequest;
import com.greytest.dto.admin.UpdateUserStatusRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AdminService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public PageDto<UserSummaryDto> users(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listUsers(search, role, enabled, page, size, sort, direction);
    }

    @GetMapping("/users/{userId}")
    public UserDetailDto user(@PathVariable Long userId) {
        return service.userDetail(userId);
    }

    @PatchMapping("/users/{userId}/status")
    public UserSummaryDto status(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return service.updateStatus(admin, userId, request.enabled());
    }

    @PatchMapping("/users/{userId}/role")
    public UserSummaryDto role(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        return service.updateRole(admin, userId, request.role());
    }

    @PatchMapping("/users/{userId}/quota")
    public QuotaDto quota(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateQuotaRequest request) {
        return service.updateQuota(admin, userId, request.quotaLimit());
    }

    @GetMapping("/activity")
    public PageDto<ActivityDto> activity(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) ActivityAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.activities(userId, action, from, to, page, size);
    }

    @GetMapping("/stats/overview")
    public OverviewDto overview() {
        return service.overview();
    }

    @GetMapping("/stats/trend")
    public List<TrendPointDto> trend(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) ActivityAction action) {
        return service.trend(days, granularity, action);
    }

    @GetMapping("/stats/top-users")
    public List<TopUserDto> topUsers(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return service.topUsers(days, limit);
    }

    @GetMapping("/health")
    public HealthDto health() {
        return service.health();
    }
}
