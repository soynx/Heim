package dev.soynx.heim.icon;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Finds favicon candidates in a page and its web app manifest, best first: SVG, then the largest
 * raster icon. Pure parsing, no I/O.
 */
public final class FaviconFinder {

    /** Ranks above every raster size. */
    private static final int SVG_SCORE = 100_000;
    /** Apple touch icons without {@code sizes} are 180px by convention. */
    private static final int APPLE_TOUCH_DEFAULT = 180;
    /** Plain {@code rel=icon} without {@code sizes}: usually small, but often better than favicon.ico. */
    private static final int UNSIZED_DEFAULT = 32;

    private static final ObjectMapper JSON = new ObjectMapper();

    private FaviconFinder() {
    }

    /** An icon URL with its ranking score (higher is better). */
    public record Candidate(URI uri, int score) {
    }

    /** Icons declared via {@code <link rel>}; relative URLs resolve against the document's base URI. */
    public static List<Candidate> fromHtml(Document doc) {
        List<Candidate> result = new ArrayList<>();
        for (Element link : doc.select("link[rel][href]")) {
            Set<String> rel = tokens(link.attr("rel"));
            boolean apple = rel.contains("apple-touch-icon") || rel.contains("apple-touch-icon-precomposed");
            // "mask-icon" is a single-colour Safari pinned tab glyph and is not matched by the token "icon".
            if (!apple && !rel.contains("icon")) {
                continue;
            }
            resolve(link.absUrl("href")).ifPresent(uri -> result.add(new Candidate(uri,
                    score(uri, link.attr("type"), link.attr("sizes"), apple ? APPLE_TOUCH_DEFAULT : UNSIZED_DEFAULT))));
        }
        return result;
    }

    /** Absolute URL of the page's web app manifest, if it declares one. */
    public static Optional<URI> manifestUrl(Document doc) {
        return doc.select("link[rel][href]").stream()
                .filter(link -> tokens(link.attr("rel")).contains("manifest"))
                .findFirst()
                .flatMap(link -> resolve(link.absUrl("href")));
    }

    /** Icons listed in a web app manifest, resolved against the manifest's own URL. */
    public static List<Candidate> fromManifest(byte[] json, URI manifestUri) {
        List<Candidate> result = new ArrayList<>();
        JsonNode icons;
        try {
            icons = JSON.readTree(json).path("icons");
        } catch (IOException | RuntimeException e) {
            return result;
        }
        for (JsonNode icon : icons) {
            String src = icon.path("src").asText("");
            if (src.isBlank() || icon.path("purpose").asText("").toLowerCase(Locale.ROOT).contains("monochrome")) {
                continue;
            }
            URI uri;
            try {
                uri = manifestUri.resolve(src.trim());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (IconHttp.isHttp(uri)) {
                result.add(new Candidate(uri,
                        score(uri, icon.path("type").asText(""), icon.path("sizes").asText(""), UNSIZED_DEFAULT)));
            }
        }
        return result;
    }

    /** {@code <origin>/favicon.ico} of the given page. */
    public static URI fallback(URI page) {
        return URI.create(page.getScheme() + "://" + page.getRawAuthority() + "/favicon.ico");
    }

    /** Distinct URLs, best first; {@link #fallback} of {@code page} goes last. */
    public static List<URI> rank(List<Candidate> candidates, URI page) {
        Set<URI> ordered = new LinkedHashSet<>();
        candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::score).reversed())
                .forEach(c -> ordered.add(c.uri()));
        ordered.add(fallback(page));
        return List.copyOf(ordered);
    }

    static int score(URI uri, String type, String sizes, int unsizedDefault) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String sizeList = sizes.toLowerCase(Locale.ROOT).trim();
        if (type.toLowerCase(Locale.ROOT).contains("svg") || path.endsWith(".svg") || sizeList.equals("any")) {
            return SVG_SCORE;
        }
        int largest = Arrays.stream(sizeList.split("\\s+"))
                .mapToInt(FaviconFinder::width)
                .max()
                .orElse(0);
        return largest > 0 ? largest : unsizedDefault;
    }

    private static int width(String size) {
        int x = size.indexOf('x');
        if (x <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(size.substring(0, x));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Set<String> tokens(String rel) {
        return new HashSet<>(Arrays.asList(rel.toLowerCase(Locale.ROOT).trim().split("\\s+")));
    }

    private static Optional<URI> resolve(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(absoluteUrl.trim().replace(" ", "%20"));
            return IconHttp.isHttp(uri) && uri.getHost() != null ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
