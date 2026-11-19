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
        String region = env.getRequiredProperty("management-content.oss.region");
        String endpoint = env.getRequiredProperty("management-content.oss.endpoint");
        if (!region.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid OSS region");
        String method = env.getProperty("management-content.oss.credentials", "ecs");
        CredentialsProvider provider;
        switch (method) {
            case "ecs" -> {
                if (!endpoint.equals("https://oss-" + region + "-internal.aliyuncs.com"))
                    throw new IllegalArgumentException("ECS storage requires the regional HTTPS internal endpoint");
                provider = ecsCredentials(env);
            }
            case "local-sts" -> {
                if (!endpoint.equals("https://oss-" + region + ".aliyuncs.com"))
                    throw new IllegalArgumentException("Local OSS requires the regional HTTPS endpoint");
                provider = new DefaultCredentialProvider(
                    requiredSecret(env, "CONTENT_OSS_STS_KEY_ID"),
                    requiredSecret(env, "CONTENT_OSS_STS_KEY_SECRET"),
                    requiredSecret(env, "CONTENT_OSS_STS_TOKEN"));
            }
            default -> throw new IllegalArgumentException("Unknown OSS credential mode");
        }
        var config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(5000); config.setSocketTimeout(15000);
        config.setConnectionRequestTimeout(5000); config.setMaxErrorRetry(2); config.setMaxConnections(32);
        OSS client = OSSClientBuilder.create().endpoint(endpoint).region(region)
            .credentialsProvider(provider).clientConfiguration(config).build();
        return new OssContentAssetStore(client, env.getRequiredProperty("management-content.oss.bucket"), env.getProperty("management-content.oss.prefix", "content/"));
    }

    private static String requiredSecret(Environment env, String name) {
        String value = env.getRequiredProperty(name);
        if (value.isBlank()) throw new IllegalArgumentException("Missing OSS credential: " + name);
        return value;
    }

    private static CredentialsProvider ecsCredentials(Environment env) throws Exception {
        var credentialConfig = new com.aliyun.credentials.models.Config()
            .setType("ecs_ram_role")
            .setRoleName(env.getRequiredProperty("management-content.oss.role-name"))
            .setDisableIMDSv1(true);
        var credentialClient = new com.aliyun.credentials.Client(credentialConfig);
        return new CredentialsProvider() {
            @Override public void setCredentials(Credentials ignored) { throw new UnsupportedOperationException("Use ECS role credentials"); }
            @Override public Credentials getCredentials() {
                var c = credentialClient.getCredential();
                return new DefaultCredentials(c.getAccessKeyId(), c.getAccessKeySecret(), c.getSecurityToken());
            }
        };
    }
}
