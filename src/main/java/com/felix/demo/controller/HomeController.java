package com.felix.demo.controller;

import com.felix.demo.auth.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {

    @GetMapping("/user")
    public Map<String, Object> user() {
        return UserContext.get();
    }
}
