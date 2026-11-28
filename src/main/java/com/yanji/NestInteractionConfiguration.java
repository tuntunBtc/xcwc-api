package com.yanji;

import java.util.Arrays;
import java.util.stream.Stream;
import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="nest-interactions.enabled", havingValue="true")
class NestInteractionConfiguration {
    @Bean FlywayConfigurationCustomizer nestTestMigrations() {
        return config -> config.locations(Stream.concat(Arrays.stream(config.getLocations()),
            Stream.of(new Location("classpath:db/nest-test"))).toArray(Location[]::new));
    }
}
