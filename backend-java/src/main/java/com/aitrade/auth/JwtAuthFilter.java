package com.aitrade.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> WHITELIST = List.of(
            "/api/auth/register",
            "/api/auth/login",
            // 健康检查接口对外探活用，与 README 中"不带 token 也能调"的承诺一致；
            // 转发的是 Python 网关 /health 状态（mootdx_ready / market_status / 服务器时间），
            // 不含业务数据，所以放行安全。
            "/api/quote/gateway-health"
    );

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) || isWhitelisted(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }

        String token = null;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else if (isSseEndpoint(req.getRequestURI())) {
            // SSE：EventSource 无法加自定义 header，仅对 SSE 端点 fallback 到 query string
            String qt = req.getParameter("token");
            if (qt != null && !qt.isBlank()) token = qt;
        }
        if (token == null) {
            writeUnauthorized(res, "missing token");
            return;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            req.setAttribute(ATTR_USER_ID, Long.valueOf(claims.getSubject()));
            req.setAttribute(ATTR_USERNAME, claims.get("username", String.class));
        } catch (Exception e) {
            log.debug("jwt parse failed: {}", e.getMessage());
            writeUnauthorized(res, "invalid token");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean isWhitelisted(String uri) {
        for (String p : WHITELIST) {
            if (MATCHER.match(p, uri)) return true;
        }
        return false;
    }

    /** SSE 端点路径白名单：这些端点允许通过 ?token= query 鉴权（EventSource 无法加 header）。 */
    private static boolean isSseEndpoint(String uri) {
        return uri.endsWith("/llm-stream")
                || uri.endsWith("/llm-activity/stream")
                || uri.endsWith("/analyst/stream");
    }

    private void writeUnauthorized(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":401,\"message\":\"" + msg + "\"}");
    }
}
