package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.workitem.api.CompanyTimeZonePort;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
class CompanyTimeZoneAdapter implements CompanyTimeZonePort {
    private final CompanyConfigurationQuery company;

    CompanyTimeZoneAdapter(CompanyConfigurationQuery company) {
        this.company = company;
    }

    @Override
    public ZoneId current() {
        return company.current().timezone();
    }
}
