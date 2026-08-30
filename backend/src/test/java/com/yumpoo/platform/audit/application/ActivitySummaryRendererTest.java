package com.yumpoo.platform.audit.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ActivitySummaryRendererTest {
    private final ActivitySummaryRenderer renderer = new ActivitySummaryRenderer();

    @ParameterizedTest
    @CsvSource({
            "PROJECT_MOVED_TO_WORKSPACE,移动了项目所属工作区",
            "PRODUCT_LINKED_TO_PROJECT,关联了产品",
            "PRODUCT_UNLINKED_FROM_PROJECT,取消了产品关联"
    })
    void rendersDerivedCatalogTemplateCodes(String templateCode, String summary) {
        assertThat(renderer.render(templateCode, JsonNodeFactory.instance.objectNode()))
                .isEqualTo(summary);
    }
}
