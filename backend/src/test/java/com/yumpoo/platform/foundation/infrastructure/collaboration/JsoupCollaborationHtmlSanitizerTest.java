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

        assertThat(result.bodyHtml()).doesNotContain("script", "onclick", "style", "img", "h1")
                .contains("标题", "正文", "表格文字");
        assertThat(result.bodyText()).contains("标题", "正文", "表格文字");
    }

    @Test
    void preservesSupportedFormattingAndCanonicalTaskStateAcrossRepeatedEdits() {
        String html = """
                <h2 dir="rtl" style="text-align: center">标题</h2>
                <p><span style="color: #ff0000; background-color: rgb(0, 255, 16); font-size: 24px"><strong><em><u><s>样式😀</s></u></em></strong></span><code>行内</code></p>
                <blockquote><p>引用</p></blockquote><pre><code>代码\n第二行</code></pre><hr>
                <ol start="3"><li><p>编号</p></li></ol><ul><li><p>项目</p></li></ul>
                <table><tbody><tr><th colspan="2" rowspan="1"><p>表头</p></th></tr><tr><td><p>内容</p></td><td><p>单元格</p></td></tr></tbody></table>
                <ul data-type="taskList"><li data-type="taskItem" data-checked="true"><p>完成</p></li><li data-type="taskItem" data-checked="false"><p>待办</p></li></ul>
                """;
        var first = sanitizer.canonicalize(sanitizer.parse(html), Map.of());
        var second = sanitizer.canonicalize(sanitizer.parse(first.bodyHtml()), Map.of());
        assertThat(second).isEqualTo(first);
        assertThat(first.bodyHtml()).contains("<h2", "dir=\"rtl\"", "text-align: center", "color: #ff0000",
                "background-color: rgb(0, 255, 16)", "font-size: 24px", "<u><s>", "<pre><code>", "<hr>",
                "<table>", "colspan=\"2\"", "start=\"3\"", "data-checked=\"true\"", "data-checked=\"false\"");
    }

    @Test
    void removesUnsafeStylesAttributesAndForgedTaskControls() {
        var result = sanitizer.canonicalize(sanitizer.parse("""
                <p dir="auto" style="text-align: center; position: fixed; background: url(javascript:evil)">
                <span onclick="evil()" class="overlay" style="color: rgb(256, 0, 0); font-size: 999px; background-color: expression(evil)">正文</span>
                <span style="color: #fff; font-size: 18px; opacity: 0">受限样式</span></p>
                <ul data-type="evil"><li data-type="taskItem" data-checked="true"><input checked onclick="evil()">伪造</li></ul>
                <ul data-type="taskList"><li data-type="taskItem" data-checked="evil">未勾选</li></ul>
                <table><tr><td colspan="99999" rowspan="-1" style="position:fixed">表格</td></tr></table>
                """), Map.of());
        assertThat(result.bodyHtml()).doesNotContain("onclick", "class=", "position", "url(", "expression", "256", "999", "opacity", "input", "dir=", "rowspan", "colspan", "data-checked=\"true\"")
                .contains("text-align: center", "color: #fff; font-size: 18px", "data-checked=\"false\"");
        assertThatThrownBy(() -> sanitizer.canonicalize(sanitizer.parse(
                "<span data-type=mention data-mention-user-id='" + USER_ID + "'>伪造</span>"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
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
    void canonicalizesMentionsDeduplicatesIdsAndPreservesUnicode() {
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

        var nearHtmlLimit = sanitizer.canonicalize(
                sanitizer.parse("<p>x" + "<br>".repeat(16_382) + "</p>"), Map.of());
        assertThat(nearHtmlLimit.bodyHtml()).hasSize(65_536);
        assertThatThrownBy(() -> sanitizer.canonicalize(
                sanitizer.parse("<p>x" + "<br>".repeat(16_383) + "</p>"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
