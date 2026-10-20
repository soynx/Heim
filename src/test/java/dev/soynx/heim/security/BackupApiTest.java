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
        Files.writeString(paths.iconsDir().resolve("a.png"), "icon-a");
        Files.writeString(paths.uploadsDir().resolve("bg.jpg"), "background");
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
        assertThat(new String(entries.get("icons/a.png"), StandardCharsets.UTF_8)).isEqualTo("icon-a");

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
        Files.writeString(paths.iconsDir().resolve("b.png"), "icon-b");
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
        assertThat(paths.iconsDir().resolve("a.png")).hasContent("icon-a");
        assertThat(paths.iconsDir().resolve("b.png")).doesNotExist();
        assertThat(paths.uploadsDir().resolve("bg.jpg")).hasContent("background");
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
            entries.put("icons/new.png", "new".getBytes());
            entries.put(evil, "pwned".getBytes());
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
            entries.put("icons/new.png", "new".getBytes());
            importZip(zip(entries)).andExpect(status().isBadRequest());
        }
        importZip(zip(Map.of("icons/new.png", "new".getBytes()))).andExpect(status().isBadRequest());
        importZip("definitely not a zip".getBytes()).andExpect(status().isBadRequest());
        assertThat(store.revision()).isEqualTo(revision);
        assertThat(paths.iconsDir().resolve("new.png")).doesNotExist();
        assertThat(paths.iconsDir().resolve("a.png")).exists();
    }
}
