package com.yanji;
import org.springframework.context.annotation.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
@MapperScan("com.ruoyi.system.mapper")
public class AdminConfiguration {
    // Authentication is explicitly enforced by AdminAccess at each management service boundary.
    // Existing consumer controllers retain their independent bearer authentication.
    @Bean SecurityFilterChain legacyAndAdminSecurity(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }
}
