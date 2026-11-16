package com.yanji;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProvisionCommand implements ApplicationRunner {
    private final RegistrationService service;
    private final BirdFactory factory;
    @Value("${app.seed-demo:false}") private boolean seedDemo;
    @Value("${app.provision-count:0}") private int count;
    @Value("${app.provision-output:provision-public.csv}") private String output;
    @Value("${app.provision-city:}") private String city;
    @Value("${app.provision-ip:}") private String ip;
    @Value("${app.provision-skin-id:}") private String skinId;
    @Value("${app.provision-skin-name:}") private String skinName;
    @Value("${app.provision-image:}") private String image;
    @Value("${app.provision-batch:}") private String batch;
    @Value("${spring.main.web-application-type:servlet}") private String webType;
    public ProvisionCommand(RegistrationService service, BirdFactory factory) { this.service = service; this.factory = factory; }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (seedDemo) {
            if (!webType.equals("none") || count != 0) throw new IllegalArgumentException("Demo seed requires web-application-type=none and no provision-count");
            service.seedDemo();
            System.out.println("已初始化 3 只旧版测试小鸟；已有记录保持不变，不生成新版正式编码。");
            return;
        }
        if (count == 0) return;
        if (!webType.equals("none") || count < 1 || count > 1000) throw new IllegalArgumentException("Provisioning requires web-application-type=none and count 1..1000");
        var spec = new BirdFactory.Spec(city,ip,skinId,skinName,image,batch);
        factory.validate(spec);
        Path file = Path.of(output);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, "bird_id,bird_code,link_suffix\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        for (int index = 0; index < count; index++) {
            var bird = factory.create(spec);
            Files.writeString(file, bird.id() + "," + bird.birdCode() + "," + bird.linkSuffix() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        System.out.println("小鸟已建档，公开 NFC 清单已写入指定文件。未生成或导出隐藏认领码。");
    }
}
