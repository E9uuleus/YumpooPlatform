package com.yumpoo.platform.audit.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ActivitySummaryRendererTest {
    private final ActivitySummaryRenderer renderer = new ActivitySummaryRenderer();

    @ParameterizedTest
    @CsvSource({
            "PROJECT_REOPENED,恢复了项目",
            "PROJECT_ENDED,结束了项目",
            "PROJECT_RESUMED,重新打开了项目",
            "PROJECT_MOVED_TO_WORKSPACE,移动了项目所属工作区",
            "WORK_ITEM_RELATION_DELETED,解除事项关系",
            "WORK_ITEM_PARENT_CHANGED,更换事项父项"
    })
    void rendersDerivedCatalogTemplateCodes(String templateCode, String summary) {
        assertThat(renderer.render(templateCode, JsonNodeFactory.instance.objectNode()))
                .isEqualTo(summary);
    }
}
