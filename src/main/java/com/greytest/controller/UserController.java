package com.greytest.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.UserQuotaResponse;
import com.greytest.entity.AuthUser;
import com.greytest.service.AuthService;
import com.greytest.service.UserService;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    /**
     * Nâng cấp tài khoản người dùng hiện tại lên gói PRO.
     */
    @PostMapping("/upgrade-pro")
    public UserQuotaResponse upgradeToPro(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return userService.upgradeToPro(user);
    }

    /**
     * Lấy thông tin hạn mức quota LLM của người dùng hiện tại.
     */
    @GetMapping("/quota")
    public UserQuotaResponse getQuota(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return userService.currentQuota(user);
    }
}
