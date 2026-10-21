package dev.soynx.heim.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.DataPaths;
import dev.soynx.heim.config.model.AuthSettings;

@SpringBootTest
@AutoConfigureMockMvc
class BackupApiTest {

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
    PasswordEncoder encoder;
    @Autowired
    DataPaths paths;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG_B = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() throws IOException {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        for (Path dir : new Path[] {paths.iconsDir(), paths.uploadsDir()}) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.write(paths.iconsDir().resolve("a.png"), PNG);
        Files.write(paths.uploadsDir().resolve("bg.jpg"), JPEG);
    }

    private byte[] export() throws Exception {
        return mvc.perform(get("/api/backup/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.matchesPattern("attachment; filename=\"heim-backup-\\d{4}-\\d{2}-\\d{2}\\.zip\"")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private ResultActions importZip(byte[] zip) throws Exception {
        return mvc.perform(multipart("/api/backup/import")
                .file(new MockMultipartFile("file", "backup.zip", "application/zip", zip))
                .with(csrf()));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void exportContainsConfigWithoutCredentialsPlusFiles() throws Exception {
        store.update(null, c -> {
            c.getSettings().getAuth().setRequireLoginToView(true);
            return null;
        });
        var session = SecurityTestSupport.login(mvc);
        byte[] zip = mvc.perform(get("/api/backup/export").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> entries = unzip(zip);
        assertThat(entries).containsOnlyKeys("config.json", "icons/a.png", "uploads/bg.jpg");
        assertThat(entries.get("icons/a.png")).isEqualTo(PNG);

        String config = new String(entries.get("config.json"), StandardCharsets.UTF_8);
        assertThat(config).doesNotContain("$2a$").doesNotContain(SecurityTestSupport.USER);
        JsonNode auth = json.readTree(config).path("settings").path("auth");
        assertThat(auth.path("passwordHash").isNull() || auth.path("passwordHash").isMissingNode()).isTrue();
        assertThat(auth.path("username").isNull() || auth.path("username").isMissingNode()).isTrue();
        assertThat(auth.path("requireLoginToView").asBoolean()).isFalse();
        assertThat(auth.path("requireLoginToEdit").asBoolean()).isFalse();
    }

    @Test
    void importRoundTripRestoresConfigAndFilesButKeepsAuth() throws Exception {
        store.update(null, c -> {
            c.getSettings().setTitle("Before export");
            return null;
        });
        byte[] backup = export();

        store.update(null, c -> {
            c.getSettings().setTitle("Changed later");
            c.getSettings().getAuth().setRequireLoginToEdit(true);
            return null;
        });
        Files.delete(paths.iconsDir().resolve("a.png"));
        Files.write(paths.iconsDir().resolve("b.png"), PNG_B);
        String hashBefore = store.read().getSettings().getAuth().getPasswordHash();
        var session = SecurityTestSupport.login(mvc);

        long revision = json.readTree(mvc.perform(multipart("/api/backup/import")
                        .file(new MockMultipartFile("file", "backup.zip", "application/zip", backup))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").isNumber())
                .andReturn().getResponse().getContentAsString()).path("revision").asLong();

        assertThat(revision).isEqualTo(store.revision());
        assertThat(store.read().getSettings().getTitle()).isEqualTo("Before export");
        assertThat(paths.iconsDir().resolve("a.png")).hasBinaryContent(PNG);
        assertThat(paths.iconsDir().resolve("b.png")).doesNotExist();
        assertThat(paths.uploadsDir().resolve("bg.jpg")).hasBinaryContent(JPEG);
        AuthSettings auth = store.read().getSettings().getAuth();
        assertThat(auth.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(auth.getUsername()).isEqualTo(SecurityTestSupport.USER);
        assertThat(auth.isRequireLoginToEdit()).isTrue();
        try (var leftovers = Files.list(paths.dataDir())) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".import"));
        }
    }

    @Test
    void zipSlipEntriesAreRejected() throws Exception {
        byte[] config = store.toJson(store.read());
        for (String evil : new String[] {"../evil.txt", "icons/../../evil.txt", "icons/../evil.txt",
                "/tmp/evil.txt", "icons/sub/evil.txt", "icons/.hidden", "other.txt", "icons\\..\\evil.txt"}) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("config.json", config);
            entries.put("icons/new.png", PNG);
            entries.put(evil, PNG);
            long revision = store.revision();
            importZip(zip(entries)).andExpect(status().isBadRequest());
            assertThat(store.revision()).as(evil).isEqualTo(revision);
            assertThat(paths.iconsDir().resolve("new.png")).as(evil).doesNotExist();
            assertThat(paths.iconsDir().resolve("a.png")).as(evil).exists();
        }
        assertThat(dataDir.getParent().resolve("evil.txt")).doesNotExist();
        assertThat(dataDir.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void invalidConfigIsRejectedWithoutChanges() throws Exception {
        long revision = store.revision();
        for (byte[] badConfig : new byte[][] {"not json".getBytes(), "[1,2]".getBytes()}) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("config.json", badConfig);
            entries.put("icons/new.png", PNG);
            importZip(zip(entries)).andExpect(status().isBadRequest());
        }
        importZip(zip(Map.of("icons/new.png", PNG))).andExpect(status().isBadRequest());
        importZip("definitely not a zip".getBytes()).andExpect(status().isBadRequest());
        assertThat(store.revision()).isEqualTo(revision);
        assertThat(paths.iconsDir().resolve("new.png")).doesNotExist();
        assertThat(paths.iconsDir().resolve("a.png")).exists();
    }

    @Test
    void filesWithNonImageOrMismatchedContentRejectTheWholeImport() throws Exception {
        byte[] config = store.toJson(store.read());
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> bad = new LinkedHashMap<>();
        bad.put("uploads/x.html", html);
        bad.put("uploads/x.png", html);
        bad.put("uploads/x.svg", svg);
        bad.put("uploads/x.jpg", PNG);
        bad.put("icons/x.html", html);
        bad.put("icons/x.png", html);
        bad.put("icons/x.png ", PNG);
        bad.put("icons/x.svg", PNG);
        bad.put("icons/x.png.svg", "<svg><foo/></svg><svg/>".getBytes(StandardCharsets.UTF_8));
        for (var entry : bad.entrySet()) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("config.json", config);
            entries.put("uploads/new.png", PNG);
            entries.put(entry.getKey(), entry.getValue());
            long revision = store.revision();
            importZip(zip(entries)).andExpect(status().isBadRequest());
            assertThat(store.revision()).as(entry.getKey()).isEqualTo(revision);
            assertThat(paths.uploadsDir().resolve("new.png")).as(entry.getKey()).doesNotExist();
            assertThat(paths.uploadsDir().resolve("bg.jpg")).as(entry.getKey()).exists();
        }
    }

    @Test
    void importedSvgIconsAreSanitized() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("config.json", store.toJson(store.read()));
        entries.put("icons/x.svg", ("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">"
                + "<script>alert(2)</script><circle r=\"4\"/></svg>").getBytes(StandardCharsets.UTF_8));
        importZip(zip(entries)).andExpect(status().isOk());
        assertThat(Files.readString(paths.iconsDir().resolve("x.svg")))
                .contains("<circle").doesNotContain("script").doesNotContain("onload");
    }

    @Test
    void uploadsAreServedOnlyAsSandboxedImages() throws Exception {
        Files.writeString(paths.uploadsDir().resolve("x.html"), "<script>alert(1)</script>");
        Files.writeString(paths.uploadsDir().resolve("x.png"), "<script>alert(1)</script>");
        mvc.perform(get("/uploads/x.html")).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/x.png")).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/bg.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
