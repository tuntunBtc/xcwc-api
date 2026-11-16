# xcwc-api

xcwc 后端 Maven 工程，Java 21 / Spring Boot。源码在 src/main/java/com/yanji，接口契约为 openapi.yaml。

## 构建

安装 JDK 21 和 Maven 后，在本目录运行：

```sh
mvn verify
```

或在本目录使用 Docker（不要求本机安装 JDK）：

```sh
docker run --rm -v "$PWD:/app" -v yanji-maven-cache:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -B verify
```

父 Maven 工程为 xcwc-backend，按 common/service/api/admin/server 分模块；可执行部署产物为 target/xcwc.jar。xcwc-server/target 下的普通模块 JAR 不用于启动。

## 运行与测试

运行时用 DB_URL、DB_USERNAME、DB_PASSWORD 配置 MySQL；后端默认监听 127.0.0.1:8080，应用上下文为 /，接口前缀为 /api。

```sh
java -jar target/xcwc.jar --server.servlet.context-path=/
```

测试默认使用隔离 H2。只有指定一次性测试库时才设置 TEST_DB_URL、TEST_DB_USERNAME、TEST_DB_PASSWORD；测试会清空业务数据，不能连接真实业务数据库。

正式前端位于 ../xcwc-web，需求与部署说明位于 ../docs。旧星辰工程发布脚本不作为当前配套发布入口。

服务器部署路径：/www/server/xcwc/xcwc.jar；私密配置：/etc/xcwc/backend.env。目录迁移不修改服务器数据库或线上服务。

## 打卡合照

新增 V4 合照迁移、受控照片上传及本人相册查询；每账号每天最多三张不同原图各奖励 1 叽分；同一原图终身不重复奖励。按 requestId 幂等，作品返回持久化 reward=0/1，达到上限仍可保存。部署必须设置持久的 CHECKIN_STORAGE_DIR（默认 ./data/checkins，禁止映射为静态目录）。接口见 openapi.yaml，完整联调和部署说明见 ../docs/CHECKIN-IMPLEMENTATION.md。

## 城市-IP CODE-雪花ID

V5 增加对外 birdCode，内部 id 及旧链接保持兼容。新增 GET /api/birds/by-code/{birdCode}；离线建档必须指定城市、角色、出厂皮肤、素材路径和批次，仅启用已确认的 HZ-HY-。操作和风险边界见 [编码实施说明](../docs/BIRD-CODES.md)。编码查询不凭前缀创建实体或发放权益。

## 云端绑定与奖励修复（2026-11-02）

V6 迁移旧 owner 为 bird_binding；同一实体可由多个账号绑定，昵称与穿戴独立。POST /api/birds/{id}/adopt 需登录，确认即免费孵化；重复提交恢复原结果。账号首次新领养 +5，历史已有绑定账号不追补。GET /api/me/birds/{id}/binding 返回本人状态，GET /api/auth/session 校验会话，固定七天到期。旧 claim 接口仅作兼容，不再验证隐藏码。

当前 H5 主流程已由并行任务调整为免登录本机体验：本地领取和本地叽分不写入以上云端接口，不自动迁移。后端鉴权继续保留。云端每日补给、喂食/添水/清洁/改名、每日问候/照料和周奖励仍未实现；不可将本机体验当作后端能力。

本轮后端 verify 39 项通过（38 项 MySQL 8.4，迁移专项 1 项独立 H2）。配套升级与迁移注意事项见 [升级说明](../docs/BACKEND-UPGRADE-20261102.md)，完整范围和验收见 [任务记录](../docs/tasks/领养登录与奖励矛盾修复-V1.1030-2026-11-02.md)。未执行生产部署。


## 小程序云端宠物后端（2026-11-11，本地交付）

新增 AppID + OpenID 身份隔离（V11）与云端宠物状态/操作回执/库存/补给流水（V12）。新小程序从 `/api/me/pets` 恢复本人宠物，通过 `/api/me/pets/adopt` 显式领养、`/api/me/pets/{petId}/actions` 提交业务操作；不上传整份本机存档。身份、绑定、昵称、普通档养成、每日账号补给、初见礼/摸摸奖励及清洁事件由服务端事务结算。

旧微信用户缺少 AppID 时，须核实来源后配置 `WX_LEGACY_APP_ID`；默认不猜填或跨应用合并。既有绑定首次接入云端状态时只初始化一次，不追补初见礼。实现和验证以 [任务记录](../docs/tasks/后端云端存档差距审查与首批方案-V1.1030-2026-11-11.md) 为准，前端按 [接口交接](../docs/handoffs/小程序云端宠物接口交接-2026-11-11.md) 适配。此前“每日补给/养成未实现”的历史段落不代表本次源码状态。

仅完成本地开发与隔离验证，未部署或执行生产迁移；小程序本机 store 尚未切换云端，企业微信双真机验收另行完成。


## 微信授权手机号登录（2026-11-14，本地交付）

新增 `POST /api/auth/wx-phone-login`，接收独立的微信身份码 `code` 和手机号授权码 `phoneCode`，核验后复用原用户及云端资产，返回登录会话和手机号掩码。V13 追加已验证手机号表，不按号码自动合并用户。`WX_PHONE_LOGIN_ENABLED=false` 可关闭新入口的后端能力；真实调用沿用 `WX_APP_ID`、`WX_APP_SECRET`，要求 `app.wx.debug=false`。前端“我的”页已本地接入，原微信登录保留。

本轮 H2 / 隔离 MySQL 8.4 各 103 项通过；未执行生产迁移、部署或真实手机号换码。接口以 `openapi.yaml` 为准，完整验证及微信平台待办见 [交接记录](../docs/tasks/小程序手机号授权登录-2026-11-14.md)。

## 多 IP 运营后台（2026-11-15）

后台管理前端在 admin-ui，运行 npm ci 与 npm run build:prod，产物 admin-ui/dist。后端依然 mvn verify；一个 Java 服务同时提供小程序 API 与管理 API。CONTENT_STORAGE_DIR 为受控持久素材目录，ADMIN_BOOTSTRAP_PASSWORD 只用于空管理库初始化，重启不会重置已有密码。生产密码不得写进源码、命令日志或 Git。

新增 V14 管理身份、发布、素材、实体角色关联与永久装扮权益，保留 V1–V13 和已有业务数据。完整接口、模块、部署入口、权限、备份与前端待办见[交接](../docs/handoffs/多IP运营后台接口与部署交接-2026-11-15.md)。本次部署记录优先于上方历史“未部署”描述。
