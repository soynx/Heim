package dev.soynx.heim.icon;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The homarr-labs dashboard-icons collection, served by jsDelivr. {@code metadata.json} maps each
 * slug to {@code {"base": "svg"|"png", "aliases": [...], ...}}; files live at {@code svg/<slug>.svg}
 * and {@code png/<slug>.png} (every slug has a PNG, SVG only where {@code base} is svg).
 */
@Component
public class IconLibrary {

    private static final Logger log = LoggerFactory.getLogger(IconLibrary.class);

    public static final String DEFAULT_BASE_URL = "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons";
    public static final int MAX_RESULTS = 50;

    private static final Duration INDEX_TTL = Duration.ofHours(24);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);
    private static final long MAX_INDEX_BYTES = 32L * 1024 * 1024;
    private static final Duration INDEX_TIMEOUT = Duration.ofSeconds(20);
    private static final String SLUG_PATTERN = "[a-z0-9][a-z0-9._-]{0,120}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IconHttp http;
    private final String baseUrl;

    private volatile Loaded loaded;
    private volatile Instant lastFailure;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public IconLibrary(IconHttp http, @Value("${heim.icons.library-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** One icon of the collection; {@code base} is the preferred file format. */
    public record Entry(String base, List<String> aliases) {
    }

    /** Search result as returned by {@code GET /api/icons/library}. */
    public record LibraryIcon(String slug, String name, String previewUrl) {
    }

    /** Matches on slug, display name and aliases; empty if the index is unavailable. */
    public List<LibraryIcon> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return rank(index(), query, baseUrl);
    }

    /** Download URLs for a slug, preferred format first. */
    public List<URI> downloadUrls(String slug) {
        if (slug == null || !slug.matches(SLUG_PATTERN) || slug.contains("..")) {
            return List.of();
        }
        Entry entry = index().get(slug);
        URI svg = URI.create(baseUrl + "/svg/" + slug + ".svg");
        URI png = URI.create(baseUrl + "/png/" + slug + ".png");
        // Unknown slug (index down or newer than our copy): try both.
        return entry == null || "svg".equals(entry.base()) ? List.of(svg, png) : List.of(png);
    }

    static List<LibraryIcon> rank(Map<String, Entry> index, String query, String baseUrl) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        String compactQ = compact(q);
        if (compactQ.isEmpty()) {
            return List.of();
        }
        record Hit(String slug, Entry entry, int rank) {
        }
        List<Hit> hits = new ArrayList<>();
        index.forEach((slug, entry) -> {
            int rank = matchRank(slug, entry, q, compactQ);
            if (rank >= 0) {
                hits.add(new Hit(slug, entry, rank));
            }
        });
        return hits.stream()
                .sorted(Comparator.comparingInt(Hit::rank)
                        .thenComparingInt(h -> h.slug().length())
                        .thenComparing(Hit::slug))
                .limit(MAX_RESULTS)
                .map(h -> new LibraryIcon(h.slug(), displayName(h.slug()), previewUrl(baseUrl, h.slug(), h.entry())))
                .toList();
    }

    /** 0 exact slug, 1 exact alias, 2 slug prefix, 3 alias or word prefix, 4/5 substring; -1 no match. */
    private static int matchRank(String slug, Entry entry, String q, String compactQ) {
        String compactSlug = compact(slug);
        List<String> aliases = entry.aliases().stream().map(a -> a.toLowerCase(Locale.ROOT)).toList();
        if (slug.equals(q) || compactSlug.equals(compactQ)) {
            return 0;
        }
        if (aliases.stream().anyMatch(a -> a.equals(q) || compact(a).equals(compactQ))) {
            return 1;
        }
        if (slug.startsWith(q) || compactSlug.startsWith(compactQ)) {
            return 2;
        }
        if (aliases.stream().anyMatch(a -> a.startsWith(q) || compact(a).startsWith(compactQ))
                || Arrays.stream(slug.split("-")).anyMatch(word -> word.startsWith(q))) {
            return 3;
        }
        if (compactSlug.contains(compactQ)) {
            return 4;
        }
        if (aliases.stream().anyMatch(a -> compact(a).contains(compactQ))) {
            return 5;
        }
        return -1;
    }

    static Map<String, Entry> parseIndex(byte[] json) throws IOException {
        JsonNode root = JSON.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IOException("dashboard-icons metadata is not a JSON object");
        }
        Map<String, Entry> result = new LinkedHashMap<>();
        root.properties().forEach(e -> {
            String slug = e.getKey();
            if (!slug.matches(SLUG_PATTERN)) {
                return;
            }
            JsonNode node = e.getValue();
            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(a -> {
                if (a.isTextual() && !a.asText().isBlank()) {
                    aliases.add(a.asText());
                }
            });
            result.put(slug, new Entry(node.path("base").asText("png"), List.copyOf(aliases)));
        });
        return result;
    }

    /** "adguard-home" → "Adguard Home"; the index carries no display names. */
    static String displayName(String slug) {
        return Arrays.stream(slug.split("[-_]"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static String previewUrl(String baseUrl, String slug, Entry entry) {
        return "svg".equals(entry.base())
                ? baseUrl + "/svg/" + slug + ".svg"
                : baseUrl + "/png/" + slug + ".png";
    }

    private static String compact(String s) {
        return s.replaceAll("[\\s._-]", "");
    }

    /** Cached for 24h; a failed load keeps the stale copy and is retried after a pause. */
    private record Loaded(Map<String, Entry> index, Instant at) {
    }

    /**
     * The first load makes callers wait; later refreshes are done by one caller while everyone else
     * keeps using the stale copy.
     */
    private Map<String, Entry> index() {
        Loaded current = loaded;
        if (current != null) {
            if (isStale(current) && !isPausing() && refreshing.compareAndSet(false, true)) {
                try {
                    load();
                } finally {
                    refreshing.set(false);
                }
            }
            return loaded.index();
        }
        synchronized (this) {
            if (loaded == null && !isPausing()) {
                load();
            }
        }
        current = loaded;
        return current != null ? current.index() : Map.of();
    }

    private static boolean isStale(Loaded loaded) {
        return !loaded.at().plus(INDEX_TTL).isAfter(Instant.now());
    }

    private boolean isPausing() {
        Instant failed = lastFailure;
        return failed != null && failed.plus(RETRY_AFTER_FAILURE).isAfter(Instant.now());
    }

    private void load() {
        URI uri = URI.create(baseUrl + "/metadata.json");
        try {
            IconHttp.Response response = http.get(uri, false, MAX_INDEX_BYTES, false, INDEX_TIMEOUT)
                    .filter(IconHttp.Response::isSuccess)
                    .orElseThrow(() -> new IOException("download failed"));
            Map<String, Entry> index = parseIndex(response.body());
            loaded = new Loaded(index, Instant.now());
            lastFailure = null;
            log.info("Loaded icon library index with {} icons", index.size());
        } catch (IOException | RuntimeException e) {
            lastFailure = Instant.now();
            log.info("Could not load icon library index from {}: {}", uri, e.getMessage());
        }
    }
}
