package com.yumpoo.platform.templateworkflow.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.Lifecycle.DRAFT;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.ProjectType.PRODUCT_DEVELOPMENT;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.RequiredPermission.MEMBER;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.StatusCategory.CANCELED;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.StatusCategory.DONE;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.StatusCategory.IN_PROGRESS;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.StatusCategory.TODO;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.TemplateKey.RND;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.ViewType.TABLE;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkItemType.DEFECT;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkItemType.REQUIREMENT;
import static com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkItemType.TASK;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTemplateDefinitionValidatorTest {

    private final ProjectTemplateDefinitionValidator validator = new ProjectTemplateDefinitionValidator();

    @Test
    void acceptsCompleteReachableCatalogWithCancelEdgesAndTerminalSinks() {
        assertThatCode(() -> validator.validateForPublish(validDefinition()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingBlueprintDuplicateInitialUnknownEndpointAndUnreachableStatus() {
        ProjectTemplateDefinition valid = validDefinition();

        assertInvalid(copy(valid, valid.contentBlueprints().subList(0, 2), valid.statuses(), valid.transitions()),
                "三类 Content blueprint");

        List<ProjectTemplateDefinition.WorkflowStatus> duplicateInitial = valid.statuses().stream()
                .map(status -> status.statusCode().equals("READY")
                        ? new ProjectTemplateDefinition.WorkflowStatus(
                                status.statusCode(), status.displayName(), status.statusCategory(),
                                status.sortOrder(), true, status.terminal())
                        : status)
                .toList();
        assertInvalid(copy(valid, valid.contentBlueprints(), duplicateInitial, valid.transitions()),
                "一个初始状态");

        List<ProjectTemplateDefinition.WorkflowTransition> unknownEndpoint = new ArrayList<>(valid.transitions());
        unknownEndpoint.add(new ProjectTemplateDefinition.WorkflowTransition("READY", "UNKNOWN", MEMBER, false));
        assertInvalid(copy(valid, valid.contentBlueprints(), valid.statuses(), unknownEndpoint),
                "引用当前模板");

        List<ProjectTemplateDefinition.WorkflowTransition> unreachable = valid.transitions().stream()
                .filter(edge -> !(edge.fromStatus().equals("READY") && edge.toStatus().equals("DONE")))
                .toList();
        assertInvalid(copy(valid, valid.contentBlueprints(), valid.statuses(), unreachable),
                "从初始状态可达");
    }

    @Test
    void rejectsTerminalOutgoingEdgeAndMissingCancelEdge() {
        ProjectTemplateDefinition valid = validDefinition();
        List<ProjectTemplateDefinition.WorkflowTransition> terminalOutgoing = new ArrayList<>(valid.transitions());
        terminalOutgoing.add(new ProjectTemplateDefinition.WorkflowTransition("DONE", "CANCELED", MEMBER, false));
        assertInvalid(copy(valid, valid.contentBlueprints(), valid.statuses(), terminalOutgoing),
                "终态不能存在迁移出边");

        List<ProjectTemplateDefinition.WorkflowTransition> missingCancel = valid.transitions().stream()
                .filter(edge -> !(edge.fromStatus().equals("READY") && edge.toStatus().equals("CANCELED")))
                .toList();
        assertInvalid(copy(valid, valid.contentBlueprints(), valid.statuses(), missingCancel),
                "每个非终态");
    }

    private static ProjectTemplateDefinition validDefinition() {
        return new ProjectTemplateDefinition(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                RND, 1, "RND_V1", PRODUCT_DEVELOPMENT, "产品研发", DRAFT, 0,
                null, null,
                List.of(
                        new ProjectTemplateDefinition.ContentBlueprint("REQUIREMENTS", "需求", REQUIREMENT, TABLE, 10),
                        new ProjectTemplateDefinition.ContentBlueprint("TASKS", "任务", TASK, TABLE, 20),
                        new ProjectTemplateDefinition.ContentBlueprint("DEFECTS", "缺陷", DEFECT, TABLE, 30)),
                List.of(
                        new ProjectTemplateDefinition.WorkflowStatus("BACKLOG", "待规划", TODO, 10, true, false),
                        new ProjectTemplateDefinition.WorkflowStatus("READY", "就绪", IN_PROGRESS, 20, false, false),
                        new ProjectTemplateDefinition.WorkflowStatus("DONE", "完成", DONE, 30, false, true),
                        new ProjectTemplateDefinition.WorkflowStatus("CANCELED", "取消", CANCELED, 40, false, true)),
                List.of(
                        new ProjectTemplateDefinition.WorkflowTransition("BACKLOG", "READY", MEMBER, false),
                        new ProjectTemplateDefinition.WorkflowTransition("BACKLOG", "CANCELED", MEMBER, false),
                        new ProjectTemplateDefinition.WorkflowTransition("READY", "DONE", MEMBER, false),
                        new ProjectTemplateDefinition.WorkflowTransition("READY", "CANCELED", MEMBER, false)));
    }

    private static ProjectTemplateDefinition copy(
            ProjectTemplateDefinition source,
            List<ProjectTemplateDefinition.ContentBlueprint> blueprints,
            List<ProjectTemplateDefinition.WorkflowStatus> statuses,
            List<ProjectTemplateDefinition.WorkflowTransition> transitions
    ) {
        return new ProjectTemplateDefinition(
                source.id(), source.templateKey(), source.version(), source.versionCode(),
                source.projectType(), source.displayName(), source.lifecycle(), source.rowVersion(),
                source.publishedAt(), source.retiredAt(), blueprints, statuses, transitions);
    }

    private void assertInvalid(ProjectTemplateDefinition definition, String message) {
        assertThatThrownBy(() -> validator.validateForPublish(definition))
                .isInstanceOf(InvalidProjectTemplateDefinitionException.class)
                .hasMessageContaining(message);
    }
}
