package dev.soynx.heim.icon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.XmlDeclaration;
import org.jsoup.parser.Parser;

/**
 * Strips active content from SVG icons: scripts, embedded HTML, event handlers and script URLs.
 * Served icons additionally get {@code Content-Security-Policy: sandbox}, so this is defence in depth.
 */
public final class SvgSanitizer {

    private static final Set<String> FORBIDDEN_ELEMENTS =
            Set.of("script", "foreignobject", "iframe", "embed", "object", "handler", "listener");
    private static final List<String> FORBIDDEN_URL_SCHEMES = List.of("javascript:", "vbscript:", "data:text/html");

    private SvgSanitizer() {
    }

    /** Returns the sanitized SVG, or null if {@code content} is not a single SVG document. */
    public static byte[] sanitize(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replace("﻿", "");
        Document doc = Jsoup.parse(text, "", Parser.xmlParser());
        if (doc.children().size() != 1 || !localName(doc.child(0)).equals("svg")) {
            return null;
        }

        // Doctypes may declare entities, processing instructions may pull in stylesheets.
        List<Node> doomed = new ArrayList<>();
        doc.forEachNode(node -> {
            if (node instanceof DocumentType || node instanceof XmlDeclaration || node instanceof Comment
                    || node instanceof Element el && FORBIDDEN_ELEMENTS.contains(localName(el))) {
                doomed.add(node);
            }
        });
        doomed.forEach(Node::remove);

        for (Element el : doc.getAllElements()) {
            List<String> dangerous = new ArrayList<>();
            for (Attribute attribute : el.attributes()) {
                if (isDangerous(attribute)) {
                    dangerous.add(attribute.getKey());
                }
            }
            dangerous.forEach(el::removeAttr);
        }

        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);
        return doc.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    /** Lower-cased name without namespace prefix ({@code svg:script} → {@code script}). */
    private static String localName(Element el) {
        return stripPrefix(el.tagName().toLowerCase(Locale.ROOT));
    }

    private static boolean isDangerous(Attribute attribute) {
        if (stripPrefix(attribute.getKey().toLowerCase(Locale.ROOT)).startsWith("on")) {
            return true;
        }
        // Browsers ignore whitespace and control characters inside URL schemes ("java\tscript:"); checking
        // every attribute also covers animation values such as <set attributeName="href" to="javascript:...">.
        String value = attribute.getValue().replaceAll("[\\s\\p{Cntrl}]", "").toLowerCase(Locale.ROOT);
        return FORBIDDEN_URL_SCHEMES.stream().anyMatch(value::contains);
    }

    private static String stripPrefix(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
