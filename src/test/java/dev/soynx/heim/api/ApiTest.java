package dev.soynx.heim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.service.TabManager;

/**
 * MockMvc tests of the foundation REST API. All tests share one context and data directory, so
 * each test reads the current revision and ETags instead of assuming them. Mutating requests carry a CSRF
 * token so the tests keep working once real security replaces the temporary permit-all config.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final String HASH = "$2a$10$abcdefghijklmnopqrstuuSECRETSECRETSECRETSECRETSECRET1";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("heim.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ConfigStore store;

    @Autowired
    TabManager tabs;

    private final ObjectMapper json = new ObjectMapper();

    private static String etag(long revision) {
        return "\"" + revision + "\"";
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String createService(String body) throws Exception {
        MvcResult result = mvc.perform(jsonBody(post("/api/services"), body))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("id").asText();
    }

    // --- ETags ----------------------------------------------------------------------------

    private String etagOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    @Test
    void dashboardCarriesRevisionETag() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etag(store.revision())))
                .andExpect(jsonPath("$.revision").value(store.revision()));
    }

    @Test
    void dashboardHidesProbeConfig() throws Exception {
        String id = createService("{\"name\":\"nas\",\"links\":[{\"label\":\"LAN\",\"url\":\"https://nas.lan\","
                + "\"probe\":{\"enabled\":true,\"url\":\"https://admin:s3cret@nas.lan/health\",\"ignoreTls\":true}}]}");
        String dashboard = mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains(id).doesNotContain("s3cret").doesNotContain("/health")
                .doesNotContain("ignoreTls").doesNotContain("\"probe\"");
        JsonNode link = json.readTree(dashboard).findParents("primaryLinkId").stream()
                .filter(s -> s.path("id").asText().equals(id)).findFirst().orElseThrow()
                .path("links").get(0);
        assertThat(link.path("label").asText()).isEqualTo("LAN");
        assertThat(link.path("url").asText()).isEqualTo("https://nas.lan");
        assertThat(link.path("probeEnabled").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/services", "/api/tabs", "/api/settings"})
    void getsCarryResourceETagIndependentOfRevision(String path) throws Exception {
        String tag = etagOf(get(path));
        assertThat(tag).matches("\"[0-9a-f]{16}\"");

        store.update(null, c -> null); // bumps the revision only
        assertThat(etagOf(get(path))).isEqualTo(tag);
    }

    @Test
    void singleServiceGetCarriesETagAndUnknownIs404Json() throws Exception {
        String id = createService("{\"name\":\"Etagged\",\"tabIds\":[]}");

        mvc.perform(get("/api/services/{id}", id))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", matchesPattern("\"[0-9a-f]{16}\"")))
                .andExpect(jsonPath("$.name").value("Etagged"));

        mvc.perform(get("/api/services/svc_missing0"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(containsString("svc_missing0")));
    }

    @Test
    void writesReturnNewETag() throws Exception {
        String tag = etagOf(get("/api/settings"));
        String theme = "dark".equals(store.read().getSettings().getTheme()) ? "light" : "dark";
        MvcResult result = mvc.perform(jsonBody(put("/api/settings"), "{\"theme\":\"" + theme + "\"}")
                        .header("If-Match", tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value(theme))
                .andReturn();
        String newTag = result.getResponse().getHeader("ETag");
        assertThat(newTag).isNotEqualTo(tag).isEqualTo(etagOf(get("/api/settings")));
    }

    @Test
    void weakAndWildcardIfMatchAccepted() throws Exception {
        String tag = etagOf(get("/api/settings"));
        mvc.perform(jsonBody(put("/api/settings"), "{}").header("If-Match", "W/" + tag))
                .andExpect(status().isOk());
        mvc.perform(jsonBody(put("/api/settings"), "{}").header("If-Match", "*"))
                .andExpect(status().isOk());
    }

    @Test
    void staleIfMatchIs409JsonAndNothingSaved() throws Exception {
        long rev = store.revision();
        String title = store.read().getSettings().getTitle();
        String current = etagOf(get("/api/settings"));

        mvc.perform(jsonBody(put("/api/settings"), "{\"title\":\"Overwritten\"}").header("If-Match", "\"stale\""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("ETag", current))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").isString());

        mvc.perform(jsonBody(post("/api/services"), "{\"name\":\"Late\"}").header("If-Match", "\"stale\""))
                .andExpect(status().isConflict())
                .andExpect(header().string("ETag", etagOf(get("/api/services"))))
                .andExpect(jsonPath("$.error").value("Conflict"));

        assertThat(store.revision()).isEqualTo(rev);
        assertThat(store.read().getSettings().getTitle()).isEqualTo(title);
    }

    @Test
    void backgroundIconCacheDoesNotCauseConflictButRealEditDoes() throws Exception {
        String id = createService("{\"name\":\"Editing\",\"tabIds\":[],\"links\":[{\"url\":\"https://a.lan\"}]}");
        String opened = etagOf(get("/api/services/{id}", id));

        // What IconService does after fetching a favicon in the background.
        store.update(null, c -> {
            c.findService(id).orElseThrow().getIcon().setCachedFile("svc_cached.png");
            return null;
        });

        MvcResult saved = mvc.perform(jsonBody(put("/api/services/{id}", id),
                        "{\"name\":\"Edited\",\"tabIds\":[],\"links\":[{\"url\":\"https://a.lan\"}]}")
                        .header("If-Match", opened))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Edited"))
                .andReturn();
        String afterSave = saved.getResponse().getHeader("ETag");

        // Someone else renames it meanwhile: our next save with the old tag must fail.
        store.update(null, c -> {
            c.findService(id).orElseThrow().setName("Renamed elsewhere");
            return null;
        });
        mvc.perform(jsonBody(put("/api/services/{id}", id), "{\"name\":\"Mine\",\"tabIds\":[]}")
                        .header("If-Match", afterSave))
                .andExpect(status().isConflict())
                .andExpect(header().string("ETag", etagOf(get("/api/services/{id}", id))));
        mvc.perform(delete("/api/services/{id}", id).with(csrf()).header("If-Match", afterSave))
                .andExpect(status().isConflict());
        assertThat(store.read().findService(id).orElseThrow().getName()).isEqualTo("Renamed elsewhere");

        mvc.perform(delete("/api/services/{id}", id).with(csrf())
                        .header("If-Match", etagOf(get("/api/services/{id}", id))))
                .andExpect(status().isNoContent());
    }

    @Test
    void tabETagsArePerTabAndOrderTagGuardsReorder() throws Exception {
        String first = store.read().getTabs().getFirst().getId();
        String second = body(mvc.perform(jsonBody(post("/api/tabs"), "{\"name\":\"Second\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
        String firstTag = etagOf(get("/api/tabs/{id}", first));
        String secondTag = etagOf(get("/api/tabs/{id}", second));
        String orderTag = etagOf(get("/api/tabs"));

        // Editing one tab leaves the other's tag and the order tag valid.
        mvc.perform(jsonBody(put("/api/tabs/{id}", second), "{\"name\":\"Second 2\"}").header("If-Match", secondTag))
                .andExpect(status().isOk());
        mvc.perform(jsonBody(put("/api/tabs/{id}/groups", first), "[]").header("If-Match", firstTag))
                .andExpect(status().isOk());
        mvc.perform(jsonBody(put("/api/tabs/{id}", second), "{\"name\":\"Stale\"}").header("If-Match", secondTag))
                .andExpect(status().isConflict());

        List<String> original = store.read().getTabs().stream().map(t -> t.getId()).toList();
        String ids = original.reversed().stream()
                .map(t -> "\"" + t + "\"").collect(Collectors.joining(",", "[", "]"));
        MvcResult reordered = mvc.perform(jsonBody(put("/api/tabs/order"), "{\"tabIds\":" + ids + "}")
                        .header("If-Match", orderTag))
                .andExpect(status().isOk()).andReturn();
        assertThat(reordered.getResponse().getHeader("ETag")).isNotEqualTo(orderTag);
        mvc.perform(jsonBody(put("/api/tabs/order"), "{\"tabIds\":" + ids + "}").header("If-Match", orderTag))
                .andExpect(status().isConflict());
        tabs.reorder(original, null); // other tests expect the default tab first

        mvc.perform(delete("/api/tabs/{id}", second).with(csrf())
                        .header("If-Match", etagOf(get("/api/tabs/{id}", second))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tabs/tab_missing0")).andExpect(status().isNotFound());
    }

    @Test
    void authChangesDoNotInvalidateSettingsTag() throws Exception {
        String tag = etagOf(get("/api/settings"));
        String username = store.read().getSettings().getAuth().getUsername();
        store.update(null, c -> {
            c.getSettings().getAuth().setUsername("tag-test");
            return null;
        });
        String afterAuthChange = etagOf(get("/api/settings"));
        store.update(null, c -> {
            c.getSettings().getAuth().setUsername(username);
            return null;
        });
        assertThat(afterAuthChange).isEqualTo(tag);
    }

    @Test
    void malformedIfMatchIs400() throws Exception {
        mvc.perform(jsonBody(put("/api/settings"), "{}").header("If-Match", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    // --- validation -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"\"}",
            "{\"name\":\"   \"}",
            "{}",
            "{\"name\":\"x\",\"links\":[{\"url\":\"not a url\"}]}",
            "{\"name\":\"x\",\"links\":[{\"url\":\"relative/path\"}]}",
            "{\"name\":\"x\",\"links\":[{\"url\":\"\"}]}",
            "{\"name\":\"x\",\"links\":[{\"url\":\"https://a.lan\",\"probe\":{\"expectedStatus\":\"ok\"}}]}",
            "{\"name\":\"x\",\"links\":[{\"url\":\"https://a.lan\",\"probe\":{\"expectedStatus\":\"200-\"}}]}",
            "{\"name\":\"x\",\"links\":[{\"url\":\"https://a.lan\",\"probe\":{\"intervalSeconds\":1}}]}",
            "{\"name\":\"x\",\"tabIds\":[\"tab_nope0000\"]}",
            "{\"name\":\"x\",\"icon\":{\"source\":\"url\",\"value\":\"file:///etc/passwd\"}}",
            "{\"name\":",
    })
    void invalidServiceRequestsAre400Json(String body) throws Exception {
        long rev = store.revision();
        mvc.perform(jsonBody(post("/api/services"), body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(store.revision()).isEqualTo(rev);
    }

    @Test
    void validationMessageNamesTheField() throws Exception {
        mvc.perform(jsonBody(post("/api/services"), "{\"name\":\"\"}"))
                .andExpect(jsonPath("$.message").value(containsString("name")));
        mvc.perform(jsonBody(post("/api/services"),
                        "{\"name\":\"x\",\"links\":[{\"url\":\"https://a.lan\",\"probe\":{\"expectedStatus\":\"ok\"}}]}"))
                .andExpect(jsonPath("$.message").value(containsString("expectedStatus")));
        mvc.perform(jsonBody(post("/api/services"), "{\"name\":\"x\",\"links\":[{\"url\":\"not a url\"}]}"))
                .andExpect(jsonPath("$.message").value(containsString("links[0].url")));
    }

    @Test
    void validExpectedStatusListsAccepted() throws Exception {
        String id = createService("{\"name\":\"Probed\",\"tabIds\":[],\"links\":[{\"id\":\"tmp\",\"url\":\"https://a.lan\","
                + "\"probe\":{\"enabled\":true,\"expectedStatus\":\"200, 204,300-399\",\"method\":\"HEAD\"}}],"
                + "\"primaryLinkId\":\"tmp\"}");
        mvc.perform(get("/api/services/{id}", id))
                .andExpect(jsonPath("$.links[0].probe.expectedStatus").value("200, 204,300-399"))
                .andExpect(jsonPath("$.links[0].probe.method").value("HEAD"))
                .andExpect(jsonPath("$.primaryLinkId").value(startsWith("lnk_")))
                .andExpect(jsonPath("$.links[0].id").value(startsWith("lnk_")));
    }

    @Test
    void invalidTabRequestsAre400() throws Exception {
        mvc.perform(jsonBody(post("/api/tabs"), "{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
        mvc.perform(jsonBody(put("/api/tabs/order"), "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(put("/api/tabs/order"), "{\"tabIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("every tab")));
        String tabId = store.read().getTabs().getFirst().getId();
        mvc.perform(jsonBody(put("/api/tabs/{id}/groups", tabId), "[{\"name\":\"" + "x".repeat(101) + "\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void tabLifecycleOverHttp() throws Exception {
        MvcResult created = mvc.perform(jsonBody(post("/api/tabs"), "{\"name\":\"Media\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/tabs/tab_")))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andReturn();
        String id = body(created).path("id").asText();

        mvc.perform(get("/api/tabs"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].default").doesNotExist());

        mvc.perform(delete("/api/tabs/{id}", id).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingLastTabIs400() throws Exception {
        // Remove any extra tabs other tests created, then try to delete the last one.
        var tabs = store.read().getTabs();
        for (int i = 1; i < tabs.size(); i++) {
            mvc.perform(delete("/api/tabs/{id}", tabs.get(i).getId()).with(csrf())).andExpect(status().isNoContent());
        }
        mvc.perform(delete("/api/tabs/{id}", tabs.getFirst().getId()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("last tab")));
    }

    // --- secrets --------------------------------------------------------------------------

    @Test
    void passwordHashNeverReturned() throws Exception {
        store.update(null, c -> {
            c.getSettings().getAuth().setUsername("admin");
            c.getSettings().getAuth().setPasswordHash(HASH);
            return null;
        });
        createService("{\"name\":\"WithSecretAround\"}");

        for (String path : new String[] {"/api/dashboard", "/api/settings", "/api/services", "/api/tabs"}) {
            String response = mvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).as(path).doesNotContain("passwordHash").doesNotContain(HASH)
                    .doesNotContain("SECRETSECRET");
        }
        mvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.auth.passwordSet").value(true))
                .andExpect(jsonPath("$.auth.username").value("admin"));
        String afterPut = mvc.perform(jsonBody(put("/api/settings"), "{\"title\":\"Secretless\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(afterPut).doesNotContain("passwordHash").doesNotContain(HASH);

        // Settings PUT cannot touch the stored credentials.
        mvc.perform(jsonBody(put("/api/settings"), "{\"auth\":{\"passwordHash\":\"x\",\"username\":\"evil\"}}"))
                .andExpect(status().isOk());
        assertThat(store.read().getSettings().getAuth().getPasswordHash()).isEqualTo(HASH);
        assertThat(store.read().getSettings().getAuth().getUsername()).isEqualTo("admin");
    }

    // --- assets ---------------------------------------------------------------------------

    @Test
    void customCssServedAsTextCss() throws Exception {
        mvc.perform(jsonBody(put("/api/settings"), "{\"customCss\":\".card { color: red; }\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/custom.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/css;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(content().string(".card { color: red; }"));
        mvc.perform(get("/api/dashboard")).andExpect(jsonPath("$.settings.customCss").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/uploads/..%2Fconfig.json",
            "/uploads/%2e%2e%2fconfig.json",
            "/uploads/..%5Cconfig.json",
            "/uploads/..config.json",
            "/uploads/.bak",
            "/uploads/config.json",
            "/uploads/%2E%2E",
    })
    void uploadsPathTraversalRejected(String path) throws Exception {
        MvcResult result = mvc.perform(get(URI.create(path))).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isBetween(400, 499);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("schemaVersion");
    }

    @Test
    void backgroundUploadAcceptsPngAndServesIt() throws Exception {
        MockMultipartFile png = new MockMultipartFile("file", "wall.png", "image/png", PNG);
        MvcResult result = mvc.perform(multipart("/api/settings/background").file(png).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", matchesPattern("\"[0-9a-f]{16}\"")))
                .andExpect(jsonPath("$.background.source").value("upload"))
                .andExpect(jsonPath("$.background.value").value("wall.png"))
                .andReturn();
        String file = body(result).path("background").path("cachedFile").asText();
        assertThat(file).matches("background_[0-9a-z]{8}\\.png");

        mvc.perform(get("/uploads/{file}", file))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/dashboard"))
                .andExpect(jsonPath("$.settings.background.source").value("upload"))
                .andExpect(jsonPath("$.settings.background.url").value("/uploads/" + file));
    }

    @Test
    void backgroundUploadRejectsTextEvenWithImageContentType() throws Exception {
        long rev = store.revision();
        MockMultipartFile text = new MockMultipartFile("file", "evil.png", "image/png",
                "<script>alert(1)</script>".getBytes());
        mvc.perform(multipart("/api/settings/background").file(text).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
        assertThat(store.revision()).isEqualTo(rev);
    }

    @Test
    void backgroundUploadWithoutFileIs400() throws Exception {
        mvc.perform(multipart("/api/settings/background").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
