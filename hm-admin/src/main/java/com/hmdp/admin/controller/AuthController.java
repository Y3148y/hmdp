package com.hmdp.admin.controller;

import com.hmdp.admin.dto.LoginRequest;
import com.hmdp.admin.dto.LoginResponse;
import com.hmdp.admin.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthenticationManager authenticationManager;

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest request) {

        //1. doFilterInternal

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(), request.getPassword()));

        //2. loadUserByUsername

        String token = jwtTokenProvider.generateToken(auth);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "登录成功");
        result.put("data", LoginResponse.of(token, auth.getName()));
        return result;
    }
}
