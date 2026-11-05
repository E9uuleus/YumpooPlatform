package com.yumpoo.platform.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@TestConfiguration(proxyBeanMethods = false)
class OpenApiResponseValidationConfiguration {

    @Bean
    FilterRegistrationBean<OpenApiResponseValidationFilter> openApiResponseValidationFilter() {
        FilterRegistrationBean<OpenApiResponseValidationFilter> registration =
                new FilterRegistrationBean<>(new OpenApiResponseValidationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
