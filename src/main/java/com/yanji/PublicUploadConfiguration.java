package com.yanji;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class PublicUploadConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="management-upload.enabled", havingValue="true")
    public PublicUploadStore publicUploadStore(Environment env) throws Exception {
        var client = OssClientFactory.ossClient(env);
        try {
            return new OssPublicUploadStore(client, env.getRequiredProperty("management-content.oss.bucket"));
        } catch (RuntimeException e) { client.shutdown(); throw e; }
    }

    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="management-upload.enabled", havingValue="true")
    public PublicUploadVerifier publicUploadVerifier() { return new HttpsPublicUploadVerifier(); }
}
