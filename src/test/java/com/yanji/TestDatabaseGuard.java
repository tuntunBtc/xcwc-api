package com.yanji;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Test classpath only. Check the actual connection before Flyway or fixture cleanup can run. */
public class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource source) {
                    try (Connection connection = source.getConnection()) {
                        if ("MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                            String catalog = connection.getCatalog();
                            if (catalog == null || Set.of("xcwh", "yanji").contains(catalog.toLowerCase(java.util.Locale.ROOT))) {
                                throw new IllegalStateException("Shared business schema is forbidden for automated tests");
                            }
                        }
                    } catch (SQLException ex) {
                        throw new IllegalStateException("Cannot verify test database isolation", ex);
                    }
                }
                return bean;
            }
        });
    }
}
