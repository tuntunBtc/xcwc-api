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

Maven 项目名与 artifactId 为 xcwc-api；部署产物继续为 target/xcwc.jar，与现有宝塔启动配置一致。

## 运行与测试

运行时用 DB_URL、DB_USERNAME、DB_PASSWORD 配置 MySQL；后端默认监听 127.0.0.1:8080，应用上下文为 /，接口前缀为 /api。

```sh
java -jar target/xcwc.jar --server.servlet.context-path=/
```

测试默认使用隔离 H2。只有指定一次性测试库时才设置 TEST_DB_URL、TEST_DB_USERNAME、TEST_DB_PASSWORD；测试会清空业务数据，不能连接真实业务数据库。

前端与部署说明目前仍在 /Users/beluga/Documents/ChatGPT/星辰；该目录的 npm run package:server 会读取本工程的构建产物。可用 XCWC_API_DIR 覆盖后端路径。

服务器部署路径：/www/server/xcwc/xcwc.jar；私密配置：/etc/xcwc/backend.env。目录迁移不修改服务器数据库或线上服务。

## 打卡合照

新增 V4 合照迁移、受控照片上传及本人相册查询；每张成功作品通过现有账号流水奖励 1 叽分，按 requestId 幂等。部署必须设置持久的 CHECKIN_STORAGE_DIR（默认 ./data/checkins，禁止映射为静态目录）。接口见 openapi.yaml，完整联调和部署说明见 ../docs/CHECKIN-IMPLEMENTATION.md。
