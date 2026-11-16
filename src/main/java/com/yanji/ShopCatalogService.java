package com.yanji;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Public shop reads from one validated, immutable startup snapshot. No database writes. */
@Service
public class ShopCatalogService {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern CITY = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern IMAGE = Pattern.compile("/assets/[A-Za-z0-9][A-Za-z0-9_./-]*\\.(png|jpe?g|webp|avif)");
    private static final Set<String> PURCHASE_HOSTS = Set.of("e.tb.cn", "item.taobao.com", "detail.tmall.com");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final List<Product> products;
    private final List<Banner> banners;
    private final List<City> cities;
    private final ObjectMapper mapper;

    public record Product(String id, String title, String cityCode, String cityName, String remark,
                          BigDecimal price, String coverImageUrl, String detailImageUrl,
                          String purchaseUrl, int sortOrder, ImageRect cartHotspot, ImageRect coverCrop, ImageRect detailCrop, ImageRect closeHotspot) {}
    public record ImageRect(Double left, Double top, Double width, Double height) {}
    private static final ImageRect DEFAULT_HOTSPOT = new ImageRect(0.08, 0.87, 0.84, 0.08);
    private static final ImageRect DEFAULT_CLOSE_HOTSPOT = new ImageRect(0.837, 0.0555, 0.085, 0.046);
    private static final ImageRect FULL_IMAGE = new ImageRect(0.0, 0.0, 1.0, 1.0);
    public record Banner(String id, String imageUrl, String alt) {}
    public record City(String code, String name) {}
    public record Page(List<Product> items, int total, int page, int pageSize, List<City> cities) {}
    public record BannerPage(List<Banner> items) {}
    record Catalog(int schemaVersion, List<ProductConfig> products, List<BannerConfig> banners) {}
    record ProductConfig(String id, String title, String cityCode, String cityName, String remark,
                         BigDecimal price, String coverImageUrl, String detailImageUrl,
                         String purchaseUrl, Integer sortOrder, Boolean enabled, ImageRect cartHotspot, ImageRect coverCrop, ImageRect detailCrop, ImageRect closeHotspot) {}
    record BannerConfig(String id, String imageUrl, String alt, Integer sortOrder, Boolean enabled) {}

    @org.springframework.beans.factory.annotation.Autowired
    public ShopCatalogService(ObjectMapper mapper, @Value("${app.shop.catalog-file:}") String filename) {
        this(mapper, readCatalog(filename));
    }

    ShopCatalogService(ObjectMapper mapper, byte[] bytes) {
        this.mapper = mapper;
        try {
            require(bytes.length <= MAX_BYTES, "商城配置超过 2 MB");
            var strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            Catalog config = strict.readValue(bytes, Catalog.class);
            require(config != null && config.schemaVersion() == 1, "商城配置 schemaVersion 必须为 1");
            require(config.products() != null && config.products().size() <= 5000, "商城配置 products 必须为数组且不超过 5000 项");
            require(config.banners() != null && config.banners().size() <= 20, "商城配置 banners 必须为数组且不超过 20 项");
            Set<String> productIds = new HashSet<>(), bannerIds = new HashSet<>();
            Map<String, String> cityNames = new LinkedHashMap<>();
            for (ProductConfig p : config.products()) {
                require(p != null, "商品不可为空");
                require(validId(p.id()) && productIds.add(p.id()), "商品 ID 必须唯一，且为 1–64 位小写字母、数字、下划线或横线");
                text(p.title(), 80, false, "商品标题");
                require(p.cityCode() != null && CITY.matcher(p.cityCode()).matches(), "商品城市编码格式不正确");
                text(p.cityName(), 32, false, "城市名称");
                String previous = cityNames.putIfAbsent(p.cityCode(), p.cityName());
                require(previous == null || previous.equals(p.cityName()), "相同城市编码必须使用相同城市名称");
                text(p.remark(), 80, true, "商品备注");
                require(p.price() != null && p.price().signum() > 0 && p.price().scale() <= 2
                    && p.price().compareTo(new BigDecimal("999999.99")) <= 0, "商品价格须为元，范围 0.01–999999.99，最多两位小数");
                image(p.coverImageUrl()); image(p.detailImageUrl()); purchase(p.purchaseUrl()); order(p.sortOrder()); hotspot(p.cartHotspot()); crop(p.coverCrop()); crop(p.detailCrop()); closeHotspot(p.closeHotspot());
            }
            for (BannerConfig b : config.banners()) {
                require(b != null, "轮播图不可为空");
                require(validId(b.id()) && bannerIds.add(b.id()), "轮播图 ID 必须唯一且格式正确");
                image(b.imageUrl()); text(b.alt(), 120, false, "轮播图替代文字"); order(b.sortOrder());
            }
            var defaultOrder = Comparator.comparingInt(Product::sortOrder).thenComparing(Product::id);
            products = config.products().stream().filter(p -> !Boolean.FALSE.equals(p.enabled()))
                .map(p -> new Product(p.id(), p.title(), p.cityCode(), p.cityName(), p.remark() == null ? "" : p.remark(),
                    p.price().setScale(2), p.coverImageUrl(), p.detailImageUrl(), p.purchaseUrl(), order(p.sortOrder()), hotspot(p.cartHotspot()), crop(p.coverCrop()), crop(p.detailCrop()), closeHotspot(p.closeHotspot())))
                .sorted(defaultOrder).toList();
            banners = config.banners().stream().filter(b -> !Boolean.FALSE.equals(b.enabled()))
                .sorted(Comparator.comparingInt((BannerConfig b) -> order(b.sortOrder())).thenComparing(BannerConfig::id))
                .map(b -> new Banner(b.id(), b.imageUrl(), b.alt())).toList();
            Map<String, City> availableCities = new LinkedHashMap<>();
            products.forEach(p -> availableCities.putIfAbsent(p.cityCode(), new City(p.cityCode(), p.cityName())));
            cities = List.copyOf(availableCities.values());
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("商城配置无效：" + error.getMessage(), error);
        }
    }

