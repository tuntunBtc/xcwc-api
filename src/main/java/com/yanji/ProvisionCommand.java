package com.yanji;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProvisionCommand implements ApplicationRunner {
    private final RegistrationService service;
    @Value("${app.seed-demo:false}") private boolean seedDemo;
    @Value("${app.provision-count:0}") private int count;
    @Value("${app.provision-output:provision.csv}") private String output;
    @Value("${spring.main.web-application-type:servlet}") private String webType;
    public ProvisionCommand(RegistrationService service) { this.service = service; }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (seedDemo) {
            if (!webType.equals("none") || count != 0) throw new IllegalArgumentException("Demo seed requires web-application-type=none and no provision-count");
            service.seedDemo();
            System.out.println("已初始化 3 只测试小鸟；已有记录保持不变。测试码公开，禁止用于真实商品。");
            return;
        }
        if (count == 0) return;
        if (!webType.equals("none") || count < 1 || count > 1000) throw new IllegalArgumentException("Provisioning requires web-application-type=none and count 1..1000");
        Path file = Path.of(output);
        Files.writeString(file, "bird_id,hidden_claim_code,link_suffix\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        for (int index = 0; index < count; index++) {
            var bird = service.provision();
            Files.writeString(file, bird.id() + "," + bird.claimCode() + ",/register.html?bird=" + bird.id() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }
}
