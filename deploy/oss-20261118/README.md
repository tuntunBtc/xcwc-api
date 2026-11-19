# 品牌图片 OSS 接入（待部署）

本目录记录部署准备，不能作为已上线证据。2026-11-18 已创建并授权角色、绑定当前 ECS；实际验收见工作区 docs/tasks/OSS备案前接入核验-2026-11-18.md。

- Bucket：`xcwc-brand-assets-20261118`；杭州、标准本地冗余、私有、阻止公共访问、AES256。
- RAM 策略：`ram-policy.json`；只读写 `content/*`，无删除或权限管理。
- 角色：`xcwc-assets-ecs`，已绑定当前应用 ECS `i-bp17py7klx5cbvo973ar`。
- 应用通过 ECS 角色获取临时凭证，强制 IMDSv2，不保存长期 AccessKey。

## 部署配置

```dotenv
CONTENT_STORAGE=oss
CONTENT_OSS_BUCKET=xcwc-brand-assets-20261118
CONTENT_OSS_REGION=cn-hangzhou
CONTENT_OSS_ENDPOINT=https://oss-cn-hangzhou-internal.aliyuncs.com
CONTENT_OSS_ROLE_NAME=xcwc-assets-ecs
CONTENT_OSS_PREFIX=content/
CONTENT_OSS_MIGRATE=false
```

容器需能访问 ECS 元数据服务和杭州 OSS 内网地址；先验证 IMDSv2 联通性，不为方便接入关闭元数据保护。

## 迁移与切换

1. 保存当前 jar、compose 配置、数据库与本地 content 目录备份。暂停后台素材写入，保持客户端读取旧服务。
2. 在隔离的应用启动实例中挂载现有 content 目录，配置 OSS，设置 `CONTENT_OSS_MIGRATE=true`。此实例不得对公网或 Nginx 接收业务流量。
3. 启动迁移逐条校验数据库中的 size/SHA256、本地原件与运行图片，按原 ID 写入 OSS，读取校验完全相同。已有同内容对象跳过，冲突则停止且不覆盖。迁移不删除本地文件、不改数据库 ID。
4. 保存迁移成功数量与数据库素材数量的核对证据后，将正式实例切换 OSS 配置（`CONTENT_OSS_MIGRATE=false`），验证旧图片、权限和真实后台上传。
5. 新上传必须完成原件、运行图和缩略图三次 OSS 写入，才写入素材元数据并返回成功。失败可能留下无引用对象，但不会生成成功素材记录。此轮不自动清理云端对象。

## 回退限制

切换后产生的新素材只在 OSS。若需回退到本地存储，必须先停写并把新增素材连同缩略图导回本地、核对 SHA256，再切换。不能直接切回旧 jar，否则新素材会丢失访问。原有本地备份完整保留。

## 后续 CDN

素材 ID 与现有 `/api/content/assets/{id}` 公开接口保持稳定。私有原件和草稿不配置公共缓存；后续应单独配置已发布资源的 CDN 域名、私有回源及访问策略。此轮不提前购买或开通 CDN，也不让私有 Bucket 匿名可读。打卡照片存储不在此轮范围。
