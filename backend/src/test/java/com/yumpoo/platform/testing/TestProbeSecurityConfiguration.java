package com.yumpoo.platform.testing;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@TestConfiguration(proxyBeanMethods = false)
public class TestProbeSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain existingM0ProbeSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/__test/m0-*/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
