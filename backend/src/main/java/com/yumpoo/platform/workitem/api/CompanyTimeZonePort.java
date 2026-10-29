package com.yumpoo.platform.workitem.api;

import java.time.ZoneId;

/** The company calendar zone; workitem may not read the organization module, so another module supplies it. */
public interface CompanyTimeZonePort {
    ZoneId current();
}