    public Page products(String city, String sort, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid("页码须大于 0，每页数量须为 1–100");
        if (city != null && !city.isEmpty() && !CITY.matcher(city).matches()) throw invalid("城市编码格式不正确");
        Comparator<Product> ordering = switch (sort) {
            case "default" -> Comparator.comparingInt(Product::sortOrder).thenComparing(Product::id);
            case "priceAsc" -> Comparator.comparing(Product::price).thenComparingInt(Product::sortOrder).thenComparing(Product::id);
            case "priceDesc" -> Comparator.comparing(Product::price).reversed().thenComparingInt(Product::sortOrder).thenComparing(Product::id);
            default -> throw invalid("排序仅支持 default、priceAsc、priceDesc");
        };
        List<Product> matching = products.stream().filter(p -> city == null || city.isEmpty() || p.cityCode().equals(city)).sorted(ordering).toList();
        int from = (int) Math.min((long) (page - 1) * pageSize, matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page(List.copyOf(matching.subList(from, to)), matching.size(), page, pageSize, cities);
    }

    public Product product(String id) {
        if (!validId(id)) throw new ShopFailure(404, "SHOP_PRODUCT_NOT_FOUND", "商品不存在或已下架");
        return products.stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new ShopFailure(404, "SHOP_PRODUCT_NOT_FOUND", "商品不存在或已下架"));
    }

    public BannerPage banners() { return new BannerPage(banners); }

    String representationTag(Object body) {
        try {
            // Hash the selected representation, including paging and all currently visible product fields.
            // A weak validator remains correct when Nginx applies a different transfer encoding.
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body));
            return "W/\"" + HexFormat.of().formatHex(hash) + "\"";
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("商城响应摘要无法生成", error);
        }
    }

    private static byte[] readCatalog(String filename) {
        try {
            if (filename == null || filename.isBlank()) {
                try (var stream = new ClassPathResource("shop/catalog.json").getInputStream()) { return stream.readNBytes(MAX_BYTES + 1); }
            }
            Path file = Path.of(filename);
            require(Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES, "商城配置文件不存在、不是文件或超过 2 MB");
            try (var stream = Files.newInputStream(file)) { return stream.readNBytes(MAX_BYTES + 1); }
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("商城配置无法读取", error);
        }
    }
    private static boolean validId(String value) { return value != null && ID.matcher(value).matches(); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static int order(Integer value) { require(value == null || value >= 0, "sortOrder 不可为负数"); return value == null ? 0 : value; }
    private static void text(String value, int max, boolean optional, String field) {
        if (value == null && optional) return;
        require(value != null && (optional || !value.isBlank()) && value.length() <= max
            && value.chars().noneMatch(Character::isISOControl), field + "长度或内容不正确");
    }
    private static void image(String value) {
        require(value != null && value.length() <= 1024 && IMAGE.matcher(value).matches()
            && !value.contains("..") && !value.contains("//"), "图片须为同站点 /assets/ 下的 PNG/JPG/WebP/AVIF 路径");
    }
    private static ImageRect hotspot(ImageRect value) { return rectangle(value, DEFAULT_HOTSPOT); }
    private static ImageRect closeHotspot(ImageRect value) { return rectangle(value, DEFAULT_CLOSE_HOTSPOT); }
    private static ImageRect crop(ImageRect value) { return rectangle(value, FULL_IMAGE); }
    private static ImageRect rectangle(ImageRect value, ImageRect fallback) {
        if (value == null) return fallback;
        require(value.left() != null && value.top() != null && value.width() != null && value.height() != null
            && Double.isFinite(value.left()) && Double.isFinite(value.top()) && Double.isFinite(value.width()) && Double.isFinite(value.height())
            && value.left() >= 0 && value.top() >= 0 && value.width() > 0 && value.height() > 0
            && value.left() + value.width() <= 1 && value.top() + value.height() <= 1, "图片裁剪及按钮热点须为图片范围内有效的 0–1 比例坐标");
        return value;
    }
    private static void purchase(String value) {
        if (value == null) return;
        text(value, 2048, false, "淘宝链接");
        URI uri = URI.create(value);
        require("https".equals(uri.getScheme()) && PURCHASE_HOSTS.contains(uri.getHost()) && uri.getUserInfo() == null
            && (uri.getPort() == -1 || uri.getPort() == 443), "购买链接须为受支持的 HTTPS 淘宝域名");
    }
    private static ShopFailure invalid(String message) { return new ShopFailure(400, "INVALID_SHOP_QUERY", message); }
    public static final class ShopFailure extends RuntimeException {
        final int status; final String code;
        ShopFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
