# 商城商品与轮播资料维护

商城只提供公开读取接口，采用 UTF-8 JSON 配置，不使用数据库，不含后台管理、上传、站内订单或支付。随 JAR 的 `src/main/resources/shop/catalog.json` 记录用户已确认的商品完整图，数量、顺序、城市与 SKU 以该配置中的启用清单为准。当前默认清单为 17 款、5 座城市：杭州 11、苏州 3、台州 1、洛阳 1、绍兴 1；包含用户要求额外保留的原西湖款，竹风雀与苏州小鸟毛绒为两个独立 SKU。14 张新增源图的溯源见同目录 `catalog-import-20261103.json`。2026-11-04 按用户要求将全部商品展示价格统一为 79.00 元，并新增杭州「白头高洁」「济公小鸟」两款，购买链接暂为 null，内置城市旅行日记主题轮播图 `/assets/shop/banners/huangying-shop-layout-v3-optimized.webp`（发布时使用内容指纹 URL；2026-11-04 已移除图内“小黄莺的小店”标题及右下“鸟蛋规则”标签，现有文案采用主标题与两行副文案居中排版），只展示不跳转；不展示销量，不将配置价格或示例素材视为后台成交记录。

新增两款使用稳定商品 ID `hangzhou-baitou-gaojie`、`hangzhou-jigong`，配置顺序为 160、170，保留原 15 款的 ID 与顺序。原素材、运行资源指纹及裁切／热点坐标见 [新增商品媒体记录](../小鸟资料库/小鸟商城详情页/新增20261104/media-coordinates-20261104.json)。目录仅收录商品字段，媒体制作记录中的遮罩坐标和源文件位置不进入公开接口。

2026-11-05 本地修订：用户已确认“先在本地恢复所有商品共用这个链接”，开发目录全部 17 款 `purchaseUrl` 已恢复为 `https://e.tb.cn/h.jTRBezgACJi6VEn?tk=fD1ETztXxx7`，替代上述历史空值。用户此前已给过这个共用地址，API 目录迁移时未延续；不能再把本批商品记为用户从未提供链接。本轮没有更新生产的 `/www/server/xcwc/config/shop-catalog.json` 或重启线上服务；线上现有 16 款仍与本地目录分开。此共用目标不代表逐款独立淘宝 SKU，未经本次发布授权不能仅凭本地配置更新生产。详见 [恢复记录](../docs/tasks/商城淘宝链接恢复-V1.1030-2026-11-05.md)。

## 三个公开接口

- `GET /api/shop/products?city=hangzhou&sort=default&page=1&pageSize=20` 返回 `{items,total,page,pageSize,cities}`。`city` 省略/空串表示全部；`sort` 为 `default`、`priceAsc`、`priceDesc`；`pageSize` 为 1–100。`total` 为当前城市筛选后的总数，`cities` 来自所有启用商品。
- `GET /api/shop/products/{id}` 返回单个启用商品；不存在、停用、ID 格式不符都返回 404 / `SHOP_PRODUCT_NOT_FOUND`。
- `GET /api/shop/banners` 返回 `{items:[{id,imageUrl,alt}]}`；只有图片，不含跳转链接。

权威字段定义在 `openapi.yaml`。所有商品响应均无销量字段，不支持销量排序。价格 `price` 是人民币**元**的 JSON number，最多两位小数；当前真实资料按用户确认统一填写 `79.00`，不在后端硬编码固定价格。

## 导入真实资料

