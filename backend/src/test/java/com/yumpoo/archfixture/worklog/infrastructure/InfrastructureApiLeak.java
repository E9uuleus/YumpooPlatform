package com.yumpoo.archfixture.worklog.infrastructure;

import com.yumpoo.archfixture.worklog.api.WorklogApiContract;

public final class InfrastructureApiLeak {

    private final WorklogApiContract apiContract;

    public InfrastructureApiLeak(WorklogApiContract apiContract) {
        this.apiContract = apiContract;
    }

    public WorklogApiContract apiContract() {
        return apiContract;
    }
}
