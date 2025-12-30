package com.felix.demo.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.view.RedirectView;
import jakarta.servlet.http.HttpSession;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class LoginController {

    @Value("${keycloak.auth-server-url}")
    private String authServerUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.redirect-uri}")
    private String redirectUri;

    private final RestTemplate restTemplate = new RestTemplate();

    // 发起登录：重定向到 Keycloak
    @GetMapping("/login")
    public RedirectView login() {
        String authUrl = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/auth"
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=openid%20profile%20email"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&state=some-random-state"  // 可选，防 CSRF
                + "&nonce=some-nonce";  // 可选

        return new RedirectView(authUrl);
    }

    // 回调：交换 code 获取 token
    @GetMapping("/callback")
    public String callback(@RequestParam("code") String code, HttpSession session) {
        String tokenUrl = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);
        Map<String, Object> tokenResponse = response.getBody();

        if (tokenResponse != null) {
            String accessToken = (String) tokenResponse.get("access_token");
            String idToken = (String) tokenResponse.get("id_token");  // JWT，包含用户信息
            String refreshToken = (String) tokenResponse.get("refresh_token");

            // 存储到 session
            session.setAttribute("access_token", accessToken);
            session.setAttribute("id_token", idToken);
            session.setAttribute("refresh_token", refreshToken);

            // 可选：调用 UserInfo endpoint 获取更多信息
            // HttpHeaders userHeaders = new HttpHeaders();
            // userHeaders.setBearerAuth(accessToken);
            // ResponseEntity<Map> userInfo = restTemplate.exchange(userInfoUrl, HttpMethod.GET, new HttpEntity<>(userHeaders), Map.class);

            return "登录成功！用户信息从 ID Token 获取: " + idToken;  // 或重定向到首页
        }

        return "登录失败";
    }

    // 登出
    @GetMapping("/logout")
    public RedirectView logout(HttpSession session) {
        session.invalidate();  // 清除本地 session

        String logoutUrl = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/logout"
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&post_logout_redirect_uri=" + URLEncoder.encode("http://localhost:9000/", StandardCharsets.UTF_8);

        return new RedirectView(logoutUrl);
    }

    // 示例：受保护页面
    @GetMapping("/home")
    public String home(HttpSession session) {
        String accessToken = (String) session.getAttribute("access_token");
        if (accessToken == null) {
            return "未登录，请 <a href='/login'>登录</a>";
        }
        return "欢迎！你的 access_token: " + accessToken.substring(0, 20) + "... <a href='/logout'>登出</a>";
    }
}