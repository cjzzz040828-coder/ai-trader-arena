package com.aitrade.service;

import com.aitrade.auth.JwtUtil;
import com.aitrade.auth.dto.AuthResp;
import com.aitrade.common.ApiException;
import com.aitrade.entity.User;
import com.aitrade.mapper.UserMapper;
import com.aitrade.trade.TraderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TraderService traderService;

    @Transactional
    public AuthResp register(String username, String rawPassword, String nickname) {
        User existing = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (existing != null) {
            throw ApiException.badRequest("用户名已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        LocalDateTime now = LocalDateTime.now();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userMapper.insert(u);
        traderService.createDefault(u.getId());
        String token = jwtUtil.generate(u.getId(), u.getUsername());
        return new AuthResp(u.getId(), u.getUsername(), u.getNickname(), token);
    }

    public AuthResp login(String username, String rawPassword) {
        User u = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (u == null || !passwordEncoder.matches(rawPassword, u.getPassword())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        String token = jwtUtil.generate(u.getId(), u.getUsername());
        return new AuthResp(u.getId(), u.getUsername(), u.getNickname(), token);
    }

    public User getById(Long id) {
        User u = userMapper.selectById(id);
        if (u == null) throw ApiException.unauthorized("用户不存在");
        return u;
    }
}
