package com.unisence.iot.admin.auth.service;

import com.unisence.iot.admin.auth.dto.LoginRequest;
import com.unisence.iot.admin.auth.dto.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthApplicationService {

    LoginVO login(LoginRequest request, HttpServletRequest servletRequest);
}
