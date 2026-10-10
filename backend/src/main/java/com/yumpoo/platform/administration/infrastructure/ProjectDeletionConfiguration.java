package com.yumpoo.platform.administration.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import com.yumpoo.platform.administration.application.ProjectDeletionSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProjectDeletionProperties.class)
public class ProjectDeletionConfiguration {
    public ProjectDeletionConfiguration(ProjectDeletionProperties properties) { properties.validate(); }
    @Bean
    ProjectDeletionSettings projectDeletionSettings(ProjectDeletionProperties properties) {
        return new ProjectDeletionSettings(properties.getGracePeriod(), properties.getReminderLead(), properties.getPurgeBatchSize());
    }
}
