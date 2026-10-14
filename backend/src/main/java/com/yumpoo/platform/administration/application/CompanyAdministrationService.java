package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.organization.api.CompanyConfigurationCommand;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class CompanyAdministrationService {
    private static final int DISPLAY_NAME_MAX_LENGTH = 200;

    private final CompanyConfigurationQuery company;
    private final CompanyConfigurationCommand commands;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;

    public CompanyAdministrationService(CompanyConfigurationQuery company, CompanyConfigurationCommand commands,
            SecurityAuditAppendPort audits, ObjectMapper json, Clock clock) {
        this.company = company; this.commands = commands; this.audits = audits; this.json = json; this.clock = clock;
    }

    @Transactional
    public CompanyConfigurationSnapshot rename(CurrentActor actor, long expectedVersion, String displayName) {
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw ApplicationException.validation(new FieldViolation("displayName", "INVALID_VALUE", "公司名称需为 1–200 个字符"));
        }
        CompanyConfigurationSnapshot before = company.current();
        Instant now = clock.instant();
        if (!commands.rename(name, expectedVersion, now)) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        audits.append(new SecurityAuditDraft(actor.companyId(), "company-renamed:" + (expectedVersion + 1),
                "COMPANY_RENAMED", SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), ProjectLifecycleGovernanceService.roleNames(actor)),
                "COMPANY", before.companyId().toString(), null,
                json.valueToTree(Map.of("displayName", before.displayName())), json.valueToTree(Map.of("displayName", name)),
                null, null, null, null, now));
        return company.current();
    }
}
