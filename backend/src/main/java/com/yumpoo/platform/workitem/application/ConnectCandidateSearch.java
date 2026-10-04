package com.yumpoo.platform.workitem.application;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.yumpoo.platform.workitem.application.ConnectionAccess.invalid;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.CandidateField;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.CandidateSort;

/** Normalized candidate search input; an empty text browses the whole project instead of matching. */
public record ConnectCandidateSearch(String text, Set<CandidateField> fields, CandidateSort sort) {
    public static final int MAX_TEXT_LENGTH = 80;

    public ConnectCandidateSearch {
        fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
    }

    public static ConnectCandidateSearch of(String text, List<String> fields, String sort) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.length() > MAX_TEXT_LENGTH) throw invalid("q", "INVALID_LENGTH", "搜索词最多 80 个字符");
        return new ConnectCandidateSearch(normalized, fields(fields), sort(sort));
    }

    private static Set<CandidateField> fields(List<String> values) {
        if (values == null || values.isEmpty()) return EnumSet.of(CandidateField.NAME);
        EnumSet<CandidateField> result = EnumSet.noneOf(CandidateField.class);
        for (String value : values) {
            try { result.add(CandidateField.valueOf(value.strip().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException | NullPointerException error) {
                throw invalid("fields", "INVALID_CANDIDATE_FIELD", "搜索字段无效");
            }
        }
        return result;
    }

    private static CandidateSort sort(String value) {
        if (value == null || value.isBlank()) return CandidateSort.RECENT;
        try { return CandidateSort.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw invalid("sort", "INVALID_CANDIDATE_SORT", "排序方式无效"); }
    }
}
