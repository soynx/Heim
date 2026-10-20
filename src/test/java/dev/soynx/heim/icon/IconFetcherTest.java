package dev.soynx.heim.icon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.icon.IconFetcher.IconFile;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

class IconFetcherTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] ICO = {0, 0, 1, 0, 1, 0, 16, 16, 0, 0};

    private MockWebServer server;
    private IconFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        IconLibrary library = new IconLibrary(new IconHttp(), server.url("/lib").toString());
        fetcher = new IconFetcher(new IconHttp(), library);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void route(Dispatcher dispatcher) {
        server.setDispatcher(dispatcher);
    }

    private static MockResponse image(byte[] content, String type) {
        return new MockResponse().setHeader("Content-Type", type).setBody(new Buffer().write(content));
    }

    private static MockResponse html(String body) {
        return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body);
    }

    private static Service service(IconSource source, String value, String... linkUrls) {
        Service service = new Service();
        service.setId("svc_test");
        service.getIcon().setSource(source);
        service.getIcon().setValue(value);
        for (int i = 0; i < linkUrls.length; i++) {
            Link link = new Link();
            link.setId("lnk_" + i);
            link.setUrl(linkUrls[i]);
            service.getLinks().add(link);
        }
        if (linkUrls.length > 0) {
            service.setPrimaryLinkId("lnk_0");
        }
        return service;
    }

    @Test
    void faviconFollowsRedirectAndResolvesAgainstFinalUrl() {
        route(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getPath()) {
                    case "/" -> new MockResponse().setResponseCode(302).setHeader("Location", "/app/login");
                    case "/app/login" -> html("<html><head>"
                            + "<link rel=icon sizes=16x16 href=small.png>"
                            + "<link rel=apple-touch-icon href=img/touch.png></head></html>");
                    case "/app/img/touch.png" -> image(PNG, "image/png");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        Optional<IconFile> icon = fetcher.fetch(service(IconSource.FAVICON, null, server.url("/").toString()));
        assertThat(icon).get().extracting(IconFile::format).isEqualTo(IconFormat.PNG);
    }

    @Test
    void faviconUsesChosenLinkAndSkipsBrokenCandidates() throws InterruptedException {
        route(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getPath()) {
                    case "/second" -> html("<link rel=icon type=image/svg+xml href=/logo.svg>"
                            + "<link rel=icon sizes=64x64 href=/missing.png>");
                    // An SPA answering every path with its index page must not become an icon.
                    case "/logo.svg" -> html("<!doctype html><html><body>app</body></html>");
                    case "/favicon.ico" -> image(ICO, "image/x-icon");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        Service service = service(IconSource.FAVICON, null, server.url("/first").toString(),
                server.url("/second").toString());
        service.getIcon().setFaviconLinkId("lnk_1");

        assertThat(fetcher.fetch(service)).get().extracting(IconFile::format).isEqualTo(IconFormat.ICO);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/second");
    }

    @Test
    void faviconFromManifestWhenPageHasNoIconLinks() {
        route(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getPath()) {
                    case "/" -> html("<link rel=manifest href=/static/app.webmanifest>");
                    case "/static/app.webmanifest" -> new MockResponse()
                            .setBody("{\"icons\":[{\"src\":\"icon-512.png\",\"sizes\":\"512x512\"}]}");
                    case "/static/icon-512.png" -> image(PNG, "image/png");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        assertThat(fetcher.fetch(service(IconSource.FAVICON, null, server.url("/").toString()))).isPresent();
    }

    @Test
    void faviconUnreachableHostYieldsEmpty() throws IOException {
        String url = server.url("/").toString();
        server.shutdown();
        assertThat(fetcher.fetch(service(IconSource.FAVICON, null, url))).isEmpty();
        assertThat(fetcher.fetch(service(IconSource.FAVICON, null))).isEmpty();
    }

    @Test
    void urlDownloadSanitizesSvg() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/svg+xml")
                .setBody("<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'><script>x</script><rect/></svg>"));
        IconFile icon = fetcher.fetch(service(IconSource.URL, server.url("/i.svg").toString())).orElseThrow();
        assertThat(icon.format()).isEqualTo(IconFormat.SVG);
        assertThat(new String(icon.content(), StandardCharsets.UTF_8)).contains("<rect").doesNotContain("script", "onload");
    }

    @Test
    void urlDownloadRejectsBadContentErrorsAndOversizedBodies() {
        server.enqueue(image("<html>not an image</html>".getBytes(StandardCharsets.UTF_8), "image/png"));
        assertThat(fetcher.fetch(service(IconSource.URL, server.url("/a.png").toString()))).isEmpty();

        server.enqueue(image(PNG, "image/png").setResponseCode(500));
        assertThat(fetcher.fetch(service(IconSource.URL, server.url("/b.png").toString()))).isEmpty();

        byte[] huge = new byte[(int) IconFetcher.MAX_ICON_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        server.enqueue(image(huge, "image/png"));
        assertThat(fetcher.fetch(service(IconSource.URL, server.url("/c.png").toString()))).isEmpty();

        // Same, without Content-Length: the cap applies while reading.
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                .setChunkedBody(new Buffer().write(huge), 64 * 1024));
        assertThat(fetcher.fetch(service(IconSource.URL, server.url("/d.png").toString()))).isEmpty();

        assertThat(fetcher.fetch(service(IconSource.URL, "file:///etc/passwd"))).isEmpty();
    }

    @Test
    void stalledBodyHitsDeadline() {
        server.enqueue(image(PNG, "image/png").throttleBody(1, 1, TimeUnit.SECONDS));
        IconHttp http = new IconHttp();
        long start = System.nanoTime();
        Optional<IconHttp.Response> response =
                http.get(server.url("/slow.png").uri(), false, 1024, false, Duration.ofMillis(500));
        assertThat(response).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void libraryPrefersSvgAndFallsBackToPng() {
        route(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getPath()) {
                    case "/lib/metadata.json" -> new MockResponse()
                            .setBody("{\"proxmox\":{\"base\":\"svg\",\"aliases\":[]}}");
                    case "/lib/png/proxmox.png" -> image(PNG, "image/png");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        assertThat(fetcher.fetch(service(IconSource.LIBRARY, "proxmox"))).get()
                .extracting(IconFile::format).isEqualTo(IconFormat.PNG);
        List<String> paths = List.of(take(), take(), take());
        assertThat(paths).containsExactly("/lib/metadata.json", "/lib/svg/proxmox.svg", "/lib/png/proxmox.png");
    }

    @Test
    void uploadSourceIsNeverFetched() {
        assertThat(fetcher.fetch(service(IconSource.UPLOAD, "x.png", server.url("/").toString()))).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    private String take() {
        try {
            return server.takeRequest(1, TimeUnit.SECONDS).getPath();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void faviconTargetFallsBackToPrimaryLink() {
        Service service = service(IconSource.FAVICON, null, "https://a.example", "https://b.example");
        service.setPrimaryLinkId("lnk_1");
        service.getLinks().get(1).getProbe().setIgnoreTls(true);
        assertThat(IconFetcher.faviconTarget(service)).get()
                .isEqualTo(new IconFetcher.FaviconTarget(URI.create("https://b.example"), true));
        service.getIcon().setFaviconLinkId("lnk_0");
        assertThat(IconFetcher.faviconTarget(service).orElseThrow().url()).isEqualTo(URI.create("https://a.example"));
    }
}
