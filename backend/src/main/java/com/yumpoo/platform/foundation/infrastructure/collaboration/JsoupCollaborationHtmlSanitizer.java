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
import java.util.UUID;

@Component
public final class JsoupCollaborationHtmlSanitizer implements CollaborationHtmlSanitizer {
    public static final int MAX_HTML_LENGTH = 65_536;
    public static final int MAX_TEXT_LENGTH = 16_384;
    private static final String MENTION_SELECTOR = "span[data-type=mention]";
    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "em", "ul", "ol", "li", "blockquote", "code", "a", "span")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addAttributes("span", "data-type", "data-mention-user-id")
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
        for (Element span : new ArrayList<>(body.select("span"))) {
            if (!"mention".equals(span.attr("data-type"))) span.unwrap();
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
