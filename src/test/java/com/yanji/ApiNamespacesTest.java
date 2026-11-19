package com.yanji;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiNamespacesTest {
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;

    @Test void everyDeclaredOperationHasOneCanonicalHandler() throws Exception {
        Map<String, HandlerMethod> registered = new HashMap<>();
        mappings.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType().getPackageName().equals("com.yanji")) {
                for (String path : mapping.getPatternValues()) {
                    for (var verb : mapping.getMethodsCondition().getMethods()) {
                        registered.put(verb.name().toLowerCase() + " " + path, method);
                    }
                }
            }
        });
        var options = new LoaderOptions();
        options.setMaxAliasesForCollections(2000);
        var contract = new Yaml(options).loadAs(Files.readString(Path.of("openapi.yaml")), Map.class);
        var paths = (Map<?, ?>)contract.get("paths");
        Map<String, HandlerMethod> checked = new HashMap<>();
        paths.forEach((path, spec) -> {
            var item = (Map<?, ?>)spec;
            assertThat(item.containsKey("x-legacy-path") || item.containsKey("x-canonical-path")).isFalse();
            for (String verb : new String[]{"get","post","put","delete","patch"}) {
                if (!item.containsKey(verb)) continue;
                String key = verb + " " + path;
                assertThat(registered.get(key)).as(key).isNotNull();
                checked.put(key, registered.get(key));
            }
        });
        assertThat(checked.keySet()).containsExactlyInAnyOrderElementsOf(registered.keySet());
    }

    @Test void retiredRoutesAreRejectedByJava() throws Exception {
        for (String root : new String[]{"/api","/test/api"}) {
            for (String path : new String[]{"/health","/me/pets","/admin/accounts","/content",
                    "/content/assets/00000000000000000000000000000000","/content/preview"}) {
                mvc.perform(get(root+path)).andExpect(status().isNotFound());
            }
            mvc.perform(post(root+"/me/pets/adopt").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
            mvc.perform(post(root+"/admin/login").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        }
    }

    @Test void administrativeAndPreviewOperationsCannotBeReachedThroughTheBirdNamespace() throws Exception {
        for (String path : new String[]{"/bird/v1/admin/accounts", "/bird/v1/content/preview",
                "/bird/v1/admin/brand-ips", "/admin/v1/brand-ips", "/test/bird/v1/health"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        for (String path : new String[]{"/admin/v1/accounts", "/admin/v1/bird/brand-ips",
                "/admin/v1/bird/content/preview", "/bird/v1/me/pets"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        }
    }

    @Test void canonicalLoginRetainsRateLimitAndMediaTypeValidation() throws Exception {
        for (int i=0; i<21; i++) {
            String path = "/bird/v1" + "/auth/login";
            var result = mvc.perform(post(path).with(request -> {
                request.setRemoteAddr("192.0.2.219"); return request;
            }).contentType("text/plain").content("invalid"));
            if (i<20) result.andExpect(status().isUnsupportedMediaType());
            else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
    }
}
