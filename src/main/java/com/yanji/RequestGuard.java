package com.yanji;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {
    private final Map<String, Integer> counts = new HashMap<>();
    private long minute;

    private synchronized boolean permit(String ip, int limit) {
        long current = System.currentTimeMillis() / 60000;
        if (current != minute) { counts.clear(); minute = current; }
        if (counts.size() >= 10000 && !counts.containsKey(ip)) return false;
        return counts.merge(ip, 1, Integer::sum) <= limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String route = request.getRequestURI().substring(request.getContextPath().length());
        boolean cloud = request.getMethod().equals("POST") && route.startsWith("/bird/v1/me/pets/");
        boolean limited = cloud || route.endsWith("/claim") || route.endsWith("/adopt") ||
            (request.getMethod().equals("POST") && (route.startsWith("/bird/v1/auth/") || route.equals("/admin/v1/login") ||
                route.equals("/bird/v1/checkins") || route.equals("/bird/v1/supplies/preview")));
        String ip = request.getRemoteAddr();
        if ((ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")) && request.getHeader("X-Real-IP") != null) ip = request.getHeader("X-Real-IP");
        if (limited && !permit((cloud ? "cloud:" : "consumer:") + ip, cloud ? 120 : 20)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"尝试过于频繁，请一分钟后再试\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
