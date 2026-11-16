package com.yanji;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.WebRequest;

/** Public, read-only catalog. No account, order or payment operations. */
@RestController
@RequestMapping(value = "/api/shop", produces = "application/json")
public class ShopController {
    private static final String PUBLIC_CACHE = "public, max-age=60, must-revalidate";
    private final ShopCatalogService catalog;
    public ShopController(ShopCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/products")
    public ResponseEntity<ShopCatalogService.Page> products(@RequestParam(required = false) String city,
        @RequestParam(defaultValue = "default") String sort, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize, WebRequest request, HttpServletResponse response) {
        return cached(catalog.products(city, sort, page, pageSize), request, response);
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ShopCatalogService.Product> product(@PathVariable String id, WebRequest request, HttpServletResponse response) {
        return cached(catalog.product(id), request, response);
    }

    @GetMapping("/banners")
    public ResponseEntity<ShopCatalogService.BannerPage> banners(WebRequest request, HttpServletResponse response) {
        return cached(catalog.banners(), request, response);
    }

    private <T> ResponseEntity<T> cached(T body, WebRequest request, HttpServletResponse response) {
        // Resolve and validate before checking conditions: a stale tag must not conceal invalid queries or removals.
        String tag = catalog.representationTag(body);
        // Replace the global safety default, rather than append a contradictory second Cache-Control value.
        response.setHeader("Cache-Control", PUBLIC_CACHE);
        response.setHeader("ETag", tag);
        String[] conditions = request.getHeaderValues("If-None-Match");
        // The body has already resolved to an existing representation, including empty lists.
        boolean existing = conditions != null && Arrays.stream(conditions).anyMatch(value -> value.strip().equals("*"));
        if (existing || request.checkNotModified(tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(ShopCatalogService.ShopFailure.class)
    ResponseEntity<?> failure(ShopCatalogService.ShopFailure error, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(error.status).body(Map.of("code", error.code, "message", error.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> invalidQuery(MethodArgumentTypeMismatchException error, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_SHOP_QUERY", "message", "页码和每页数量须为整数"));
    }
}
