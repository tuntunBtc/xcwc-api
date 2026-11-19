package com.yanji;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {
    public record PageView(@NotNull @Pattern(regexp = "home|city|shop|profile|adopt|photo|wardrobe|flock") String page,
                           @NotNull @Pattern(regexp = "[a-f0-9]{32}") String sessionId) {}

    private final AnalyticsService analytics;
    private final String adminToken;

    public AnalyticsController(AnalyticsService analytics, @Value("${app.analytics.admin-token:}") String adminToken) {
        this.analytics = analytics;
        this.adminToken = adminToken;
    }

    @PostMapping("/bird/v1/analytics/page-view")
    public ResponseEntity<Void> pageView(@Valid @RequestBody PageView body, HttpServletRequest request) {
        analytics.record(body.page(), body.sessionId(), request.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/v1/bird/analytics")
    public ResponseEntity<?> summary(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to) {
        ResponseEntity<?> denied = authorize(authorization);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(analytics.summary(from, to));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_DATE_RANGE", "message", error.getMessage()));
        }
    }

    private ResponseEntity<?> authorize(String authorization) {
        if (adminToken.length() < 32) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "ANALYTICS_ADMIN_NOT_CONFIGURED", "message", "后台密钥尚未配置"));
        }
        String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        boolean matches = MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
        if (!matches) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header("WWW-Authenticate", "Bearer realm=\"xcwc-admin\"")
            .body(Map.of("code", "ANALYTICS_ADMIN_UNAUTHORIZED", "message", "后台密钥不正确"));
        return null;
    }
}
