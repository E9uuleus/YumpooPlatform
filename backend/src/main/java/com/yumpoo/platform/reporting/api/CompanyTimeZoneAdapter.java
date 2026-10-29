package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.workitem.api.CompanyTimeZonePort;
import org.springframework.stereotype.Component;
import java.time.ZoneId;

@Component
class CompanyTimeZoneAdapter implements CompanyTimeZonePort {
    private final CompanyConfigurationQuery company;
    CompanyTimeZoneAdapter(CompanyConfigurationQuery company) { this.company = company; }

    @Override public ZoneId current() { return company.current().timezone(); }
}
