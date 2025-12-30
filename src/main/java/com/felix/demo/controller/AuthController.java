package com.felix.demo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 前端（Vue / React）
 * │
 * │ ① 请求登录
 * ▼
 * 后端（Auth API）
 * │
 * │ ② 重定向到 Keycloak
 * ▼
 * Keycloak
 * │
 * │ ③ 登录成功 → code
 * ▼
 * 后端 /auth/callback
 * │
 * │ ④ 用 code 换 token
 * │ ⑤ 存 Redis
 * ▼
 * 前端（拿到 sessionId / accessToken）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Value("${keycloak.auth-server-url}")
    private String authServer;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.redirect-uri}")
    private String redirectUri;

    private final RestTemplate restTemplate = new RestTemplate();
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 1.登录
     */
    @GetMapping("/login")
    public void login(HttpServletResponse response) throws Exception {
        String url = authServer + "/realms/" + realm + "/protocol/openid-connect/auth"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&scope=openid profile email"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        response.sendRedirect(url);
    }

    /**
     * 2.回调
     * <p>
     * 浏览器
     * ↓
     * 前端（Vue / React）
     * ↓  跳转
     * Keycloak 登录页
     * ↓
     * redirect_uri = 前端地址
     * ↓
     * 前端拿到 code
     * ↓
     * POST /api/auth/callback (后端)
     * ↓
     * 后端换 token + 存 Redis
     * ↓
     * 返回你自己的 token
     */
    @GetMapping("/callback")
    public Map<String, Object> callback(@RequestParam String code) {

        // 1. 换 token
        String tokenUrl = authServer + "/realms/" + realm + "/protocol/openid-connect/token";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<String, Object> tokenResp = restTemplate.postForObject(
                tokenUrl,
                new HttpEntity<>(body, headers),
                Map.class
        );

        String accessToken = (String) tokenResp.get("access_token");
        String refreshToken = (String) tokenResp.get("refresh_token");
        Integer refreshExpiresIn = (Integer) tokenResp.get("refresh_expires_in");
        String idToken = (String) tokenResp.get("id_token");

        // 2. 解析用户
        Map<String, Object> claims = parseIdToken(idToken);
        String userId = (String) claims.get("sub");
        String username = (String) claims.get("preferred_username");
        String email = (String) claims.get("email");

        // 3. 生成你自己的 token
        String loginToken = generateToken();

        // 4. 存 Redis（统一结构）
        String redisKey = "login:token:" + loginToken;
        redisTemplate.opsForHash().putAll(redisKey, Map.of(
                "userId", userId,
                "username", username,
                "email", email,
                "accessToken", accessToken,
                "refreshToken", refreshToken
        ));
        redisTemplate.expire(redisKey, refreshExpiresIn, TimeUnit.SECONDS);

        // 5. 返回前端
        return Map.of(
                "token", loginToken,
                "expiresIn", refreshExpiresIn,
                "user", Map.of(
                        "userId", userId,
                        "username", username,
                        "email", email
                )
        );
    }


    /**
     * 它由 三个部分 用点 . 分隔：
     * Header（parts[0]）：Base64Url 编码的 JSON，描述算法（如 RS256）和类型。
     * Payload（parts[1]）：Base64Url 编码的 JSON，这就是我们想要的用户信息（claims），如 sub（用户ID）、name、email、realm_access.roles（角色）等。
     * Signature（parts[2]）：签名部分，用于验证完整性。
     */
    public Map<String, Object> parseIdToken(String idToken) {
        String[] parts = idToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        Map map = null;
        try {
            map = objectMapper.readValue(payload, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return map;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }


    /**
     * 3.刷新 token
     * 前端
     * ↓
     * POST /refresh
     * ↓
     * 后端从 Header 拿 accessToken
     * ↓
     * Redis 找到 refreshToken
     * ↓
     * 调用 Keycloak /token（grant_type=refresh_token）
     * ↓
     * 更新 Redis
     * ↓
     * 返回新 accessToken
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestHeader("Authorization") String authorization) {

        String loginToken = authorization.replace("Bearer ", "");
        String oldKey = "login:token:" + loginToken;

        // 1️⃣ 从 Hash 取 refreshToken
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(oldKey);
        if (entries == null || entries.isEmpty()) {
            throw new RuntimeException("登录已过期，请重新登录");
        }
        String refreshToken = entries.get("refreshToken").toString();

        // 2️⃣ 刷新 Keycloak token
        String tokenUrl = authServer + "/realms/" + realm + "/protocol/openid-connect/token";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<String, Object> kcResp = restTemplate.postForObject(
                tokenUrl,
                new HttpEntity<>(body, headers),
                Map.class
        );

        if (kcResp == null || kcResp.get("refresh_token") == null) {
            throw new RuntimeException("refresh_token 已失效");
        }

        String newRefreshToken = (String) kcResp.get("refresh_token");
        Integer refreshExpiresIn = (Integer) kcResp.get("refresh_expires_in");

        //生成新的 loginToken
        String newLoginToken = generateToken();

        //写入新 token
        String newKey = "login:token:" + newLoginToken;

        entries.put("refreshToken", newRefreshToken);
        redisTemplate.opsForHash().putAll(newKey, entries);
        redisTemplate.expire(newKey, refreshExpiresIn, TimeUnit.SECONDS);
        //老token 延迟30秒过期
        redisTemplate.expire(oldKey, 30, TimeUnit.SECONDS);

        return Map.of(
                "token", newLoginToken,
                "expiresIn", refreshExpiresIn
        );
    }


    /**
     * 4.登出
     */
    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        String loginToken = authorization.replace("Bearer ", "");
        String redisKey = "login:token:" + loginToken;

        // 1️⃣ 修正：从 Hash 结构中获取 refreshToken
        Object refreshTokenObj = redisTemplate.opsForHash().get(redisKey, "refreshToken");

        if (refreshTokenObj != null) {
            String refreshToken = refreshTokenObj.toString();
            try {
                // 2️⃣ 通知 Keycloak 注销
                String logoutUrl = authServer + "/realms/" + realm + "/protocol/openid-connect/logout";

                MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                body.add("client_id", clientId);
                body.add("client_secret", clientSecret);
                body.add("refresh_token", refreshToken);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                // 使用 postForEntity 或 exchange 确保请求发出
                restTemplate.postForEntity(logoutUrl, new HttpEntity<>(body, headers), String.class);
            } catch (Exception e) {
                // 生产环境建议记录日志，但不阻断 Redis 的清理工作
                log.error("Keycloak logout failed: {}", e.getMessage());
            }
        }

        // 3️⃣ 清理 Redis（无论 Keycloak 那边是否注销成功，本地会话都应销毁）
        redisTemplate.delete(redisKey);
    }

}
