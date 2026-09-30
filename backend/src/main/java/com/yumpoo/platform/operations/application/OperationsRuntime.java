package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import com.yumpoo.platform.operations.application.OperationsModels.RuntimeSnapshot;
import java.util.List;

public interface OperationsRuntime {
    RuntimeSnapshot snapshot();
    List<MetricPoint> recent();
}
