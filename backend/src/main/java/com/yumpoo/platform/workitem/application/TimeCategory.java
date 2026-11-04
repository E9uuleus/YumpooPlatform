package com.yumpoo.platform.workitem.application;

/** 不关联项目与工作项的固定工时分类；库中以名称保存，增删须同步 V81/V84 的 CHECK 与 OpenAPI 枚举。 */
public enum TimeCategory {
    BUSINESS_TRIP, LEARNING, BUSINESS, MEETING,
    /** 迁入工时：只由管理员的迁入接口写入，成员不可选择、修改或删除。 */
    MIGRATED;

    public boolean userSelectable() { return this != MIGRATED; }
}
