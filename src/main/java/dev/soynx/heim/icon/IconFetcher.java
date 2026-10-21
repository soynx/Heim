package dev.soynx.heim.icon;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.Service;

/** Downloads a service's icon from its configured source. Never throws; failures yield empty. */
@Component
public class IconFetcher {

    private static final Logger log = LoggerFactory.getLogger(IconFetcher.class);

    public static final long MAX_ICON_BYTES = 2L * 1024 * 1024;
    private static final long MAX_PAGE_BYTES = 2L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Upper bound on icon URLs tried per favicon lookup. */
    private static final int MAX_FAVICON_ATTEMPTS = 5;

    private final IconHttp http;
    private final IconLibrary library;

    public IconFetcher(IconHttp http, IconLibrary library) {
        this.http = http;
        this.library = library;
    }

    /** Validated icon content; SVGs are already sanitized. */
    public record IconFile(byte[] content, IconFormat format) {
    }

    public Optional<IconFile> fetch(Service service) {
        IconRef icon = service.getIcon();
        if (icon == null || icon.getSource() == null) {
            return Optional.empty();
        }
        try {
            return switch (icon.getSource()) {
                case FAVICON -> faviconTarget(service).flatMap(link -> favicon(link.url(), link.ignoreTls()));
                case LIBRARY -> firstValid(library.downloadUrls(icon.getValue()), false);
                case URL -> parse(icon.getValue()).flatMap(uri -> download(uri, false));
                case UPLOAD -> Optional.empty();
            };
        } catch (RuntimeException e) {
            log.info("Icon fetch for service {} failed: {}", service.getId(), e.toString());
            return Optional.empty();
        }
    }

    /** Page whose favicon to use and whether its link accepts any TLS certificate. */
    public record FaviconTarget(URI url, boolean ignoreTls) {
    }

    /** The icon's favicon link, falling back to the primary link and then to the first one. */
    public static Optional<FaviconTarget> faviconTarget(Service service) {
        return service.faviconLink().flatMap(l -> parse(l.getUrl())
                .map(uri -> new FaviconTarget(uri, l.getProbe() != null && l.getProbe().isIgnoreTls())));
    }

    /** Reads the page, tries its declared icons best first, then {@code /favicon.ico}. */
    Optional<IconFile> favicon(URI page, boolean ignoreTls) {
        Optional<IconHttp.Response> fetched = http.get(page, ignoreTls, MAX_PAGE_BYTES, true, TIMEOUT);
        if (fetched.isEmpty()) {
            return Optional.empty(); // host unreachable, so /favicon.ico would be too
        }
        IconHttp.Response response = fetched.get();
        URI base = response.uri();
        List<FaviconFinder.Candidate> candidates = new ArrayList<>();
        // Error pages (401 login walls etc.) often still carry the app's icons, so the status is ignored.
        if (looksLikeHtml(response)) {
            Document doc = Jsoup.parse(new String(response.body(), charset(response)), base.toString());
            candidates.addAll(FaviconFinder.fromHtml(doc));
            FaviconFinder.manifestUrl(doc)
                    .flatMap(manifest -> http.get(manifest, ignoreTls, MAX_ICON_BYTES, false, TIMEOUT))
                    .filter(IconHttp.Response::isSuccess)
                    .ifPresent(m -> candidates.addAll(FaviconFinder.fromManifest(m.body(), m.uri())));
        }
        List<URI> ranked = FaviconFinder.rank(candidates, base);
        return firstValid(ranked.subList(0, Math.min(ranked.size(), MAX_FAVICON_ATTEMPTS)), ignoreTls);
    }

    private Optional<IconFile> firstValid(List<URI> uris, boolean ignoreTls) {
        for (URI uri : uris) {
            Optional<IconFile> file = download(uri, ignoreTls);
            if (file.isPresent()) {
                return file;
            }
        }
        return Optional.empty();
    }

    /** Downloads one icon; rejects non-2xx, oversized and unrecognised content. */
    Optional<IconFile> download(URI uri, boolean ignoreTls) {
        return http.get(uri, ignoreTls, MAX_ICON_BYTES, false, TIMEOUT)
                .filter(IconHttp.Response::isSuccess)
                .flatMap(r -> {
                    Optional<IconFile> file = validate(r.body());
                    if (file.isEmpty()) {
                        log.debug("Icon {} is not a supported image (Content-Type {})", uri, r.contentType());
                    }
                    return file;
                });
    }

    /** Detects the format from content and sanitizes SVGs. */
    public static Optional<IconFile> validate(byte[] content) {
        return IconFormat.detect(content).flatMap(format -> {
            if (format != IconFormat.SVG) {
                return Optional.of(new IconFile(content, format));
            }
            return Optional.ofNullable(SvgSanitizer.sanitize(content)).map(svg -> new IconFile(svg, format));
        });
    }

    private static boolean looksLikeHtml(IconHttp.Response response) {
        if (response.isHtml()) {
            return true;
        }
        if (response.contentType() != null) {
            return false;
        }
        String head = new String(response.body(), 0, Math.min(response.body().length, 1024),
                StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return head.contains("<html") || head.contains("<head") || head.contains("<!doctype html");
    }

    private static Charset charset(IconHttp.Response response) {
        String type = response.contentType();
        if (type != null) {
            for (String part : type.split(";")) {
                String p = part.trim();
                if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (IllegalArgumentException ignored) {
                        break;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Optional<URI> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url.trim());
            return IconHttp.isHttp(uri) && uri.getHost() != null ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
