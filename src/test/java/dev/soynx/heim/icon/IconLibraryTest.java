package dev.soynx.heim.icon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.soynx.heim.icon.IconLibrary.Entry;
import dev.soynx.heim.icon.IconLibrary.LibraryIcon;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class IconLibraryTest {

    /** Shape of the real dashboard-icons {@code metadata.json}. */
    private static final String INDEX = """
            {
              "proxmox": {"base": "svg", "aliases": ["Virtualization Platform"], "categories": [],
                          "colors": {"dark": "proxmox", "light": "proxmox-light"}},
              "proxmox-backup-server": {"base": "svg", "aliases": ["pbs"]},
              "proxmox-helper-scripts": {"base": "png", "aliases": []},
              "pve-exporter": {"base": "png", "aliases": ["proxmox exporter"]},
              "adguard-home": {"base": "svg", "aliases": ["adguard"]},
              "home-assistant": {"base": "svg", "aliases": ["hass", "HA"]},
              "jellyfin": {"base": "svg", "aliases": []},
              "Bad Slug": {"base": "svg"},
              "ntopng": {"base": "png", "aliases": ["network-analyzer"]}
            }""";

    private static final String BASE = "https://cdn.example/icons";

    private static Map<String, Entry> index() throws IOException {
        return IconLibrary.parseIndex(INDEX.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> slugs(String query) throws IOException {
        return IconLibrary.rank(index(), query, BASE).stream().map(LibraryIcon::slug).toList();
    }

    @Test
    void parsesIndexAndSkipsInvalidSlugs() throws IOException {
        Map<String, Entry> index = index();
        assertThat(index).doesNotContainKey("Bad Slug").containsKey("proxmox");
        assertThat(index.get("proxmox").aliases()).containsExactly("Virtualization Platform");
        assertThat(index.get("ntopng").base()).isEqualTo("png");
    }

    @Test
    void ranksExactThenPrefixThenAliasThenSubstring() throws IOException {
        assertThat(slugs("Proxmox")).containsExactly(
                "proxmox", "proxmox-backup-server", "proxmox-helper-scripts", "pve-exporter");
        assertThat(slugs("pbs")).containsExactly("proxmox-backup-server");
        assertThat(slugs("hass")).containsExactly("home-assistant");
        assertThat(slugs("home")).containsExactly("home-assistant", "adguard-home");
        assertThat(slugs("adguard home")).first().isEqualTo("adguard-home");
        assertThat(slugs("lly")).containsExactly("jellyfin");
        assertThat(slugs("  ")).isEmpty();
        assertThat(slugs("nothing-like-this")).isEmpty();
    }

    @Test
    void buildsNamesAndPreviewUrls() throws IOException {
        List<LibraryIcon> results = IconLibrary.rank(index(), "proxmox", BASE);
        assertThat(results.getFirst()).isEqualTo(new LibraryIcon("proxmox", "Proxmox", BASE + "/svg/proxmox.svg"));
        assertThat(results.get(2)).isEqualTo(new LibraryIcon("proxmox-helper-scripts", "Proxmox Helper Scripts",
                BASE + "/png/proxmox-helper-scripts.png"));
    }

    @Test
    void limitsResults() throws IOException {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 80; i++) {
            json.append(i == 0 ? "" : ",").append("\"app-").append(i).append("\": {\"base\": \"png\"}");
        }
        json.append('}');
        Map<String, Entry> big = IconLibrary.parseIndex(json.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(IconLibrary.rank(big, "app", BASE)).hasSize(IconLibrary.MAX_RESULTS);
    }

    @Test
    void loadsIndexOnceAndPicksDownloadFormats() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(INDEX).setHeader("Content-Type", "application/json"));
            server.start();
            String base = server.url("/gh/dashboard-icons").toString();
            IconLibrary library = new IconLibrary(new IconHttp(), base);

            assertThat(library.search("jelly")).extracting(LibraryIcon::slug).containsExactly("jellyfin");
            assertThat(library.search("ntop")).extracting(LibraryIcon::slug).containsExactly("ntopng");
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(server.takeRequest().getPath()).isEqualTo("/gh/dashboard-icons/metadata.json");

            assertThat(library.downloadUrls("jellyfin")).extracting(URI::getPath)
                    .containsExactly("/gh/dashboard-icons/svg/jellyfin.svg", "/gh/dashboard-icons/png/jellyfin.png");
            assertThat(library.downloadUrls("ntopng")).extracting(URI::getPath)
                    .containsExactly("/gh/dashboard-icons/png/ntopng.png");
            assertThat(library.downloadUrls("unknown-new-icon")).hasSize(2);
            assertThat(library.downloadUrls("../etc/passwd")).isEmpty();
        }
    }

    @Test
    void unavailableIndexYieldsNoResults() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.start();
            IconLibrary library = new IconLibrary(new IconHttp(), server.url("/").toString());
            assertThat(library.search("proxmox")).isEmpty();
            // Failure is remembered for a while instead of re-downloading on every keystroke.
            assertThat(library.search("proxmo")).isEmpty();
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }
}
