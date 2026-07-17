package com.hmdp.token.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RefreshRequest {
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
