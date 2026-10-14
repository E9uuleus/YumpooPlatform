package com.yumpoo.platform.organization.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CompanyConfigurationCommandService {
    private final CompanyRepository repository;

    public CompanyConfigurationCommandService(CompanyRepository repository) { this.repository = repository; }

    @Transactional
    public boolean rename(String displayName, long expectedVersion, Instant now) {
        return repository.rename(repository.current().id(), displayName, expectedVersion, now);
    }
}
