package com.hmdp.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String tokenType;
    private String username;

    public static LoginResponse of(String token, String username) {
        return new LoginResponse(token, "Bearer", username);
    }
}
