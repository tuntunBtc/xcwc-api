# 若依上游锁定

来源 https://github.com/yangzongzhuan/RuoYi-Vue ，springboot3，提交 127ac73146272b71e070662043917d80585d5bae，MIT，保留 LICENSE。

保留 common/system 原始源码，父 POM 仅裁剪模块清单。实际启用管理用户/角色/菜单的领域类和 MyBatis Mapper；不扫描上游演示服务，不启用代码生成、任务执行、Druid 或 Redis 缓存。管理会话/权限适配在 xcwc-admin，使用数据库哈希会话与 BCrypt，兼容若依 Vue 3 登录/菜单契约。
