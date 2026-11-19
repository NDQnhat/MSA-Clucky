package com.clucky.notificationservice.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Preserves email document structure while removing executable HTML and CSS.
 * Structural placeholders are protected as comments before parsing because the
 * HTML parser moves bare text tokens out of table elements.
 */
final class EmailHtmlSanitizer {
    private static final Pattern FULL_DOCUMENT = Pattern.compile(
            "(?is)^\\s*(?:<!doctype\\s+html[^>]*>\\s*)?<html(?:\\s|>)|^\\s*<!doctype\\s+html\\b");
    private static final Map<String, String> SLOTS = Map.of(
            "{{content}}", "CLUCKY_EMAIL_SLOT_CONTENT",
            "{{details}}", "CLUCKY_EMAIL_SLOT_DETAILS",
            "{{action}}", "CLUCKY_EMAIL_SLOT_ACTION",
            "{{notice}}", "CLUCKY_EMAIL_SLOT_NOTICE",
            "{{footer}}", "CLUCKY_EMAIL_SLOT_FOOTER");
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "html", "head", "body", "title", "meta", "style",
            "div", "span", "p", "br", "hr", "a", "img",
            "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u", "small",
            "blockquote", "ul", "ol", "li", "pre", "code", "sub", "sup",
            "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
            "colgroup", "col");
    private static final Set<String> DISCARD_TAGS = Set.of(
            "script", "iframe", "frame", "frameset", "object", "embed", "applet",
            "svg", "math", "form", "input", "textarea", "button", "select",
            "option", "link", "base", "video", "audio", "source", "picture",
            "canvas", "template", "noscript", "foreignobject");
    private static final Set<String> GLOBAL_ATTRIBUTES = Set.of(
            "style", "class", "id", "title", "lang", "dir", "role",
            "align", "valign", "width", "height", "bgcolor", "border",
            "cellpadding", "cellspacing", "colspan", "rowspan", "scope",
            "nowrap", "summary");
    private static final Set<String> CSS_PROPERTIES = Set.of(
            "background", "background-color", "border", "border-top", "border-right",
            "border-bottom", "border-left", "border-color", "border-radius",
            "border-style", "border-width", "border-collapse", "border-spacing",
            "box-sizing", "color", "display", "font", "font-family", "font-size",
            "font-style", "font-weight", "line-height", "letter-spacing",
            "text-align", "text-decoration", "text-transform", "text-indent",
            "vertical-align", "white-space", "word-break", "word-wrap",
            "overflow", "opacity", "visibility", "max-height", "max-width",
            "min-height", "min-width", "height", "width", "margin",
            "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding", "padding-top", "padding-right", "padding-bottom",
            "padding-left", "table-layout", "list-style", "list-style-type",
            "mso-hide", "-webkit-text-size-adjust", "float");
    private static final Pattern UNSAFE_CSS = Pattern.compile(
            "(?i)(?:url|expression|image-set|var|attr|eval)\\s*\\(|"
            + "(?:javascript|vbscript|data)\\s*:|"
            + "(?:-moz-binding|behavior|binding|@import|@font-face|@namespace|@document|@supports)\\b");
    private static final Pattern CSS_VALUE = Pattern.compile("[a-zA-Z0-9#.,%()!+'\"/ _-]{1,1000}");
    private static final Pattern CSS_PROPERTY = Pattern.compile("-?[a-z][a-z0-9-]*");
    private static final Pattern CSS_AT_RULE = Pattern.compile("@(?!media\\b)", Pattern.CASE_INSENSITIVE);

    private EmailHtmlSanitizer() {}

    static boolean isFullDocument(String html) {
        return html != null && FULL_DOCUMENT.matcher(html).find();
    }

    static String sanitize(String html) {
        if (html == null || html.isBlank()) return null;
        boolean full = isFullDocument(html);
        String protectedHtml = html;
        for (var entry : SLOTS.entrySet()) {
            protectedHtml = protectedHtml.replace(entry.getKey(), "<!--" + entry.getValue() + "-->");
        }
        Document doc = full ? Jsoup.parse(protectedHtml) : Jsoup.parseBodyFragment(protectedHtml);
        doc.outputSettings().prettyPrint(false).escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
        sanitizeChildren(full ? doc : doc.body());
        String output = full ? doc.outerHtml() : doc.body().html();
        for (var entry : SLOTS.entrySet()) {
            output = output.replace("<!--" + entry.getValue() + "-->", entry.getKey());
        }
        return output;
    }

    private static void sanitizeChildren(Node parent) {
        for (Node node : new ArrayList<>(parent.childNodes())) {
            if (node instanceof Comment comment) {
                if (!SLOTS.containsValue(comment.getData())) {
                    comment.remove();
                } else if ("CLUCKY_EMAIL_SLOT_ACTION".equals(comment.getData())
                        && (!(comment.parent() instanceof Element owner)
                        || !Set.of("table", "tbody", "thead", "tfoot").contains(owner.normalName()))) {
                    throw new IllegalArgumentException("{{action}} must be a row inside an email table");
                }
                continue;
            }
            if (!(node instanceof Element element)) continue;
            String tag = element.normalName();
            if (DISCARD_TAGS.contains(tag)) {
                element.remove();
                continue;
            }
            if (!ALLOWED_TAGS.contains(tag)) {
                sanitizeChildren(element);
                element.unwrap();
                continue;
            }
            if ("html".equals(tag) && !(parent instanceof Document)
                    || Set.of("head", "body").contains(tag)
                    && (!(parent instanceof Element parentElement) || !"html".equals(parentElement.normalName()))) {
                // Nested document wrappers are not valid email content.
                sanitizeChildren(element);
                element.unwrap();
                continue;
            }
            if (Set.of("style", "meta", "title").contains(tag) && !hasAncestor(element, "head")) {
                element.remove();
                continue;
            }
            if ("style".equals(tag)) {
                String css = element.data();
                if (!safeCss(css) || css.contains("{{")) {
                    element.remove();
                } else {
                    element.empty().appendChild(new DataNode(css));
                }
                continue;
            }
            sanitizeAttributes(element);
            sanitizeChildren(element);
            if ("img".equals(tag) && !element.hasAttr("src")) element.remove();
        }
    }

    private static void sanitizeAttributes(Element element) {
        for (Attribute attr : new ArrayList<>(element.attributes().asList())) {
            String key = attr.getKey().toLowerCase(Locale.ROOT);
            String value = attr.getValue();
            if (value.contains("{{") || value.contains("}}")
                    || SLOTS.values().stream().anyMatch(value::contains)) {
                throw new IllegalArgumentException("Email placeholders must be in HTML text, not attributes");
            }
            if ("style".equals(key)) {
                String css = cleanInlineCss(value);
                if (css.isEmpty()) element.removeAttr(key);
                else element.attr(key, css);
                continue;
            }
            if ("meta".equals(element.normalName())) {
                if (!Set.of("charset", "name", "content").contains(key)
                        || "name".equals(key) && !Set.of("viewport", "color-scheme", "format-detection")
                                .contains(value.toLowerCase(Locale.ROOT))) {
                    element.removeAttr(key);
                }
                continue;
            }
            if (("href".equals(key) && "a".equals(element.normalName()))
                    || ("src".equals(key) && "img".equals(element.normalName()))) {
                if (!safeUrl(value, "src".equals(key))) element.removeAttr(key);
                continue;
            }
            if ("target".equals(key) && "a".equals(element.normalName())) {
                if (!Set.of("_blank", "_self").contains(value)) element.removeAttr(key);
                continue;
            }
            if ("rel".equals(key) && "a".equals(element.normalName())) continue;
            if ("alt".equals(key) && "img".equals(element.normalName())) continue;
            if (GLOBAL_ATTRIBUTES.contains(key) || key.startsWith("aria-")
                    && key.matches("aria-[a-z-]{1,40}")) continue;
            element.removeAttr(key);
        }
    }

    private static boolean safeUrl(String value, boolean image) {
        if (value.isBlank() || value.chars().anyMatch(c -> c < 32 || c == 127)
                || value.contains("\\") || value.contains("{{")) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getUserInfo() != null) return false;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (image && "cid".equals(scheme)) return "clucky-logo-v1".equals(uri.getSchemeSpecificPart());
            if (!image && "mailto".equals(scheme)) {
                return uri.getSchemeSpecificPart().matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+");
            }
            return Set.of("http", "https").contains(scheme) && uri.getHost() != null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String cleanInlineCss(String css) {
        if (css.contains("{") || css.contains("}") || css.contains("@")
                || css.contains("\\") || css.contains("<") || css.contains(">")
                || css.contains("/*") || css.contains("*/")) return "";
        List<String> declarations = new ArrayList<>();
        for (String candidate : css.split(";")) {
            int colon = candidate.indexOf(':');
            if (colon < 1) continue;
            String property = candidate.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = candidate.substring(colon + 1).trim();
            if (CSS_PROPERTY.matcher(property).matches() && CSS_PROPERTIES.contains(property)
                    && CSS_VALUE.matcher(value).matches() && safeCss(value)) {
                declarations.add(property + ":" + value);
            }
        }
        return String.join(";", declarations);
    }

    private static boolean safeCss(String css) {
        return css.length() <= 20000
                && !css.contains("\\") && !css.contains("<") && !css.contains(">")
                && !css.contains("/*") && !css.contains("*/")
                && !UNSAFE_CSS.matcher(css).find()
                && !CSS_AT_RULE.matcher(css).find();
    }

    private static boolean hasAncestor(Element element, String tag) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (tag.equals(parent.normalName())) return true;
        }
        return false;
    }
}
