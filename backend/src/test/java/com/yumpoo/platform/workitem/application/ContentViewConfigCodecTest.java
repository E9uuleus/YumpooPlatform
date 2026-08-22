package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.yumpoo.platform.workitem.application.ContentViewConfig.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentViewConfigCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContentViewConfigCodec codec = new ContentViewConfigCodec(objectMapper);

    @Test
    void expandsLegacyEmptyObjectToStableDefaults() {
        ContentViewConfig config = codec.read("{}", statuses());
        assertThat(config.table().columnOrder()).containsExactly(TableColumn.values());
        assertThat(config.table().hiddenColumns()).containsExactly(
                TableColumn.REPORTER, TableColumn.DESCRIPTION, TableColumn.NOTES, TableColumn.TIMELINE);
        assertThat(config.table().sort()).containsExactly(new Sort(SortField.UPDATED_AT, SortDirection.DESC));
        assertThat(config.kanban().statusGroups()).extracting(StatusGroup::name)
                .containsExactly("待办", "处理中", "完成");
    }

    @Test
    void appendsMissingColumnsAndAcceptsExactKanbanPartition() throws Exception {
        ContentViewConfig config = codec.normalize(objectMapper.readTree("""
                {"table":{"columnOrder":["TITLE","STATUS"],"hiddenColumns":[],"sort":[],"filters":{}},
                 "kanban":{"statusGroups":[{"name":"未完成","statusCodes":["TODO","DOING"]},{"name":"完成","statusCodes":["DONE"]}]}}
                """), statuses());
        assertThat(config.table().columnOrder()).startsWith(TableColumn.TITLE, TableColumn.STATUS)
                .containsExactlyInAnyOrder(TableColumn.values());
        assertThat(config.kanban().statusGroups()).hasSize(2);
    }

    @Test
    void rejectsUnknownFieldsHiddenTitleAndInvalidStatusPartition() throws Exception {
        assertValidation("{\"unknown\":true}", "viewConfig");
        assertValidation("{\"table\":{\"hiddenColumns\":[\"TITLE\"]}}",
                "viewConfig.table.hiddenColumns");
        assertValidation("{\"kanban\":{\"statusGroups\":[{\"name\":\"待办\",\"statusCodes\":[\"TODO\"]}]}}",
                "viewConfig.kanban.statusGroups");
    }

    private void assertValidation(String json, String field) throws Exception {
        assertThatThrownBy(() -> codec.normalize(objectMapper.readTree(json), statuses()))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
                    assertThat(exception.fieldViolations()).extracting(value -> value.field())
                            .contains(field);
                });
    }

    private static List<ProjectTemplateSnapshot.WorkflowStatus> statuses() {
        return List.of(new ProjectTemplateSnapshot.WorkflowStatus("TODO", "待办", "TODO", 1, true, false),
                new ProjectTemplateSnapshot.WorkflowStatus("DOING", "处理中", "IN_PROGRESS", 2, false, false),
                new ProjectTemplateSnapshot.WorkflowStatus("DONE", "完成", "DONE", 3, false, true));
    }
}
