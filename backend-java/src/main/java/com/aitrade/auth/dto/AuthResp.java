package com.aitrade.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResp {
    private Long id;
    private String username;
    private String nickname;
    private String token;
}
