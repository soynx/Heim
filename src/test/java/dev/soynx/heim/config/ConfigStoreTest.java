package dev.soynx.heim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.error.RevisionConflictException;

class ConfigStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private JsonNode configOnDisk() throws IOException {
        return JSON.readTree(Files.readAllBytes(dir.resolve("config.json")));
    }

    private List<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    // --- default config -------------------------------------------------------------------

    @Test
    void createsDefaultConfigOnEmptyDirectory() throws IOException {
        ConfigStore store = TestStores.open(dir);

        assertThat(dir.resolve("config.json")).exists();
        assertThat(dir.resolve("icons")).isDirectory();
        assertThat(dir.resolve("uploads")).isDirectory();

        HeimConfig config = store.read();
        assertThat(config.getSchemaVersion()).isEqualTo(1);
        assertThat(config.getServices()).isEmpty();
        assertThat(config.getTabs()).singleElement().satisfies(tab -> {
            assertThat(tab.getName()).isEqualTo("Dashboard");
            assertThat(tab.isDefault()).isTrue();
            assertThat(tab.isShowAll()).isTrue();
            assertThat(tab.getId()).startsWith("tab_");
            assertThat(tab.getGroups()).singleElement().satisfies(g -> {
                assertThat(g.getId()).startsWith("grp_");
                assertThat(g.getServiceIds()).isEmpty();
            });
        });
        assertThat(configOnDisk().path("revision").asLong()).isEqualTo(store.revision());
        assertThat(configOnDisk().path("tabs").get(0).has("isDefault")).isTrue();
    }

    @Test
    void readReturnsDeepCopy() {
        ConfigStore store = TestStores.open(dir);
        HeimConfig copy = store.read();
        copy.getTabs().clear();
        copy.getSettings().setTitle("mutated");

        assertThat(store.read().getTabs()).hasSize(1);
        assertThat(store.read().getSettings().getTitle()).isEqualTo("Heim");
    }

    // --- round trip / unknown fields ------------------------------------------------------

    @Test
    void roundTripPreservesEverything() throws IOException {
        TestStores.installFixture(dir, "config-unknown-fields.json");
        ConfigStore store = TestStores.open(dir);
        JsonNode original = JSON.readTree(TestStores.fixture("config-unknown-fields.json"));

        // Valid fixture: loading must not rewrite the file.
        assertThat(store.revision()).isEqualTo(7);
        assertThat(configOnDisk()).isEqualTo(original);

        store.update(null, c -> null);
        JsonNode saved = configOnDisk();
        assertThat(saved.path("revision").asLong()).isEqualTo(8);
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved).put("revision", 7);
        assertThat(saved).isEqualTo(original);

        // Reload from disk: same model again.
        ConfigStore reopened = TestStores.open(dir);
        assertThat(JSON.readTree(reopened.toJson(reopened.read())))
                .isEqualTo(JSON.readTree(store.toJson(store.read())));
    }

    @Test
    void unknownFieldsPreservedAtEveryLevelAfterSave() throws IOException {
        TestStores.installFixture(dir, "config-unknown-fields.json");
        ConfigStore store = TestStores.open(dir);
        store.update(null, c -> {
            c.getSettings().setTitle("Changed");
            return null;
        });

        JsonNode root = configOnDisk();
        JsonNode settings = root.path("settings");
        JsonNode tab = root.path("tabs").get(0);
        JsonNode service = root.path("services").get(0);
        JsonNode link = service.path("links").get(0);

        assertThat(root.path("rootFuture").path("nested").size()).isEqualTo(3);
        assertThat(settings.path("title").asText()).isEqualTo("Changed");
        assertThat(settings.path("settingsFuture").asText()).isEqualTo("s");
        assertThat(settings.path("networks").get(0).path("cidr").asText()).isEqualTo("192.168.1.0/24");
        assertThat(settings.path("background").path("backgroundFuture").asBoolean()).isTrue();
        assertThat(settings.path("probe").path("probeDefaultsFuture").asInt()).isEqualTo(1);
        assertThat(settings.path("auth").path("authFuture").asText()).isEqualTo("a");
        assertThat(tab.path("requireLogin").isBoolean()).isTrue();
        assertThat(tab.path("icon").path("tabIconFuture").asInt()).isEqualTo(5);
        assertThat(tab.path("groups").get(0).path("groupFuture").path("collapsed").asBoolean()).isTrue();
        assertThat(service.path("serviceFuture").get(0).asText()).isEqualTo("x");
        assertThat(service.path("icon").path("iconFuture").asText()).isEqualTo("i");
        assertThat(link.path("linkFuture").asInt()).isEqualTo(42);
        assertThat(link.path("probe").path("type").asText()).isEqualTo("http");
    }

    @Test
    void unknownFieldsSurviveReadCopy() {
        TestStores.installFixture(dir, "config-unknown-fields.json");
        ConfigStore store = TestStores.open(dir);
        HeimConfig config = store.read();

        assertThat(config.getExtra()).containsKey("rootFuture");
        assertThat(config.getServices().getFirst().getLinks().getFirst().getProbe().getExtra())
                .containsEntry("type", "http");
    }

    // --- atomic write / backup ------------------------------------------------------------

    @Test
    void atomicWriteLeavesNoTmpAndKeepsPreviousVersionAsBak() throws IOException {
        ConfigStore store = TestStores.open(dir);
        long first = store.revision();
        assertThat(dir.resolve("config.json.bak")).doesNotExist();

        store.update(null, c -> {
            c.getSettings().setTitle("Second");
            return null;
        });

        assertThat(fileNames()).noneMatch(n -> n.endsWith(".tmp"));
        assertThat(dir.resolve("config.json.bak")).exists();
        JsonNode bak = JSON.readTree(Files.readAllBytes(dir.resolve("config.json.bak")));
        assertThat(bak.path("revision").asLong()).isEqualTo(first);
        assertThat(bak.path("settings").path("title").asText()).isEqualTo("Heim");
        assertThat(configOnDisk().path("settings").path("title").asText()).isEqualTo("Second");
    }

    // --- revision -------------------------------------------------------------------------

    @Test
    void revisionIncrementsAndPersistsAcrossReload() {
        ConfigStore store = TestStores.open(dir);
        long start = store.revision();

        Versioned<String> v1 = store.update(null, c -> "a");
        Versioned<String> v2 = store.update(start + 1, c -> "b");

        assertThat(v1.value()).isEqualTo("a");
        assertThat(v1.revision()).isEqualTo(start + 1);
        assertThat(v2.revision()).isEqualTo(start + 2);
        assertThat(store.revision()).isEqualTo(start + 2);
        assertThat(store.read().getRevision()).isEqualTo(start + 2);

        assertThat(TestStores.open(dir).revision()).isEqualTo(start + 2);
    }

    @Test
    void staleExpectedRevisionThrowsAndSavesNothing() throws IOException {
        ConfigStore store = TestStores.open(dir);
        long rev = store.revision();
        byte[] before = Files.readAllBytes(dir.resolve("config.json"));

        assertThatThrownBy(() -> store.update(rev - 1, c -> {
            c.getSettings().setTitle("lost");
            return null;
        }))
                .isInstanceOfSatisfying(RevisionConflictException.class,
                        e -> assertThat(e.currentTag()).isEqualTo(String.valueOf(rev)));

        assertThat(store.revision()).isEqualTo(rev);
        assertThat(store.read().getSettings().getTitle()).isEqualTo("Heim");
        assertThat(Files.readAllBytes(dir.resolve("config.json"))).isEqualTo(before);
    }

    @Test
    void mutatorExceptionSavesNothing() throws IOException {
        List<Object> events = new ArrayList<>();
        ConfigStore store = TestStores.open(dir, events);
        long rev = store.revision();
        byte[] before = Files.readAllBytes(dir.resolve("config.json"));

        assertThatThrownBy(() -> store.update(null, c -> {
            c.getSettings().setTitle("lost");
            c.getTabs().clear();
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");

        assertThat(store.revision()).isEqualTo(rev);
        assertThat(store.read().getSettings().getTitle()).isEqualTo("Heim");
        assertThat(store.read().getTabs()).hasSize(1);
        assertThat(Files.readAllBytes(dir.resolve("config.json"))).isEqualTo(before);
        assertThat(events).isEmpty();
    }

    @Test
    void updatePublishesChangedEventWithSavedState() {
        List<Object> events = new ArrayList<>();
        ConfigStore store = TestStores.open(dir, events);
        store.update(null, c -> {
            c.getSettings().setTitle("Evented");
            return null;
        });

        assertThat(events).singleElement().isInstanceOfSatisfying(ConfigChangedEvent.class, e -> {
            assertThat(e.revision()).isEqualTo(store.revision());
            assertThat(e.config().getSettings().getTitle()).isEqualTo("Evented");
        });
    }

    @Test
    void updateRepairsInvariantsBeforeSaving() {
        ConfigStore store = TestStores.open(dir);
        store.update(null, c -> {
            c.getTabs().clear();
            return null;
        });
        assertThat(store.read().getTabs()).singleElement().satisfies(t -> assertThat(t.isDefault()).isTrue());
    }

    // --- corruption / migration -----------------------------------------------------------

    @Test
    void corruptConfigFallsBackToBakAndQuarantinesCorruptFile() throws IOException {
        ConfigStore store = TestStores.open(dir);
        store.update(null, c -> {
            c.getSettings().setTitle("Good");
            return null;
        });
        store.update(null, c -> {
            c.getSettings().setTitle("Newest");
            return null;
        });
        // .bak now holds "Good"; corrupt the main file.
        Files.writeString(dir.resolve("config.json"), "{ this is not json", StandardCharsets.UTF_8);

        ConfigStore recovered = TestStores.open(dir);

        assertThat(recovered.read().getSettings().getTitle()).isEqualTo("Good");
        assertThat(fileNames()).anyMatch(n -> n.startsWith("config.json.corrupt-"));
        Path corrupt;
        try (Stream<Path> files = Files.list(dir)) {
            corrupt = files.filter(p -> p.getFileName().toString().startsWith("config.json.corrupt-")).findFirst()
                    .orElseThrow();
        }
        assertThat(Files.readString(corrupt)).isEqualTo("{ this is not json");
        assertThat(configOnDisk().path("settings").path("title").asText()).isEqualTo("Good");
        // The good backup was not overwritten by the corrupt file.
        JsonNode bak = JSON.readTree(Files.readAllBytes(dir.resolve("config.json.bak")));
        assertThat(bak.path("settings").path("title").asText()).isEqualTo("Good");
    }

    @Test
    void corruptConfigWithoutBackupFailsLoudly() throws IOException {
        Files.writeString(dir.resolve("config.json"), "[]");
        assertThatThrownBy(() -> TestStores.open(dir)).isInstanceOf(IllegalStateException.class);
        assertThat(dir.resolve("config.json")).hasContent("[]");
    }

    @Test
    void migratesV0ConfigWithoutSchemaVersion() throws IOException {
        TestStores.installFixture(dir, "config-v0.json");
        ConfigStore store = TestStores.open(dir);

        HeimConfig config = store.read();
        assertThat(config.getSchemaVersion()).isEqualTo(1);
        assertThat(config.getSettings().getTitle()).isEqualTo("Legacy");
        Tab tab = config.getTabs().getFirst();
        assertThat(tab.getId()).isEqualTo("tab_old00001");
        assertThat(tab.getGroups().getFirst().getServiceIds()).containsExactly("svc_old00001");
        Service service = config.getServices().getFirst();
        assertThat(service.getPrimaryLinkId()).isEqualTo("lnk_old00001");
        assertThat(service.getLinks().getFirst().getProbe()).isNotNull();
        assertThat(service.getLinks().getFirst().getProbe().getExpectedStatus()).isEqualTo("200-399");

        // Migrated result is persisted with a schemaVersion and a bumped revision.
        JsonNode disk = configOnDisk();
        assertThat(disk.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(disk.path("revision").asLong()).isEqualTo(1);
    }

    @Test
    void newerSchemaVersionIsLoadedAsIs() throws IOException {
        String json = new String(TestStores.fixture("config-unknown-fields.json"), StandardCharsets.UTF_8)
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99");
        Files.writeString(dir.resolve("config.json"), json);

        ConfigStore store = TestStores.open(dir);
        store.update(null, c -> null);

        assertThat(store.read().getSchemaVersion()).isEqualTo(99);
        assertThat(configOnDisk().path("schemaVersion").asInt()).isEqualTo(99);
    }

    @Test
    void parseMigratesAndRepairsWithoutTouchingStore() throws IOException {
        ConfigStore store = TestStores.open(dir);
        long rev = store.revision();

        HeimConfig parsed = store.parse(TestStores.fixture("config-v0.json"));

        assertThat(parsed.getSchemaVersion()).isEqualTo(1);
        assertThat(parsed.getServices()).extracting(Service::getName).containsExactly("Router");
        assertThat(store.revision()).isEqualTo(rev);
        assertThat(store.read().getServices()).isEmpty();
        assertThatThrownBy(() -> store.parse("nope".getBytes())).isInstanceOf(IOException.class);
    }

    @Test
    void replaceSwapsContentAndKeepsRevisionMonotonic() throws IOException {
        ConfigStore store = TestStores.open(dir);
        store.update(null, c -> null);
        long rev = store.revision();

        HeimConfig incoming = store.parse(TestStores.fixture("config-unknown-fields.json"));
        Versioned<Void> result = store.replace(incoming);

        assertThat(result.revision()).isEqualTo(rev + 1);
        assertThat(store.read().getSettings().getTitle()).isEqualTo("Home");
        assertThat(store.read().getExtra()).containsKey("rootFuture");
        assertThat(store.read().getServices()).extracting(Service::getId).containsExactly("svc_prox0001");
    }
}
