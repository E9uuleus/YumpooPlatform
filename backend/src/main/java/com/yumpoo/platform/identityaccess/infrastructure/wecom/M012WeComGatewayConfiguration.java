package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.oauth.WeComIdentityGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("m0-12-live")
@ConditionalOnProperty(prefix = "yumpoo.m012.wecom", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(M012WeComProperties.class)
public class M012WeComGatewayConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    WeComIdentityGateway weComIdentityGateway(
            M012WeComProperties properties,
            Clock clock
    ) {
        properties.validateForEnabled();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory);
        return new RestClientWeComIdentityGateway(builder, properties, clock);
    }
}
