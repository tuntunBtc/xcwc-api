package com.yanji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Isolated developer server: real shop HTTP implementation, no datasource or other API modules. */
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import({ShopController.class, ShopCatalogService.class})
public class ShopLocalServer {
    public static void main(String[] args) { SpringApplication.run(ShopLocalServer.class, args); }
}
