package com.yumpoo.platform.workitem.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Colour overrides for the default project structure, keyed by category, status and priority code; unlisted codes keep their defaults. */
public record InitialStructureColors(Map<String, String> categories, Map<String, String> statuses,
        Map<String, String> priorities) {
    public static final InitialStructureColors DEFAULT = new InitialStructureColors(Map.of(), Map.of(), Map.of());

    public InitialStructureColors {
        categories = copy(categories);
        statuses = copy(statuses);
        priorities = copy(priorities);
    }

    /** Keeps null colours (Map.copyOf would throw) so palette validation rejects them with 422. */
    private static Map<String, String> copy(Map<String, String> colors) {
        return colors == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(colors));
    }
}
