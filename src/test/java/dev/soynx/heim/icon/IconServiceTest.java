package dev.soynx.heim.icon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.config.ConfigChangedEvent;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.DataPaths;
import dev.soynx.heim.config.HeimProperties;
import dev.soynx.heim.config.migration.MigrationChain;
import dev.soynx.heim.config.migration.V0ToV1Migration;
import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.icon.IconFetcher.IconFile;

class IconServiceTest {

    @TempDir
    Path dataDir;

    private ConfigStore store;
    private DataPaths paths;
    private IconService icons;
    private final AtomicInteger fetches = new AtomicInteger();
    private volatile CountDownLatch release = new CountDownLatch(0);
    private volatile boolean fetchSucceeds = true;

    @BeforeEach
    void setUp() {
        paths = new DataPaths(new HeimProperties(dataDir));
        store = new ConfigStore(paths, new MigrationChain(List.of(new V0ToV1Migration())), event -> {
            if (event instanceof ConfigChangedEvent changed && icons != null) {
                icons.onConfigChanged(changed);
            }
        });
        IconFetcher fetcher = new IconFetcher(null, null) {
            @Override
            public Optional<IconFile> fetch(Service service) {
                fetches.incrementAndGet();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return fetchSucceeds ? Optional.of(new IconFile(IconFetcherTest.PNG, IconFormat.PNG)) : Optional.empty();
            }
        };
        icons = new IconService(store, fetcher, new IconFiles(paths));
    }

    @AfterEach
    void tearDown() {
        icons.shutdown();
    }

    private String addService(IconSource source, String value) {
        return store.update(null, config -> {
            Service service = new Service();
            service.setId("svc_" + config.getServices().size());
            service.setName("Test");
            service.getIcon().setSource(source);
            service.getIcon().setValue(value);
            config.getServices().add(service);
            return service.getId();
        }).value();
    }

    private String cachedFile(String id) {
        return store.read().findService(id).orElseThrow().getIcon().getCachedFile();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 5s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void fetchesMissingIconsInBackground() throws InterruptedException {
        String id = addService(IconSource.LIBRARY, "proxmox");
        await(() -> cachedFile(id) != null);
        assertThat(cachedFile(id)).startsWith(id + "_").endsWith(".png");
        assertThat(paths.iconsDir().resolve(cachedFile(id))).exists();
    }

    @Test
    void discardsResultWhenIconChangedDuringFetch() throws InterruptedException, IOException {
        release = new CountDownLatch(1);
        String id = addService(IconSource.URL, "https://old.example/icon.png");
        await(() -> fetches.get() == 1);
        store.update(null, config -> {
            config.findService(id).orElseThrow().getIcon().setValue("https://new.example/icon.png");
            return null;
        });
        release.countDown();
        // The stale result is dropped and the new definition is fetched afterwards.
        await(() -> fetches.get() == 2 && cachedFile(id) != null);
        try (var listing = Files.list(paths.iconsDir())) {
            assertThat(listing.map(p -> p.getFileName().toString())).containsExactly(cachedFile(id));
        }
    }

    @Test
    void failedLookupsAreNotRetriedUntilRefresh() throws InterruptedException {
        fetchSucceeds = false;
        String id = addService(IconSource.LIBRARY, "nope");
        await(() -> fetches.get() == 1);
        Thread.sleep(100);
        addService(IconSource.UPLOAD, null); // unrelated change triggers another scan
        Thread.sleep(200);
        assertThat(fetches.get()).isEqualTo(1);

        fetchSucceeds = true;
        ServiceDto refreshed = icons.refresh(id).value();
        assertThat(refreshed.icon().getCachedFile()).isNotNull();
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void uploadValidatesAndSwitchesSource() {
        String id = addService(IconSource.UPLOAD, null);
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg' onload='x()'><circle r='2'/></svg>".getBytes();
        ServiceDto dto = icons.upload(id, new MockMultipartFile("file", "C:\\pics\\logo.svg", "image/svg+xml", svg), null)
                .value();
        assertThat(dto.icon().getSource()).isEqualTo(IconSource.UPLOAD);
        assertThat(dto.icon().getValue()).isEqualTo("logo.svg");
        assertThat(dto.icon().getCachedFile()).endsWith(".svg");
        assertThat(paths.iconsDir().resolve(dto.icon().getCachedFile())).content().doesNotContain("onload");

        assertThatThrownBy(() -> icons.upload(id,
                new MockMultipartFile("file", "x.png", "image/png", "<html></html>".getBytes()), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> icons.upload(id,
                new MockMultipartFile("file", "x.png", "image/png", new byte[(int) IconService.MAX_UPLOAD_BYTES + 1]),
                null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void cleanupDeletesOnlyOldUnreferencedFiles() throws IOException {
        String id = addService(IconSource.UPLOAD, null);
        String kept = icons.upload(id, new MockMultipartFile("file", "a.png", "image/png", IconFetcherTest.PNG), null)
                .value().icon().getCachedFile();
        Path orphan = Files.write(paths.iconsDir().resolve("svc_gone_abc.png"), IconFetcherTest.PNG);
        Path fresh = Files.write(paths.iconsDir().resolve("svc_new_abc.png"), IconFetcherTest.PNG);
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(1)));
        Files.setLastModifiedTime(orphan, old);
        Files.setLastModifiedTime(paths.iconsDir().resolve(kept), old);

        icons.cleanupOrphans();

        assertThat(orphan).doesNotExist();
        assertThat(fresh).exists();
        assertThat(paths.iconsDir().resolve(kept)).exists();
    }
}
