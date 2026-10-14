package com.yumpoo.platform.organization.api;

import com.yumpoo.platform.organization.application.CompanyConfigurationCommandService;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OrganizationConfigurationCommandAdapter implements CompanyConfigurationCommand {
    private final CompanyConfigurationCommandService service;

    public OrganizationConfigurationCommandAdapter(CompanyConfigurationCommandService service) { this.service = service; }

    @Override
    public boolean rename(String displayName, long expectedVersion, Instant now) {
        return service.rename(displayName, expectedVersion, now);
    }
}
