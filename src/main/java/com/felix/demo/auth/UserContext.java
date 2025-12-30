package com.felix.demo.auth;

import java.util.Map;

public class UserContext {

    private static final ThreadLocal<Map<String, Object>> USER = new ThreadLocal<>();

    public static void set(Map<String, Object> user) {
        USER.set(user);
    }

    public static Map<String, Object> get() {
        return USER.get();
    }

    public static void clear() {
        USER.remove();
    }
}
