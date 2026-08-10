package com.yumpoo.platform.identityaccess.application.directory;

import java.util.List;

/** 只在受信任应用边界内流转的原始企业微信成员 ID 页，禁止直接序列化。 */
public record WeComDirectoryPage(
        List<String> memberIds,
        String nextCursor,
        CursorState cursorState
) {

    private static final int MAX_IDENTIFIER_LENGTH = 256;
    private static final int MAX_CURSOR_LENGTH = 4096;

    public WeComDirectoryPage {
        if (memberIds == null) {
            throw new IllegalArgumentException("memberIds must not be null");
        }
        memberIds = List.copyOf(memberIds);
        for (String memberId : memberIds) {
            if (memberId == null || memberId.isBlank() || memberId.length() > MAX_IDENTIFIER_LENGTH) {
                throw new IllegalArgumentException("member ID has an invalid format");
            }
        }
        if (cursorState == null) {
            throw new IllegalArgumentException("cursorState must not be null");
        }
        switch (cursorState) {
            case NEXT -> {
                if (nextCursor == null || nextCursor.isBlank()) {
                    throw new IllegalArgumentException("next cursor has an invalid format");
                }
                if (nextCursor.length() > MAX_CURSOR_LENGTH) {
                    throw new IllegalArgumentException("next cursor is too long");
                }
            }
            case EXPLICIT_END -> {
                if (!"".equals(nextCursor)) {
                    throw new IllegalArgumentException("explicit end must use an empty cursor");
                }
            }
            case OMITTED -> {
                if (nextCursor != null) {
                    throw new IllegalArgumentException("omitted cursor must be null");
                }
            }
        }
    }

    public static WeComDirectoryPage next(List<String> memberIds, String nextCursor) {
        return new WeComDirectoryPage(memberIds, nextCursor, CursorState.NEXT);
    }

    public static WeComDirectoryPage explicitEnd(List<String> memberIds) {
        return new WeComDirectoryPage(memberIds, "", CursorState.EXPLICIT_END);
    }

    public static WeComDirectoryPage omitted(List<String> memberIds) {
        return new WeComDirectoryPage(memberIds, null, CursorState.OMITTED);
    }

    public boolean hasNextPage() {
        return cursorState == CursorState.NEXT;
    }

    public boolean hasExplicitEnd() {
        return cursorState == CursorState.EXPLICIT_END;
    }

    public boolean hasOmittedCursor() {
        return cursorState == CursorState.OMITTED;
    }

    @Override
    public String toString() {
        return "WeComDirectoryPage[memberCount=" + memberIds.size()
                + ", cursorState=" + cursorState + "]";
    }

    public enum CursorState {
        NEXT,
        EXPLICIT_END,
        OMITTED
    }
}
