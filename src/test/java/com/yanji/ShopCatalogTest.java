package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ShopCatalogTest {
    final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;
    Map<String,Object> product(String id, String city, String price, int order) {
        var result = new LinkedHashMap<String,Object>();
        result.put("id", id); result.put("title", "测试小鸟 " + id);
        result.put("cityCode", city); result.put("cityName", city.equals("hangzhou") ? "杭州" : "苏州");
        result.put("remark", "互动发声毛绒玩偶"); result.put("price", new BigDecimal(price));
        result.put("coverImageUrl", "/assets/shop/" + id + "-cover.webp");
        result.put("detailImageUrl", "/assets/shop/" + id + "-detail.webp");
        result.put("purchaseUrl", "https://e.tb.cn/h.example?tk=test"); result.put("sortOrder", order);
        return result;
    }
    Map<String,Object> banner(String id, int order) {
        return new LinkedHashMap<>(Map.of("id",id,"imageUrl","/assets/shop/"+id+".webp","alt","商城轮播图","sortOrder",order));
    }
    byte[] json(List<Map<String,Object>> products, List<Map<String,Object>> banners) throws Exception {
        return mapper.writeValueAsBytes(Map.of("schemaVersion",1,"products",products,"banners",banners));
    }
    ShopCatalogService service(List<Map<String,Object>> products, List<Map<String,Object>> banners) throws Exception {
        return new ShopCatalogService(mapper,json(products,banners));
    }
    @Test void defaultCatalogMatchesEnabledConfiguredInventoryAcrossPages() throws Exception {
        JsonNode configured;
        try(var stream=getClass().getResourceAsStream("/shop/catalog.json")) {
            assertThat(stream).isNotNull();configured=mapper.readTree(stream);
        }
        var expectedProducts=new ArrayList<JsonNode>();
        configured.path("products").forEach(p -> {if(!p.hasNonNull("enabled") || p.get("enabled").asBoolean())expectedProducts.add(p);});
        var ordering=Comparator.comparingInt((JsonNode p)->p.path("sortOrder").asInt(0)).thenComparing(p->p.path("id").asText());
        expectedProducts.sort(ordering);
        var service=new ShopCatalogService(mapper, "");
        var actual=new ArrayList<ShopCatalogService.Product>();
        for(int page=1;actual.size()<expectedProducts.size() || page==1;page++) {
            var result=service.products(null,"default",page,7);
            assertThat(result.total()).isEqualTo(expectedProducts.size());
            assertThat(result.page()).isEqualTo(page);assertThat(result.pageSize()).isEqualTo(7);
            actual.addAll(result.items());
            if(result.items().isEmpty())break;
        }
        assertThat(actual).extracting(ShopCatalogService.Product::id)
            .containsExactlyElementsOf(expectedProducts.stream().map(p->p.path("id").asText()).toList());
        var expectedCities=new LinkedHashMap<String,String>();
        for(int i=0;i<expectedProducts.size();i++) {
            var expected=expectedProducts.get(i);var item=actual.get(i);
            assertThat(item.title()).isEqualTo(expected.path("title").asText());
            assertThat(item.price()).isEqualByComparingTo(expected.path("price").decimalValue());
            assertThat(item.coverImageUrl()).isEqualTo(expected.path("coverImageUrl").asText());
            assertThat(item.detailImageUrl()).isEqualTo(expected.path("detailImageUrl").asText());
            assertThat(item.purchaseUrl()).isEqualTo(expected.path("purchaseUrl").isTextual()?expected.path("purchaseUrl").asText():null);
            assertThat(item.closeHotspot()).isNotNull();
            expectedCities.putIfAbsent(expected.path("cityCode").asText(),expected.path("cityName").asText());
        }
        assertThat(service.products(null,"default",1,100).cities()).containsExactlyElementsOf(
            expectedCities.entrySet().stream().map(e->new ShopCatalogService.City(e.getKey(),e.getValue())).toList());
        var expectedBanners=new ArrayList<JsonNode>();
        configured.path("banners").forEach(b -> {if(!b.hasNonNull("enabled") || b.get("enabled").asBoolean())expectedBanners.add(b);});
        expectedBanners.sort(ordering);
        assertThat(service.banners().items()).extracting(ShopCatalogService.Banner::id)
            .containsExactlyElementsOf(expectedBanners.stream().map(b->b.path("id").asText()).toList());
        var mvc=MockMvcBuilders.standaloneSetup(new ShopController(service)).build();
        var result=mvc.perform(get("/bird/v1/shop/banners")).andExpect(status().isOk()).andReturn();
        JsonNode response=mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.path("items").size()).isEqualTo(expectedBanners.size());
        response.path("items").forEach(b -> assertThat(b.size()).isEqualTo(3));
    }
    @Test void defaultCatalogIncludesConfirmedHangzhouAdditionsAtUnifiedRetailPriceAndPurchaseLink() throws Exception {
        var service=new ShopCatalogService(mapper, "");
        var all=service.products(null,"default",1,100);
        assertThat(all.total()).isEqualTo(17);
        assertThat(all.items()).allSatisfy(item -> {
            assertThat(item.price()).isEqualByComparingTo("79.00");
            assertThat(item.purchaseUrl()).as("purchase link for %s",item.id())
                .isEqualTo("https://e.tb.cn/h.jTRBezgACJi6VEn?tk=fD1ETztXxx7");
        });
        assertThat(service.products("hangzhou","default",1,100).total()).isEqualTo(11);
        assertThat(all.items()).extracting(ShopCatalogService.Product::id)
            .endsWith("hangzhou-baitou-gaojie","hangzhou-jigong");
        var baitou=service.product("hangzhou-baitou-gaojie");
        var jigong=service.product("hangzhou-jigong");
        assertThat(baitou.title()).isEqualTo("白头高洁");
        assertThat(jigong.title()).isEqualTo("济公小鸟");
        assertThat(baitou.sortOrder()).isEqualTo(160);
        assertThat(jigong.sortOrder()).isEqualTo(170);
        for(var item:List.of(baitou,jigong)) {
            assertThat(item.cityCode()).isEqualTo("hangzhou");
            assertThat(item.cityName()).isEqualTo("杭州");
        }
    }
    @Test void explicitEmptyCatalogRemainsSupported() throws Exception {
        var service=service(List.of(),List.of());
        assertThat(service.products(null,"default",1,20).items()).isEmpty();
        assertThat(service.products(null,"default",1,20).cities()).isEmpty();
        assertThat(service.banners().items()).isEmpty();
    }
    @Test void externalUtf8JsonLoadsOnceAndPreservesMoneyAsYuan() throws Exception {
        Path file=directory.resolve("catalog.json");
        Files.write(file,json(List.of(product("xihu","hangzhou","69.00",1)),List.of(banner("hero",0))));
        var service=new ShopCatalogService(mapper,file.toString());
        assertThat(service.product("xihu").title()).isEqualTo("测试小鸟 xihu");
        assertThat(service.product("xihu").price()).isEqualByComparingTo("69.00");
        Files.writeString(file,"invalid",StandardCharsets.UTF_8);
        assertThat(service.product("xihu").price()).isEqualByComparingTo("69.00");
        assertThatThrownBy(()->new ShopCatalogService(mapper,file.toString())).isInstanceOf(IllegalStateException.class);
    }
    @Test void filtersSortsPagesAndCitiesUseOnlyEnabledProducts() throws Exception {
        var disabled=product("hidden","nanjing","1.00",0);disabled.put("enabled",false);
        var service=service(List.of(product("z","hangzhou","69.00",1),product("b","suzhou","59.00",0),
            product("a","hangzhou","69.00",1),disabled),List.of());
        assertThat(service.products(null,"default",1,20).items()).extracting(ShopCatalogService.Product::id).containsExactly("b","a","z");
        assertThat(service.products(null,"priceAsc",1,20).items()).extracting(ShopCatalogService.Product::id).containsExactly("b","a","z");
        assertThat(service.products(null,"priceDesc",1,20).items()).extracting(ShopCatalogService.Product::id).containsExactly("a","z","b");
        var page=service.products("hangzhou","default",2,1);
        assertThat(page.items()).extracting(ShopCatalogService.Product::id).containsExactly("z");
        assertThat(page.total()).isEqualTo(2);assertThat(page.page()).isEqualTo(2);assertThat(page.pageSize()).isEqualTo(1);
        assertThat(page.cities()).extracting(ShopCatalogService.City::code).containsExactly("suzhou","hangzhou");
        assertThat(service.products(null,"default",Integer.MAX_VALUE,100).items()).isEmpty();
        assertThat(service.products("unknown","default",1,20).items()).isEmpty();
    }
    @Test void publicHttpContractContainsNoSalesNoAuthAndSafeErrors() throws Exception {
        var hidden=product("hidden","hangzhou","69.00",1);hidden.put("enabled",false);
        var service=service(List.of(product("xihu","hangzhou","69.00",0),hidden),List.of(banner("hero",0)));
        var mvc=MockMvcBuilders.standaloneSetup(new ShopController(service)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/bird/v1/shop/products")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].price").value(69)).andExpect(jsonPath("$.items[0].sales").doesNotExist())
            .andExpect(jsonPath("$.items[0].soldCount").doesNotExist()).andExpect(jsonPath("$.items[0].enabled").doesNotExist());
        mvc.perform(get("/bird/v1/shop/products/xihu")).andExpect(status().isOk()).andExpect(jsonPath("$.detailImageUrl").value("/assets/shop/xihu-detail.webp"));
        for(String id:List.of("hidden","unknown","UPPER"))mvc.perform(get("/bird/v1/shop/products/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_PRODUCT_NOT_FOUND"));
        mvc.perform(get("/bird/v1/shop/products").param("sort","sales")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SHOP_QUERY"));
        mvc.perform(get("/bird/v1/shop/products").param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/bird/v1/shop/products").param("pageSize","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/bird/v1/shop/products").param("page","abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SHOP_QUERY"));
        mvc.perform(get("/bird/v1/shop/banners")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].imageUrl").value("/assets/shop/hero.webp"))
            .andExpect(jsonPath("$.items[0].link").doesNotExist()).andExpect(jsonPath("$.items[0].purchaseUrl").doesNotExist());
    }
    @Test void conditionalPublicReadsReturnBodyless304WithWeakAndMultipleValidators() throws Exception {
        var service=service(List.of(product("xihu","hangzhou","69.00",0)),List.of(banner("hero",0)));
        var mvc=MockMvcBuilders.standaloneSetup(new ShopController(service)).build();
        for(String path:List.of("/bird/v1/shop/products","/bird/v1/shop/products/xihu","/bird/v1/shop/banners")) {
            var first=mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","public, max-age=60, must-revalidate")).andReturn();
            String tag=first.getResponse().getHeader("ETag");
            assertThat(tag).matches("W/\"[a-f0-9]{64}\"");
            assertThat(first.getResponse().getHeaders("ETag")).containsExactly(tag);
            for(String conditional:List.of(tag, tag.substring(2), "\"unrelated\", " + tag, "*")) {
                mvc.perform(get(path).header("If-None-Match",conditional))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).as(path+" with "+conditional).isEqualTo(304))
                    .andExpect(content().string(""))
                    .andExpect(header().stringValues("ETag",tag))
                    .andExpect(header().string("Cache-Control","public, max-age=60, must-revalidate"));
            }
            mvc.perform(get(path).header("If-None-Match","W/\"stale\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag",tag));
        }
    }
    @Test void validatorsIdentifySelectedRepresentationsAndChangesAfterCatalogReload() throws Exception {
        var a=product("a","hangzhou","69.00",0);var b=product("b","suzhou","59.00",1);
        var service=service(List.of(a,b),List.of(banner("hero",0)));
        var mvc=MockMvcBuilders.standaloneSetup(new ShopController(service)).build();
        String tag=mvc.perform(get("/bird/v1/shop/products")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/bird/v1/shop/products").param("city","").header("If-None-Match",tag)).andExpect(status().isNotModified());
        for(String query:List.of("?city=hangzhou","?sort=priceAsc","?pageSize=1","?page=2")) {
            var result=mvc.perform(get("/bird/v1/shop/products"+query).header("If-None-Match",tag))
                .andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getHeader("ETag")).isNotEqualTo(tag);
        }
        String productTag=mvc.perform(get("/bird/v1/shop/products/a")).andReturn().getResponse().getHeader("ETag");
        a.put("purchaseUrl","https://item.taobao.com/item.htm?id=123");
        var updated=MockMvcBuilders.standaloneSetup(new ShopController(service(List.of(a,b),List.of()))).build();
        updated.perform(get("/bird/v1/shop/products/a").header("If-None-Match",productTag))
            .andExpect(status().isOk()).andExpect(jsonPath("$.purchaseUrl").value("https://item.taobao.com/item.htm?id=123"));
        a.put("enabled",false);
        var removed=MockMvcBuilders.standaloneSetup(new ShopController(service(List.of(a,b),List.of()))).build();
        removed.perform(get("/bird/v1/shop/products/a").header("If-None-Match",productTag))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().doesNotExist("ETag"));
    }
    @Test void invalidQueriesAndMissingProductsCannotBeHiddenByMatchingWildcard() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ShopController(service(List.of(),List.of()))).build();
        for(String query:List.of("?page=0","?page=abc","?pageSize=101","?sort=sales","?city=UPPER")) {
            mvc.perform(get("/bird/v1/shop/products"+query).header("If-None-Match","*"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("ETag"));
        }
        mvc.perform(get("/bird/v1/shop/products/missing").header("If-None-Match","*"))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().doesNotExist("ETag"));
    }
    @Test void bannersAreOrderedDisabledAndNeverLinkable() throws Exception {
        var hidden=banner("hidden",0);hidden.put("enabled",false);
        assertThat(service(List.of(),List.of(banner("z",2),banner("a",1),hidden)).banners().items())
            .extracting(ShopCatalogService.Banner::id).containsExactly("a","z");
        var linked=banner("linked",0);linked.put("link","https://e.tb.cn/test");
        assertThatThrownBy(()->service(List.of(),List.of(linked))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsDuplicateInvalidIdsAndConflictingCities() throws Exception {
        assertThatThrownBy(()->service(List.of(product("same","hangzhou","69.00",0),product("same","hangzhou","69.00",1)),List.of())).isInstanceOf(IllegalStateException.class);
        for(String id:List.of("", "UPPER", "space here", "../bird", "x".repeat(65)))
            assertThatThrownBy(()->service(List.of(product(id,"hangzhou","69.00",0)),List.of())).isInstanceOf(IllegalStateException.class);
        var other=product("other","hangzhou","69.00",0);other.put("cityName","另一个城市");
        assertThatThrownBy(()->service(List.of(product("a","hangzhou","69.00",0),other),List.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsInvalidPrices() throws Exception {
        for(String price:List.of("0", "-1", "69.001", "1000000.00"))
            assertThatThrownBy(()->service(List.of(product("a","hangzhou",price,0)),List.of())).isInstanceOf(IllegalStateException.class);
        var missing=product("a","hangzhou","69.00",0);missing.remove("price");
        assertThatThrownBy(()->service(List.of(missing),List.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsUntrustedImageAndPurchaseUrls() throws Exception {
        for(String path:List.of("https://evil.test/photo.jpg", "//evil.test/photo.jpg", "/assets/../private.jpg", "/assets//photo.jpg", "/assets/x.svg", "/bird/v1/checkins/private.jpg")) {
            var item=product("a","hangzhou","69.00",0);item.put("detailImageUrl",path);
            assertThatThrownBy(()->service(List.of(item),List.of())).isInstanceOf(IllegalStateException.class);
        }
        for(String url:List.of("http://e.tb.cn/test", "https://evil.test/test", "https://e.tb.cn.evil.test/test", "https://a@e.tb.cn/test", "javascript:alert(1)", "https://e.tb.cn:8443/test")) {
            var item=product("a","hangzhou","69.00",0);item.put("purchaseUrl",url);
            assertThatThrownBy(()->service(List.of(item),List.of())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void cartHotspotDefaultsAndRejectsOutOfRangeOrNonFiniteCoordinates() throws Exception {
        var normal=service(List.of(product("a","hangzhou","69.00",0)),List.of()).product("a").cartHotspot();
        assertThat(normal).isEqualTo(new ShopCatalogService.ImageRect(0.08,0.87,0.84,0.08));
        var custom=product("a","hangzhou","69.00",0);custom.put("cartHotspot",Map.of("left",0.1,"top",0.7,"width",0.8,"height",0.1));
        assertThat(service(List.of(custom),List.of()).product("a").cartHotspot().top()).isEqualTo(0.7);
        for(var bad:List.of(
            Map.of("left",-0.1,"top",0.7,"width",0.8,"height",0.1),
            Map.of("left",0.5,"top",0.7,"width",0.8,"height",0.1),
            Map.of("left",0.1,"top",0.95,"width",0.8,"height",0.1),
            Map.of("left",0.1,"top",0.7,"width",0.0,"height",0.1),
            Map.of("left",Double.NaN,"top",0.7,"width",0.8,"height",0.1),
            Map.of("top",0.7,"width",0.8,"height",0.1))) {
            var item=product("a","hangzhou","69.00",0);item.put("cartHotspot",bad);
            assertThatThrownBy(()->service(List.of(item),List.of())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void closeHotspotDefaultsOverridesAndRejectsInvalidRectangles() throws Exception {
        var item=product("a","hangzhou","69.00",0);
        assertThat(service(List.of(item),List.of()).product("a").closeHotspot())
            .isEqualTo(new ShopCatalogService.ImageRect(0.837,0.0555,0.085,0.046));
        item.put("closeHotspot",Map.of("left",0.8,"top",0.01,"width",0.15,"height",0.1));
        assertThat(service(List.of(item),List.of()).product("a").closeHotspot().top()).isEqualTo(0.01);
        for(var invalid:List.of(
            Map.of("left",-0.1,"top",0.1,"width",0.1,"height",0.1),
            Map.of("left",0.95,"top",0.1,"width",0.1,"height",0.1),
            Map.of("left",0.8,"top",0.95,"width",0.1,"height",0.1),
            Map.of("left",0.8,"top",0.1,"width",0.0,"height",0.1),
            Map.of("left",Double.POSITIVE_INFINITY,"top",0.1,"width",0.1,"height",0.1),
            Map.of("left",0.8,"top",0.1,"width",0.1))) {
            var bad=product("bad","hangzhou","69.00",0);bad.put("closeHotspot",invalid);
            assertThatThrownBy(()->service(List.of(bad),List.of())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void missingPurchaseLinkIsVisibleAndCropsAreValidatedWithoutRewritingImages() throws Exception {
        var item=product("a","hangzhou","69.00",0);item.remove("purchaseUrl");
        item.put("coverCrop",Map.of("left",0.1,"top",0.2,"width",0.8,"height",0.5));
        item.put("detailCrop",Map.of("left",0.01,"top",0.1,"width",0.98,"height",0.8));
        var product=service(List.of(item),List.of()).product("a");
        assertThat(product.purchaseUrl()).isNull();
        assertThat(product.coverCrop().top()).isEqualTo(0.2);
        assertThat(product.detailCrop().height()).isEqualTo(0.8);
        assertThat(product.coverImageUrl()).isEqualTo("/assets/shop/a-cover.webp");
        item.put("purchaseUrl",null);item.remove("coverCrop");item.remove("detailCrop");
        var defaults=service(List.of(item),List.of()).product("a");
        assertThat(defaults.coverCrop()).isEqualTo(new ShopCatalogService.ImageRect(0.0,0.0,1.0,1.0));
        assertThat(defaults.detailCrop()).isEqualTo(defaults.coverCrop());
        for(String field:List.of("coverCrop","detailCrop")) {
            var invalid=product("bad","hangzhou","69.00",0);invalid.put(field,Map.of("left",0.8,"top",0.2,"width",0.5,"height",0.5));
            assertThatThrownBy(()->service(List.of(invalid),List.of())).isInstanceOf(IllegalStateException.class);
        }
        var mvc=MockMvcBuilders.standaloneSetup(new ShopController(service(List.of(item),List.of()))).build();
        var result=mvc.perform(get("/bird/v1/shop/products/a")).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray()).get("purchaseUrl").isNull()).isTrue();
    }
    @Test void rejectsUnknownSalesFieldsSchemaVersionAndMalformedJson() throws Exception {
        var item=product("a","hangzhou","69.00",0);item.put("sales",10);
        assertThatThrownBy(()->service(List.of(item),List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ShopCatalogService(mapper,"{\"schemaVersion\":2,\"products\":[],\"banners\":[]}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ShopCatalogService(mapper,"{\"schemaVersion\":1,\"schemaVersion\":1,\"products\":[],\"banners\":[]}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ShopCatalogService(mapper,new byte[]{'{'})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ShopCatalogService(mapper,directory.resolve("missing.json").toString())).isInstanceOf(IllegalStateException.class);
    }
}
