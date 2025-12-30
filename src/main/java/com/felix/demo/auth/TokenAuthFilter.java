package com.felix.demo.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;

    public TokenAuthFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws IOException, ServletException {

        String uri = request.getRequestURI();

        // 放行登录接口
        if (uri.startsWith("/api/v1/auth")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(response, "缺少 Token");
            return;
        }

        String token = authHeader.substring(7);
        String redisKey = "login:token:" + token;

        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(redisKey);

        if (userMap == null || userMap.isEmpty()) {
            unauthorized(response, "登录已过期");
            return;
        }

        // 放入上下文
        UserContext.set((Map) userMap);

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":401,\"msg\":\"" + msg + "\"}"
        );
    }
}
