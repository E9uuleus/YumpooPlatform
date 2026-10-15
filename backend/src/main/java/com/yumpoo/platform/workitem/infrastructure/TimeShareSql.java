package com.yumpoo.platform.workitem.infrastructure;

final class TimeShareSql {
    private TimeShareSql() {}

    /** Allocated milliseconds; aggregate as FLOOR(COALESCE(SUM(...),0))::bigint. */
    static String allocated(String alias,String asOf) {
        return "GREATEST(0,EXTRACT(EPOCH FROM (COALESCE("+alias+".stopped_at,"+asOf+")-"+alias+".started_at))*1000)/"+alias+".concurrency";
    }
}
