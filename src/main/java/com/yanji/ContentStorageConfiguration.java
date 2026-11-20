package com.yanji;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.*;
import com.aliyun.oss.common.comm.SignVersion;
import java.nio.file.Path;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class ContentStorageConfiguration {
    @Bean public ContentAssetStore contentAssetStore(Environment env) throws Exception {
        String type = env.getProperty("management-content.storage", "local");
        if ("local".equals(type)) return new LocalContentAssetStore(Path.of(env.getProperty("management-content.directory", "./data/content")));
        if (!"oss".equals(type)) throw new IllegalArgumentException("Unknown content storage type");
        OSS client = ossClient(env);
        try {
            return new OssContentAssetStore(client, env.getRequiredProperty("management-content.oss.bucket"),
                env.getProperty("management-content.oss.prefix", "content/"));
        } catch (RuntimeException e) { client.shutdown(); throw e; }
    }

    static OSS ossClient(Environment env) throws Exception { return OssClientFactory.ossClient(env); }
}
