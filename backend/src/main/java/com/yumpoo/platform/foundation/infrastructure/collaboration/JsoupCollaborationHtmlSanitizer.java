package com.yumpoo.platform.foundation.infrastructure.collaboration;

import com.yumpoo.platform.foundation.application.collaboration.CollaborationHtmlSanitizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public final class JsoupCollaborationHtmlSanitizer implements CollaborationHtmlSanitizer {
    public static final int MAX_HTML_LENGTH = 65_536;
    public static final int MAX_TEXT_LENGTH = 16_384;
    private static final String MENTION_SELECTOR = "span[data-type=mention]";
    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "em", "u", "s", "h2", "pre", "hr", "ul", "ol", "li",
                    "blockquote", "code", "a", "span", "table", "thead", "tbody", "tr", "th", "td")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addAttributes("span", "data-type", "data-mention-user-id")
            .addAttributes("span", "style")
            .addAttributes("p", "style", "dir")
            .addAttributes("h2", "style", "dir")
            .addAttributes("pre", "dir")
            .addAttributes("blockquote", "dir")
            .addAttributes("ul", "data-type")
            .addAttributes("li", "data-type", "data-checked", "dir")
            .addAttributes("ol", "start")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .preserveRelativeLinks(true);

    @Override
    public ParsedHtml parse(String untrustedHtml) {
        if (untrustedHtml == null) throw new IllegalArgumentException("bodyHtml must not be null");
        Document dirty = Jsoup.parseBodyFragment(untrustedHtml);
        validateMentionMarkup(dirty.body());
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        normalizeStructure(clean.body());
        clean.outputSettings(outputSettings());
        List<UUID> mentionIds = mentionIds(clean.body());
        return new ParsedHtml(clean.body().html(), mentionIds);
    }

    @Override
    public SanitizedHtml canonicalize(ParsedHtml parsed, Map<UUID, String> names) {
        Document document = Jsoup.parseBodyFragment(parsed.safeHtml());
        document.outputSettings(outputSettings());
        for (Element mention : document.select(MENTION_SELECTOR)) {
            UUID userId = UUID.fromString(mention.attr("data-mention-user-id"));
            String displayName = names.get(userId);
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("mentioned user is not available");
            }
            mention.clearAttributes();
            mention.attr("data-type", "mention");
            mention.attr("data-mention-user-id", userId.toString());
            mention.text("@" + displayName.strip());
        }
        normalizeStructure(document.body());
        String html = document.body().html();
        String text = document.body().text().strip();
        if (html.length() > MAX_HTML_LENGTH) throw new IllegalArgumentException("bodyHtml is too long");
        if (text.isEmpty()) throw new IllegalArgumentException("bodyHtml must contain text");
        if (text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("bodyText is too long");
        return new SanitizedHtml(html, text, parsed.mentionedUserIds());
    }

    private static void validateMentionMarkup(Element body) {
        for (Element span : body.select("span")) {
            boolean mention = "mention".equals(span.attr("data-type"));
            boolean hasId = span.hasAttr("data-mention-user-id");
            if (!mention && !hasId) continue;
            if (!mention || !hasId) throw new IllegalArgumentException("invalid mention markup");
            try {
                UUID.fromString(span.attr("data-mention-user-id"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid mention user id");
            }
        }
    }

    private static void normalizeStructure(Element body) {
        for (Element element : body.getAllElements()) {
            if (element.hasAttr("style")) normalizeStyle(element);
            if (element.hasAttr("dir") && !Set.of("ltr", "rtl").contains(element.attr("dir"))) element.removeAttr("dir");
            for (String name : List.of("colspan", "rowspan", "start")) {
                if (!element.hasAttr(name)) continue;
                try {
                    int value = Integer.parseInt(element.attr(name));
                    if (value < 1 || value > (name.equals("start") ? 10_000 : 20)) element.removeAttr(name);
                    else element.attr(name, String.valueOf(value));
                } catch (NumberFormatException ignored) { element.removeAttr(name); }
            }
        }
        for (Element list : body.select("ul[data-type]")) {
            if (!list.attr("data-type").equals("taskList")) list.removeAttr("data-type");
        }
        for (Element item : body.select("li")) {
            if (item.attr("data-type").equals("taskItem") && item.parent() != null
                    && item.parent().attr("data-type").equals("taskList")) {
                item.attr("data-checked", String.valueOf(item.attr("data-checked").equals("true")));
            } else {
                item.removeAttr("data-type").removeAttr("data-checked");
            }
        }
        for (Element span : new ArrayList<>(body.select("span"))) {
            if ("mention".equals(span.attr("data-type"))) span.removeAttr("style");
            else {
                span.removeAttr("data-type");
                if (!span.hasAttr("style")) span.unwrap();
            }
        }
        for (Element anchor : body.select("a[href]")) {
            if (!absoluteSafeLink(anchor.attr("href"))) {
                anchor.removeAttr("href");
                anchor.removeAttr("target");
                anchor.removeAttr("rel");
            } else {
                anchor.attr("target", "_blank");
                anchor.attr("rel", "nofollow noopener noreferrer");
            }
        }
    }

    private static void normalizeStyle(Element element) {
        Map<String, String> styles = new java.util.LinkedHashMap<>();
        for (String declaration : element.attr("style").split(";")) {
            String[] pair = declaration.split(":", 2);
            if (pair.length != 2) continue;
            String property = pair[0].strip().toLowerCase(Locale.ROOT);
            String value = pair[1].strip().toLowerCase(Locale.ROOT);
            boolean inline = element.tagName().equals("span");
            boolean allowed = inline && (property.equals("color") || property.equals("background-color")) && safeColor(value)
                    || inline && property.equals("font-size") && Set.of("16px", "18px", "24px", "32px", "36px", "48px").contains(value)
                    || !inline && property.equals("text-align") && Set.of("left", "center", "right").contains(value);
            if (allowed) styles.put(property, value);
        }
        element.removeAttr("style");
        if (!styles.isEmpty()) element.attr("style", styles.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(java.util.stream.Collectors.joining("; ")));
    }

    private static boolean safeColor(String value) {
        if (value.matches("#[0-9a-f]{3}([0-9a-f]{3})?")) return true;
        if (!value.matches("rgb\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*\\)")) return false;
        for (String channel : value.substring(4, value.length() - 1).split(",")) {
            if (Integer.parseInt(channel.strip()) > 255) return false;
        }
        return true;
    }

    private static boolean absoluteSafeLink(String value) {
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if ("mailto".equalsIgnoreCase(scheme)) return value.regionMatches(true, 0, "mailto:", 0, 7);
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.isAbsolute() && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static List<UUID> mentionIds(Element body) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Element mention : body.select(MENTION_SELECTOR)) {
            ids.add(UUID.fromString(mention.attr("data-mention-user-id")));
        }
        return List.copyOf(ids);
    }

    private static Document.OutputSettings outputSettings() {
        return new Document.OutputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.html);
    }
}
