package com.yumpoo.platform.foundation.infrastructure.collaboration;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsoupCollaborationHtmlSanitizerTest {
    private static final UUID USER_ID = UUID.fromString("35000000-0000-4000-8000-000000000007");
    private final JsoupCollaborationHtmlSanitizer sanitizer = new JsoupCollaborationHtmlSanitizer();

    @Test
    void stripsExecutableAndUnselectedMarkupButKeepsText() {
        var parsed = sanitizer.parse("""
                <h1 style="color:red" onclick="evil()">标题</h1>
                <p>正文<script>alert(1)</script><img src=x onerror=evil()></p>
                <table><tr><td>表格文字</td></tr></table>
                """);
        var result = sanitizer.canonicalize(parsed, Map.of());

        assertThat(result.bodyHtml()).doesNotContain("script", "onclick", "style", "img", "table")
                .contains("标题", "正文", "表格文字");
        assertThat(result.bodyText()).contains("标题", "正文", "表格文字");
    }

    @Test
    void keepsOnlyAbsoluteSafeLinksAndEnforcesBrowserAttributes() {
        var parsed = sanitizer.parse("""
                <p><a href="https://example.com/a">安全</a>
                <a href="mailto:owner@example.com">邮件</a>
                <a href="/relative">相对</a>
                <a href="java&#x09;script:alert(1)">混淆</a></p>
                """);
        var result = sanitizer.canonicalize(parsed, Map.of());

        assertThat(result.bodyHtml()).contains("href=\"https://example.com/a\"",
                        "href=\"mailto:owner@example.com\"", "target=\"_blank\"",
                        "rel=\"nofollow noopener noreferrer\"")
                .doesNotContain("/relative", "javascript");
    }

    @Test
    void canonicalizesMentionsAndDeduplicatesIdsInDocumentOrder() {
        String html = "<p>你好 <span data-type=\"mention\" data-mention-user-id=\"" + USER_ID
                + "\">@伪造名</span><span data-type=\"mention\" data-mention-user-id=\""
                + USER_ID + "\">@再次伪造</span> 👋</p>";
        var parsed = sanitizer.parse(html);
        var result = sanitizer.canonicalize(parsed, Map.of(USER_ID, "权威用户"));

        assertThat(result.mentionedUserIds()).containsExactly(USER_ID);
        assertThat(result.bodyHtml()).contains("@权威用户").doesNotContain("伪造名");
        assertThat(result.bodyText()).isEqualTo("你好 @权威用户@权威用户 👋");
    }

    @Test
    void rejectsMalformedMentionAndEmptyOrOversizedCleanText() {
        assertThatThrownBy(() -> sanitizer.parse(
                "<span data-type=\"mention\" data-mention-user-id=\"not-a-uuid\">x</span>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.canonicalize(sanitizer.parse("<script>x</script>"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.canonicalize(
                sanitizer.parse("<p>" + "字".repeat(16_385) + "</p>"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTextAndHtmlAtTheirBoundaries() {
        String text = "字".repeat(16_384);
        var result = sanitizer.canonicalize(sanitizer.parse("<p>" + text + "</p>"), Map.of());
        assertThat(result.bodyText()).hasSize(16_384);
        assertThat(result.bodyHtml()).startsWith("<p>").endsWith("</p>");
    }
}
