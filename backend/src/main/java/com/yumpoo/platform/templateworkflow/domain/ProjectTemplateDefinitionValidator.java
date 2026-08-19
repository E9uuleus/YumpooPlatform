package com.yumpoo.platform.templateworkflow.domain;

import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.ContentBlueprint;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.StatusCategory;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkflowStatus;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkflowTransition;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkItemType;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ProjectTemplateDefinitionValidator {

    public void validateForPublish(ProjectTemplateDefinition definition) {
        if (definition.contentBlueprints().size() != WorkItemType.values().length
                || !definition.contentBlueprints().stream()
                .map(ContentBlueprint::workItemType)
                .collect(() -> EnumSet.noneOf(WorkItemType.class), Set::add, Set::addAll)
                .equals(EnumSet.allOf(WorkItemType.class))) {
            reject("模板必须且只能包含 REQUIREMENT、TASK、DEFECT 三类 Content blueprint");
        }

        Map<String, WorkflowStatus> statuses = new HashMap<>();
        Set<Integer> statusOrders = new HashSet<>();
        WorkflowStatus initial = null;
        for (WorkflowStatus status : definition.statuses()) {
            if (statuses.put(status.statusCode(), status) != null || !statusOrders.add(status.sortOrder())) {
                reject("状态 code 和 sortOrder 必须唯一");
            }
            if (status.initial()) {
                if (initial != null) {
                    reject("模板必须且只能有一个初始状态");
                }
                initial = status;
            }
            if (status.terminal()
                    && status.statusCategory() != StatusCategory.DONE
                    && status.statusCategory() != StatusCategory.CANCELED) {
                reject("终态必须映射为 DONE 或 CANCELED");
            }
        }
        if (initial == null) {
            reject("模板必须且只能有一个初始状态");
        }
        if (statuses.values().stream().noneMatch(WorkflowStatus::terminal)) {
            reject("模板必须至少包含一个终态");
        }

        Map<String, Set<String>> outgoing = new HashMap<>();
        for (WorkflowTransition transition : definition.transitions()) {
            WorkflowStatus from = statuses.get(transition.fromStatus());
            WorkflowStatus to = statuses.get(transition.toStatus());
            if (from == null || to == null) {
                reject("迁移边必须引用当前模板中的状态");
            }
            if (from.terminal()) {
                reject("终态不能存在迁移出边");
            }
            if (!outgoing.computeIfAbsent(transition.fromStatus(), ignored -> new HashSet<>())
                    .add(transition.toStatus())) {
                reject("迁移边必须唯一");
            }
        }

        WorkflowStatus canceled = statuses.get("CANCELED");
        if (canceled == null || !canceled.terminal()
                || canceled.statusCategory() != StatusCategory.CANCELED) {
            reject("模板必须包含 CANCELED 终态");
        }
        for (WorkflowStatus status : statuses.values()) {
            if (!status.terminal()
                    && !outgoing.getOrDefault(status.statusCode(), Set.of()).contains("CANCELED")) {
                reject("每个非终态都必须允许迁移到 CANCELED");
            }
        }

        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(initial.statusCode());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (reachable.add(current)) {
                pending.addAll(outgoing.getOrDefault(current, Set.of()));
            }
        }
        if (!reachable.equals(statuses.keySet())) {
            reject("所有状态必须从初始状态可达");
        }
    }

    private static void reject(String message) {
        throw new InvalidProjectTemplateDefinitionException(message);
    }
}
