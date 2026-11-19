package com.yanji;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Relative resource links follow the API namespace and configured deployment prefix. */
@Component
public class ApiLinks {
    private final HttpServletRequest request;
    private final String externalPrefix;

    public ApiLinks(HttpServletRequest request, @Value("${app.external-path-prefix:}") String externalPrefix) {
        if (!externalPrefix.isEmpty() && !externalPrefix.equals("/test")) {
            throw new IllegalArgumentException("Expected an empty API deployment prefix or /test");
        }
        this.request = request;
        this.externalPrefix = externalPrefix;
    }

    public String bird() { return base("/bird/v1"); }
    public String admin() { return base("/admin/v1"); }
    public String birdAdmin() { return base("/admin/v1/bird"); }

    private String base(String root) {
        return externalPrefix + request.getContextPath() + root;
    }
}
