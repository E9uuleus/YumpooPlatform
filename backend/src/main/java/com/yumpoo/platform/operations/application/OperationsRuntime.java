package com.yumpoo.platform.operations.application;
import com.yumpoo.platform.operations.application.OperationsModels.*;
import java.util.List;
public interface OperationsRuntime {
    RuntimeSnapshot snapshot();
    List<MetricPoint> recent();
}
