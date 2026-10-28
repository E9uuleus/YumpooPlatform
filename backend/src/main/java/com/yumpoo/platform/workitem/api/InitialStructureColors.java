package com.yumpoo.platform.workitem.api;

import java.util.Map;

/** Colour overrides for the default project structure, keyed by category, status and priority code; unlisted codes keep their defaults. */
public record InitialStructureColors(Map<String, String> categories, Map<String, String> statuses,
        Map<String, String> priorities) {
    public static final InitialStructureColors DEFAULT = new InitialStructureColors(Map.of(), Map.of(), Map.of());

    public InitialStructureColors {
        categories = categories == null ? Map.of() : Map.copyOf(categories);
        statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
        priorities = priorities == null ? Map.of() : Map.copyOf(priorities);
    }
}
