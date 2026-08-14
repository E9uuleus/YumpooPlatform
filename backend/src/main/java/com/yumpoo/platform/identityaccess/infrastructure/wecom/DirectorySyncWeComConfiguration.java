package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncSettings;
import com.yumpoo.platform.identityaccess.application.directory.FullDirectoryScanCollector;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryGateway;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryProfileGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yumpoo.wecom.directory", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DirectorySyncWeComProperties.class)
public class DirectorySyncWeComConfiguration {

    @Bean
    DirectorySyncSettings directorySyncSettings(DirectorySyncWeComProperties properties) {
        properties.validateForEnabled();
        return new DirectorySyncSettings(properties.getPageSize(), properties.getLeaseDuration());
    }

    @Bean
    WeComDirectoryGateway directorySyncIdGateway(
            DirectorySyncWeComProperties properties,
            Clock clock
    ) {
        return new RestClientWeComDirectoryGateway(
                restClientBuilder(properties),
                properties.getCorpId(),
                properties.getDirectorySecret(),
                clock
        );
    }

    @Bean
    WeComDirectoryProfileGateway directorySyncProfileGateway(
            DirectorySyncWeComProperties properties,
            Clock clock
    ) {
        return new RestClientWeComDirectoryProfileGateway(
                restClientBuilder(properties),
                properties.getCorpId(),
                properties.getProfileSecret(),
                clock
        );
    }

    @Bean
    FullDirectoryScanCollector fullDirectoryScanCollector(
            WeComDirectoryGateway directorySyncIdGateway,
            DirectorySyncWeComProperties properties
    ) {
        return new FullDirectoryScanCollector(directorySyncIdGateway, properties.getPageSize());
    }

    private static RestClient.Builder restClientBuilder(DirectorySyncWeComProperties properties) {
        properties.validateForEnabled();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