1. 复制 `src/main/resources/shop/catalog.example.json` 为服务器站点目录外的私有配置文件，例如 `/etc/xcwc/shop-catalog.json`。示例商品和轮播均为 `enabled: false`，必须先替换真实资料再启用。
2. 商品 ID 为 1–64 位小写字母、数字、横线、下划线；一旦发布尽量保持稳定，前端用它关联详情及购物车去重，不能替换成实体 `birdId`。
3. 同城商品使用同一 `cityCode`、`cityName`。`remark` 可为空串；`sortOrder` 缺省为 0，数值小的在前，同值按 ID 排序。商品与轮播的 `enabled` 缺省为 true。
4. `coverImageUrl` 为商品封面；`detailImageUrl` 为**每款商品独立的一张完整详情长图**，前端展示整张图，不按旧方案拼接标题或透明小鸟素材；参考价格由接口 `price` 驱动的展示层保持一致，原详情图中的历史价格不作为现价。完整长图已画好“加入购物车”按钮，前端在图上叠加透明点击区。 当前默认资料使用独立派生封面 `cover-480.webp`（已截取商品主图，`coverCrop` 为整图）及 `detail-optimized.webp`（保持原详情图 852×1846 尺寸）。原 PNG 保留，详情 `detailCrop`、`cartHotspot` 和 `closeHotspot` 坐标不因压缩改变；轮播使用独立优化 WebP。
5. 图片文件部署到当前 H5 站点，配置统一使用 `/assets/...` 路径，仅支持 `.png`、`.jpg`、`.jpeg`、`.webp`、`.avif`；不使用外部图片域名、`..`、查询串、SVG 或私密图片目录。不需要为商城额外开放媒体上传或代理。
6. `coverCrop`、`detailCrop` 可分别定义封面与详情的显示窗口，相对原文件宽高使用 0–1 比例；省略时返回整图 `{left:0,top:0,width:1,height:1}`。完整截图原文件保持不变，仅由前端 CSS 窗口显示指定区域。`cartHotspot` 是 **detailCrop 裁后展示图** 内加购按钮的位置，采用图片宽高的比例坐标，`left/top >= 0`、`width/height > 0`，且不超出图片。省略时接口返回 `{left:0.08,top:0.87,width:0.84,height:0.08}`，对应本轮默认定稿位置；如果每款图按钮位置不同，仅修改该商品 JSON 坐标。前端点击区应有“加入购物车”可访问标签，不要求用户提供透明小鸟图。 `closeHotspot` 单独配置关闭按钮透明点击区，坐标同样相对 `detailCrop` 裁后图；省略时为 `{left:0.837,top:0.0555,width:0.085,height:0.046}`，兼容原四款截图。纯详情白底图与带手机外框截图的位置不同，应按每张图实际位置配置，前端不得再写死关闭按钮区域。
7. `purchaseUrl` 在尚无真实淘宝链接时留空字段或填 `null`，不填测试链接；商品仍可展示、加购，购物车购买按钮禁用并显示“链接待配置”。有真实链接后仅允许 HTTPS 的 `e.tb.cn`、`item.taobao.com`、`detail.tmall.com`。沿用加入购物车 → 购物车 → 淘宝的已确认流程，不在站内支付、不因跳转自动领取小鸟或鸟蛋。
8. 启动后端时设置 `--app.shop.catalog-file=/etc/xcwc/shop-catalog.json`，或环境变量 `APP_SHOP_CATALOG_FILE=/etc/xcwc/shop-catalog.json`。不配置时读取 JAR 内置的当前商品目录。

配置在启动时整体校验并生成不可变快照。后续修改 JSON 后需要按现有部署方式重启后端才能生效；没有热更新、后台写接口或自动导入生产的脚本。配置错误会拒绝启动，不静默降级成演示资料。备份当前配置后再替换，保留原账号、数据库、相册等配置。

## 本地隔离验证

`ShopCatalogTest` 使用直接实例化服务和独立 MockMvc，不连接数据库。Java 21 / Maven 下执行：

```sh
mvn -B -Dtest=ShopCatalogTest test
```

本机只有旧版 Java 时可使用已有 Docker Maven 21，挂载隔离源码快照及独立构建目录，避免覆盖并发构建产物。无需设置 DB_URL / DB_PASSWORD。

真实 HTTP 联调入口位于测试源码 `src/test/java/com/yanji/ShopLocalServer.java`，只导入商城 Controller/Service，显式排除数据库与 Flyway；它不会启动其他业务，也不用于部署。编译后用测试 classpath 启动：

```sh
mvn -B -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/shop-classpath.txt
java -cp "target/classes:target/test-classes:$(cat target/shop-classpath.txt)" com.yanji.ShopLocalServer \
  --server.address=127.0.0.1 --server.port=18091 \
  --app.shop.catalog-file=/absolute/path/to/local-catalog.json
```

启动前检查端口和进程归属；选空闲端口，不停止其他会话服务。内置商品资料和本地联调均不能证明生产已经部署；真实购买链接仍待配置。
