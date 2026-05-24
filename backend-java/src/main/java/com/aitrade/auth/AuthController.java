package com.aitrade.auth;

import com.aitrade.auth.dto.AuthResp;
import com.aitrade.auth.dto.LoginReq;
import com.aitrade.auth.dto.RegisterReq;
import com.aitrade.common.ApiException;
import com.aitrade.entity.User;
import com.aitrade.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AuthRateLimiter rateLimiter;

    @PostMapping("/register")
    public ResponseEntity<AuthResp> register(@Valid @RequestBody RegisterReq req,
                                              HttpServletRequest http) {
        guard("reg:" + clientIp(http));
        AuthResp resp = userService.register(req.getUsername(), req.getPassword(), req.getNickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/login")
    public AuthResp login(@Valid @RequestBody LoginReq req, HttpServletRequest http) {
        // 用 IP+username 双 key，避免同一 IP 多人共享时被牵连；又防穷举单一账户
        guard("login-ip:" + clientIp(http));
        guard("login-user:" + req.getUsername());
        return userService.login(req.getUsername(), req.getPassword());
    }

    @GetMapping("/me")
    public User me(@CurrentUser Long userId) {
        return userService.getById(userId);
    }

    private void guard(String key) {
        if (!rateLimiter.tryAcquire(key)) {
            throw new ApiException(429, "请求过于频繁，请稍后再试");
        }
    }

    /** 优先取反代头，回退到 remoteAddr。 */
    private static String clientIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) return h.split(",")[0].trim();
        h = req.getHeader("X-Real-IP");
        if (h != null && !h.isBlank()) return h.trim();
        return req.getRemoteAddr();
    }
}
