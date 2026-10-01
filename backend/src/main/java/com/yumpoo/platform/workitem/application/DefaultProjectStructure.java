package com.yumpoo.platform.workitem.application;

import java.util.List;

public final class DefaultProjectStructure {
    public static final List<Category> CATEGORIES = List.of(
            new Category("REQUIREMENTS", "需求", "BRIGHT_BLUE", 10),
            new Category("TASKS", "任务", "BRIGHT_GREEN", 20),
            new Category("DEFECTS", "缺陷", "DARK_RED", 30));
    public static final List<Status> STATUSES = List.of(
            new Status("NOT_STARTED", "未开始", "GRAY", "TODO", 0, true),
            new Status("IN_PROGRESS", "进行中", "ORANGE", "IN_PROGRESS", 10, false),
            new Status("STUCK", "卡住", "RED", "IN_PROGRESS", 20, false),
            new Status("DONE", "已完成", "GREEN", "DONE", 30, false),
            new Status("CANCELED", "已取消", "AMERICAN_GRAY", "CANCELED", 40, false));
    public static final List<Priority> PRIORITIES = List.of(
            new Priority("LOW", "低", "BLUE", 10), new Priority("MEDIUM", "中", "TEAL", 20),
            new Priority("HIGH", "高", "ORANGE", 30), new Priority("URGENT", "紧急", "RED", 40));
    private DefaultProjectStructure() {}
    public record Category(String contentCode, String displayName, String colorToken, int sortOrder) {}
    public record Status(String code, String displayName, String colorToken, String category, int sortOrder,
                         boolean protectedLabel) {}
    public record Priority(String code, String displayName, String colorToken, int sortOrder) {}
}
