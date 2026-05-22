package com.aitrade.auth;

import com.aitrade.auth.dto.AuthResp;
import com.aitrade.auth.dto.LoginReq;
import com.aitrade.auth.dto.RegisterReq;
import com.aitrade.entity.User;
import com.aitrade.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<AuthResp> register(@Valid @RequestBody RegisterReq req) {
        AuthResp resp = userService.register(req.getUsername(), req.getPassword(), req.getNickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/login")
    public AuthResp login(@Valid @RequestBody LoginReq req) {
        return userService.login(req.getUsername(), req.getPassword());
    }

    @GetMapping("/me")
    public User me(@CurrentUser Long userId) {
        return userService.getById(userId);
    }
}
