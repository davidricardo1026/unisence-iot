package com.unisence.iot.admin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.auth.config.AuthConfigProperties;
import com.unisence.iot.admin.auth.dto.LoginRequest;
import com.unisence.iot.admin.auth.dto.LoginVO;
import com.unisence.iot.admin.auth.service.AuthApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthConfigProperties authConfigProperties;
    private final AuthApplicationService authApplicationService;

    @GetMapping("/enabled-types")
    public List<String> getEnabledAuthTypes() {
        return authConfigProperties.getEnabledTypes();
    }

    @PostMapping("/login")
    public LoginVO login(@RequestBody @Valid LoginRequest request, HttpServletRequest servletRequest) {
        return authApplicationService.login(request, servletRequest);
    }

    @PostMapping("/logout")
    public void logout() {
        StpUtil.logout();
    }
}
