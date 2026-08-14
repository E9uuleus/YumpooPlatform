package com.yumpoo.platform.audit.application;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class SecurityAuditService {

    private final SecurityAuditRepository repository;

    public SecurityAuditService(SecurityAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(SecurityAuditRecord record) {
        RequestCorrelation correlation = RequestCorrelationContext.required();
        return repository.append(record, correlation.requestId(), correlation.correlationId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID appendIndependent(SecurityAuditRecord record) {
        RequestCorrelation correlation = RequestCorrelationContext.required();
        return repository.append(record, correlation.requestId(), correlation.correlationId());
    }

    @Transactional(readOnly = true)
    public SecurityAuditResultPage findByRequestId(
            UUID companyId, String requestId, int page, int size
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size between 1 and 100");
        }
        long total = repository.countByRequestId(companyId, requestId);
        List<SecurityAuditStoredEvent> items = repository.findByRequestId(
                companyId, requestId, Math.multiplyExact(page, size), size);
        int pages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
        return new SecurityAuditResultPage(items, page, size, total, pages);
    }
}
