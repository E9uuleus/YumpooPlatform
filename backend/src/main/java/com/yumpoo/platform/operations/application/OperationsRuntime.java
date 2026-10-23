package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import com.yumpoo.platform.operations.application.OperationsModels.RuntimeSnapshot;
import java.util.Optional;
import java.util.List;

public interface OperationsRuntime {
    RuntimeSnapshot snapshot();

    default Optional<RuntimeSnapshot> sampledSnapshot() {
        return Optional.empty();
    }
    List<MetricPoint> recent();
}
