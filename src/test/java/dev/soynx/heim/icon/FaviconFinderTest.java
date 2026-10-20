package dev.soynx.heim.icon;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class FaviconFinderTest {

    private static final URI PAGE = URI.create("https://app.example.com/sub/dir/index.html");

    private static List<URI> ranked(String html) {
        Document doc = Jsoup.parse(html, PAGE.toString());
        return FaviconFinder.rank(FaviconFinder.fromHtml(doc), PAGE);
    }

    @Test
    void resolvesRelativeUrlsAgainstPageAndBaseTag() {
        assertThat(ranked("<link rel=icon href='fav.png'>").getFirst())
                .isEqualTo(URI.create("https://app.example.com/sub/dir/fav.png"));
        assertThat(ranked("<link rel=icon href='/root.png'>").getFirst())
                .isEqualTo(URI.create("https://app.example.com/root.png"));
        assertThat(ranked("<link rel=icon href='//cdn.example.net/i.png'>").getFirst())
                .isEqualTo(URI.create("https://cdn.example.net/i.png"));
        assertThat(ranked("<base href='https://other.example.org/x/'><link rel=icon href='i.png'>").getFirst())
                .isEqualTo(URI.create("https://other.example.org/x/i.png"));
    }

    @Test
    void prefersLargestDeclaredSize() {
        List<URI> uris = ranked("""
                <link rel="icon" sizes="16x16" href="/16.png">
                <link rel="icon" sizes="32x32 192x192" href="/multi.png">
                <link rel="icon" sizes="96x96" href="/96.png">
                <link rel="shortcut icon" href="/plain.ico">
                """);
        assertThat(uris).extracting(URI::getPath)
                .containsExactly("/multi.png", "/96.png", "/plain.ico", "/16.png", "/favicon.ico");
    }

    @Test
    void appleTouchIconBeatsSmallIconsAndSvgBeatsEverything() {
        List<URI> uris = ranked("""
                <link rel="icon" sizes="32x32" href="/32.png">
                <link rel="apple-touch-icon" href="/apple.png">
                <link rel="icon" type="image/svg+xml" href="/logo">
                """);
        assertThat(uris).extracting(URI::getPath).containsExactly("/logo", "/apple.png", "/32.png", "/favicon.ico");
        assertThat(ranked("<link rel=icon href=/a.png sizes=512x512><link rel=icon href=/b.svg>").getFirst().getPath())
                .isEqualTo("/b.svg");
    }

    @Test
    void ignoresMaskIconsUnrelatedLinksAndNonHttpUrls() {
        List<URI> uris = ranked("""
                <link rel="mask-icon" href="/mask.svg" color="#000">
                <link rel="stylesheet" href="/style.css">
                <link rel="icon" href="data:image/png;base64,AAAA">
                <link rel="icon icon" href="javascript:alert(1)">
                """);
        assertThat(uris).containsExactly(URI.create("https://app.example.com/favicon.ico"));
    }

    @Test
    void fallsBackToOriginFaviconIco() {
        assertThat(ranked("<html><head><title>x</title></head></html>"))
                .containsExactly(URI.create("https://app.example.com/favicon.ico"));
        assertThat(FaviconFinder.fallback(URI.create("http://10.0.0.5:8080/admin/?x=1")))
                .isEqualTo(URI.create("http://10.0.0.5:8080/favicon.ico"));
    }

    @Test
    void readsManifestIcons() {
        Document doc = Jsoup.parse("<link rel=manifest href='/site.webmanifest'>", PAGE.toString());
        URI manifest = FaviconFinder.manifestUrl(doc).orElseThrow();
        assertThat(manifest).isEqualTo(URI.create("https://app.example.com/site.webmanifest"));

        byte[] json = """
                {"icons": [
                  {"src": "icons/192.png", "sizes": "192x192", "type": "image/png"},
                  {"src": "icons/mono.png", "sizes": "512x512", "purpose": "monochrome"},
                  {"src": "/icons/512.png", "sizes": "512x512"}
                ]}""".getBytes(StandardCharsets.UTF_8);
        List<URI> uris = FaviconFinder.rank(FaviconFinder.fromManifest(json, manifest), PAGE);
        assertThat(uris).extracting(URI::getPath).containsExactly("/icons/512.png", "/icons/192.png", "/favicon.ico");
        assertThat(FaviconFinder.fromManifest("not json".getBytes(StandardCharsets.UTF_8), manifest)).isEmpty();
    }
}
