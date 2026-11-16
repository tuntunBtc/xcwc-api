package com.yanji;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full application wiring, unlike the catalog parser's standalone controller tests. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {
    @Autowired MockMvc mvc;

    @Test void onlyValidatedShopReadsOverrideTheGlobalNoStorePolicy() throws Exception {
        for(String path:new String[]{"/api/shop/products", "/api/shop/products/hangzhou-xihu", "/api/shop/banners"}) {
            String tag=mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(header().stringValues("Cache-Control","public, max-age=60, must-revalidate"))
                .andReturn().getResponse().getHeader("ETag");
            mvc.perform(get(path).header("If-None-Match",tag)).andExpect(status().isNotModified())
                .andExpect(content().string(""))
                .andExpect(header().stringValues("Cache-Control","public, max-age=60, must-revalidate"));
        }
        mvc.perform(get("/api/shop/products").param("page","0").header("If-None-Match","*"))
            .andExpect(status().isBadRequest()).andExpect(header().stringValues("Cache-Control","no-store"));
        mvc.perform(get("/api/shop/products/missing").header("If-None-Match","*"))
            .andExpect(status().isNotFound()).andExpect(header().stringValues("Cache-Control","no-store"));
        for(String path:new String[]{"/api/health", "/api/birds/by-code/HZ-HY-1", "/api/me/birds", "/api/me/checkins", "/api/auth/session"}) {
            mvc.perform(get(path).header("If-None-Match","*"))
                .andExpect(header().stringValues("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isNotEqualTo(304));
        }
        for(String path:new String[]{"/api/shop/products", "/api/shop/products/hangzhou-xihu", "/api/shop/banners"}) {
            mvc.perform(get(path).accept("application/xml").header("If-None-Match","*"))
                .andExpect(status().isNotAcceptable()).andExpect(header().stringValues("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("ETag"));
        }
    }

    @Test void publicShopRoutesAreRegisteredInTheRealApplication() throws Exception {
        mvc.perform(get("/api/shop/products").param("page", "1").param("pageSize", "20")
                .param("sort", "default").param("city", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(17))
            .andExpect(jsonPath("$.items[0].id").value("hangzhou-xihu"))
            .andExpect(jsonPath("$.items[0].price").value(79))
            .andExpect(jsonPath("$.items[0].sales").doesNotExist());
        mvc.perform(get("/api/shop/products/hangzhou-xihu"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("hangzhou-xihu"));
        mvc.perform(get("/api/shop/banners"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/shop/products").param("sort", "sales"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SHOP_QUERY"));
        mvc.perform(get("/api/shop/products").param("page", "not-a-number"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SHOP_QUERY"));
    }

    @Test void privateReadsRemainAuthenticatedAndTheApiPrefixOccursOnce() throws Exception {
        for (String path : new String[]{"/api/me/birds", "/api/me/points", "/api/me/points/ledger",
                "/api/me/outfits", "/api/skins", "/api/me/checkins", "/api/auth/session"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        }
        mvc.perform(get("/api/api/shop/products"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
        mvc.perform(post("/api/shop/products"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test void unsupportedRequestMediaIsAClientErrorRatherThanServiceFailure() throws Exception {
        mvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr("192.0.2.251"); return request; })
                .contentType("text/plain").content("username=test&password=not-json"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/health").accept("application/xml"))
            .andExpect(status().isNotAcceptable());
    }
}
