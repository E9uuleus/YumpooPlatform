package com.yumpoo.platform.audit.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ActivitySummaryRenderer {
    public String render(String templateCode, JsonNode parameters) {
        String ref = text(parameters, "entityRef", "该对象");
        String person = text(parameters, "memberDisplayName", "成员");
        return switch (templateCode) {
            case "PRODUCT_CREATED" -> "创建了产品 " + ref;
            case "PRODUCT_UPDATED" -> "更新了产品 " + ref;
            case "PRODUCT_ARCHIVED" -> "归档了产品 " + ref;
            case "PRODUCT_RESTORED" -> "恢复了产品 " + ref;
            case "PRODUCT_OWNER_REASSIGNED" -> "调整了产品负责人：" + person;
            case "PROJECT_CREATED" -> "创建了项目 " + ref;
            case "PROJECT_UPDATED" -> "更新了项目信息";
            case "PROJECT_ACTIVATED" -> "激活了项目";
            case "PROJECT_ARCHIVED" -> "归档了项目";
            case "PROJECT_REOPENED" -> "重新开启了项目";
            case "PROJECT_MOVED_TO_WORKSPACE" -> "移动了项目所属工作区";
            case "PROJECT_TEMPLATE_APPLIED" -> "应用了项目模板";
            case "PROJECT_MEMBER_ADDED" -> "添加了项目成员：" + person;
            case "PROJECT_MEMBER_REMOVED" -> "移除了项目成员：" + person;
            case "PROJECT_OWNER_REASSIGNED" -> "调整了项目负责人：" + person;
            case "PRODUCT_LINKED_TO_PROJECT" -> "关联了产品";
            case "PROJECT_PRODUCT_LINK_UPDATED" -> "更新了产品关联";
            case "PRODUCT_UNLINKED_FROM_PROJECT" -> "取消了产品关联";
            case "CONTENT_CREATED" -> "创建了事项集合 " + ref;
            case "CONTENT_UPDATED" -> "更新了事项集合 " + ref;
            case "CONTENT_ARCHIVED" -> "归档了事项集合 " + ref;
            case "CONTENT_RESTORED" -> "恢复了事项集合 " + ref;
            case "WORK_ITEM_CREATED" -> "创建了事项 " + ref;
            case "WORK_ITEM_FIELDS_CHANGED" -> "更新了事项 " + ref;
            case "WORK_ITEM_ASSIGNED" -> "将事项 " + ref + " 指派给 " + person;
            case "WORK_ITEM_UNASSIGNED" -> "取消了事项 " + ref + " 的指派";
            case "WORK_ITEM_STATUS_CHANGED" -> "将事项 " + ref + " 的状态改为 "
                    + text(parameters, "toStatus", "新状态");
            case "WORK_ITEM_RANK_CHANGED" -> "调整了事项 " + ref + " 的顺序";
            case "WORK_ITEM_DELETED" -> "删除了事项 " + ref;
            case "WORK_ITEM_RESTORED" -> "恢复了事项 " + ref;
            case "WORK_ITEM_RELATION_CREATED" -> "创建了事项关系";
            case "WORK_ITEM_RELATION_DELETED" -> "解除事项关系";
            case "WORK_ITEM_PARENT_CHANGED" -> "更换事项父项";
            case "WORK_ITEM_UPDATE_PUBLISHED" -> "在事项 " + ref + " 发布了动态";
            case "WORK_ITEM_UPDATE_EDITED" -> "编辑了事项 " + ref + " 的动态";
            case "WORK_ITEM_UPDATE_DELETED" -> "删除了事项 " + ref + " 的动态";
            case "ATTACHMENT_AVAILABLE" -> "附件 " + ref + " 已可用";
            case "ATTACHMENT_DELETED" -> "删除了附件 " + ref;
            default -> "更新了 " + ref;
        };
    }

    private static String text(JsonNode parameters, String name, String fallback) {
        JsonNode value = parameters.path(name);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : fallback;
    }
}
