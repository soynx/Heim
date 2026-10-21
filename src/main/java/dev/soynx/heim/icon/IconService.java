package dev.soynx.heim.icon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.config.ConfigChangedEvent;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.Tagged;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.error.NotFoundException;
import dev.soynx.heim.service.Membership;
import dev.soynx.heim.service.ServiceManager;
import jakarta.annotation.PreDestroy;

/**
 * Keeps every service's icon cached in the icons directory. Services without a cached file are
 * fetched in the background after startup and after each config change; failed lookups are not
 * retried for an hour unless refreshed explicitly. Files no longer referenced are cleaned up.
 */
@Component
public class IconService {

    private static final Logger log = LoggerFactory.getLogger(IconService.class);

    public static final long MAX_UPLOAD_BYTES = IconFetcher.MAX_ICON_BYTES;
    private static final Duration FAILURE_BACKOFF = Duration.ofHours(1);
    private static final Duration CLEANUP_DELAY = Duration.ofSeconds(5);
    /** Protects files written by others (backup import) between writing and the config swap. */
    private static final Duration ORPHAN_GRACE = Duration.ofMinutes(2);
    private static final int MAX_CONCURRENT_FETCHES = 8;
    private static final int MAX_ORIGINAL_NAME = 200;

    private final ConfigStore store;
    private final IconFetcher fetcher;
    private final IconFiles files;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("icon-cleanup").factory());
    private final Semaphore fetchPermits = new Semaphore(MAX_CONCURRENT_FETCHES);
    /** Service id → icon definition currently being fetched in the background. */
    private final Map<String, IconKey> inFlight = new ConcurrentHashMap<>();
    /** Service id → last failed lookup, to avoid hammering unreachable hosts. */
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    /** Stored files whose config update has not completed yet; never cleaned up. */
    private final Set<String> pendingFiles = ConcurrentHashMap.newKeySet();
    private final AtomicReference<ScheduledFuture<?>> pendingCleanup = new AtomicReference<>();

    public IconService(ConfigStore store, IconFetcher fetcher, IconFiles files) {
        this.store = store;
        this.fetcher = fetcher;
        this.files = files;
    }

    /**
     * What a cached file was fetched for. Includes the resolved favicon page, so a fetch that raced
     * with an edit of the link is discarded.
     */
    record IconKey(IconSource source, String value, String faviconLinkId, String target) {

        static IconKey of(Service service) {
            IconRef icon = service.getIcon();
            String target = icon.getSource() == IconSource.FAVICON
                    ? IconFetcher.faviconTarget(service).map(t -> t.url() + (t.ignoreTls() ? " insecure" : "")).orElse(null)
                    : null;
            return new IconKey(icon.getSource(), icon.getValue(), icon.getFaviconLinkId(), target);
        }

        static IconKey of(Tab tab) {
            IconRef icon = tab.getIcon();
            return new IconKey(icon.getSource(), icon.getValue(), icon.getFaviconLinkId(), null);
        }
    }

    /** A service or a tab; their IDs never collide, so they share the in-flight and failure maps. */
    private record Owner(String id, boolean tab) {

        static Owner of(Service service) {
            return new Owner(service.getId(), false);
        }

        static Owner of(Tab tab) {
            return new Owner(tab.getId(), true);
        }

        Optional<IconRef> iconIn(HeimConfig config) {
            return tab ? config.findTab(id).map(Tab::getIcon) : config.findService(id).map(Service::getIcon);
        }

        Optional<IconKey> keyIn(HeimConfig config) {
            return tab ? config.findTab(id).filter(t -> t.getIcon() != null).map(IconKey::of)
                    : config.findService(id).map(IconKey::of);
        }
    }

    private record Failure(IconKey key, Instant at) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scan(store.read());
        scheduleCleanup();
    }

    /** Runs on the saving thread; only hands work off, never updates the config itself. */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        scan(event.config());
        scheduleCleanup();
    }

    /** Stores an uploaded icon and switches the service to it. */
    public Tagged<ServiceDto> upload(String serviceId, MultipartFile file, String expectedTag) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("Icon must be at most 2 MB");
        }
        if (store.read().findService(serviceId).isEmpty()) {
            throw NotFoundException.service(serviceId);
        }
        IconFetcher.IconFile icon = IconFetcher.validate(readBytes(file))
                .orElseThrow(() -> new BadRequestException("Icon must be a PNG, JPEG, WebP, GIF, ICO or SVG image"));
        String stored = files.save(serviceId, icon);
        pendingFiles.add(stored);
        try {
            return tagged(store.update(null, config -> {
                Service service = config.findService(serviceId).orElseThrow(() -> NotFoundException.service(serviceId));
                ServiceManager.checkTag(expectedTag, config, service);
                IconRef ref = service.getIcon();
                ref.setSource(IconSource.UPLOAD);
                ref.setValue(originalName(file));
                ref.setCachedFile(stored);
                service.setUpdatedAt(Instant.now());
                return toDto(config, service);
            }).value());
        } catch (RuntimeException e) {
            files.delete(stored);
            throw e;
        } finally {
            pendingFiles.remove(stored);
        }
    }

    /**
     * Fetches the icon again right away. On failure the previous cached file (if any) is kept.
     * Uploaded icons have nothing to fetch and are returned unchanged.
     */
    public Tagged<ServiceDto> refresh(String serviceId) {
        Service service = store.read().findService(serviceId).orElseThrow(() -> NotFoundException.service(serviceId));
        if (service.getIcon().getSource() != IconSource.UPLOAD) {
            IconKey key = IconKey.of(service);
            failures.remove(serviceId);
            Optional<IconFetcher.IconFile> icon = fetcher.fetch(service);
            if (icon.isEmpty()) {
                failures.put(serviceId, new Failure(key, Instant.now()));
                log.info("No icon found for service {} ({})", serviceId, key.source());
            } else {
                commit(Owner.of(service), key, icon.get(), true);
            }
        }
        HeimConfig config = store.read();
        service = config.findService(serviceId).orElseThrow(() -> NotFoundException.service(serviceId));
        return tagged(toDto(config, service));
    }

    /**
     * Starts background fetches for services and tabs that lack a cached icon, or whose cached file is
     * gone from disk. Tabs have no links, so only their URL and library icons are fetched.
     */
    void scan(HeimConfig config) {
        for (Service service : config.getServices()) {
            if (needsFetch(service.getIcon())
                    && !start(Owner.of(service), IconKey.of(service), () -> fetcher.fetch(service))) {
                return;
            }
        }
        for (Tab tab : config.getTabs()) {
            IconRef icon = tab.getIcon();
            if (needsFetch(icon) && icon.getSource() != IconSource.FAVICON
                    && !start(Owner.of(tab), IconKey.of(tab), () -> fetcher.fetch(tab))) {
                return;
            }
        }
    }

    private boolean needsFetch(IconRef icon) {
        return icon != null && icon.getSource() != null && icon.getSource() != IconSource.UPLOAD && !isCached(icon);
    }

    private boolean isCached(IconRef icon) {
        return icon.getCachedFile() != null && files.find(icon.getCachedFile()).isPresent();
    }

    /** Returns false if the executor is shutting down. */
    private boolean start(Owner owner, IconKey key, Supplier<Optional<IconFetcher.IconFile>> fetch) {
        Failure failure = failures.get(owner.id());
        if (failure != null && failure.key().equals(key) && failure.at().plus(FAILURE_BACKOFF).isAfter(Instant.now())) {
            return true;
        }
        if (inFlight.putIfAbsent(owner.id(), key) != null) {
            return true;
        }
        try {
            executor.execute(() -> fetchInBackground(owner, key, fetch));
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.remove(owner.id(), key);
            return false;
        }
    }

    private void fetchInBackground(Owner owner, IconKey key, Supplier<Optional<IconFetcher.IconFile>> fetch) {
        String id = owner.id();
        boolean superseded = false;
        try {
            Optional<IconFetcher.IconFile> icon;
            fetchPermits.acquire();
            try {
                icon = fetch.get();
            } finally {
                fetchPermits.release();
            }
            if (icon.isEmpty()) {
                failures.put(id, new Failure(key, Instant.now()));
                log.info("No icon found for {} ({}); retrying in {}", id, key.source(), FAILURE_BACKOFF);
            } else {
                failures.remove(id);
                superseded = !commit(owner, key, icon.get(), false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Background icon fetch for {} failed", id, e);
        } finally {
            inFlight.remove(id, key);
        }
        if (superseded) {
            // The icon changed while we were fetching; the change's own scan skipped it as in flight.
            scan(store.read());
        }
    }

    /**
     * Saves the icon file and references it, provided the owner's icon definition still matches
     * {@code key} (and, unless {@code overwrite}, nothing else has cached an icon meanwhile).
     *
     * @return false if the result was discarded
     */
    private boolean commit(Owner owner, IconKey key, IconFetcher.IconFile icon, boolean overwrite) {
        String stored = files.save(owner.id(), icon);
        pendingFiles.add(stored);
        try {
            store.update(null, config -> {
                IconRef ref = owner.iconIn(config).orElseThrow(Superseded::new);
                if (!owner.keyIn(config).equals(Optional.of(key)) || !overwrite && isCached(ref)) {
                    throw new Superseded();
                }
                ref.setCachedFile(stored);
                return null;
            });
            return true;
        } catch (Superseded e) {
            files.delete(stored);
            return false;
        } catch (RuntimeException e) {
            files.delete(stored);
            throw e;
        } finally {
            pendingFiles.remove(stored);
        }
    }

    /** Debounced: a burst of config changes results in one directory scan. */
    private void scheduleCleanup() {
        scheduleCleanup(CLEANUP_DELAY);
    }

    private void scheduleCleanup(Duration delay) {
        try {
            ScheduledFuture<?> next = scheduler.schedule(this::cleanupOrphans, delay.toMillis(), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = pendingCleanup.getAndSet(next);
            if (previous != null) {
                previous.cancel(false);
            }
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    void cleanupOrphans() {
        try {
            Set<String> keep = referencedFiles(store.read());
            keep.addAll(pendingFiles);
            IconFiles.Cleanup result = files.deleteUnreferenced(keep, ORPHAN_GRACE);
            if (result.deleted() > 0) {
                log.info("Deleted {} unreferenced icon file(s)", result.deleted());
            }
            if (result.deferred() > 0) {
                // Recently replaced icons: come back once they are past the grace period.
                scheduleCleanup(ORPHAN_GRACE.plus(CLEANUP_DELAY));
            }
        } catch (RuntimeException e) {
            log.warn("Icon cleanup failed", e);
        }
    }

    static Set<String> referencedFiles(HeimConfig config) {
        Set<String> referenced = new HashSet<>();
        for (Service service : config.getServices()) {
            addCached(referenced, service.getIcon());
        }
        for (Tab tab : config.getTabs()) {
            addCached(referenced, tab.getIcon());
        }
        return referenced;
    }

    private static void addCached(Set<String> into, IconRef icon) {
        if (icon != null && icon.getCachedFile() != null) {
            into.add(icon.getCachedFile());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private static Tagged<ServiceDto> tagged(ServiceDto service) {
        return new Tagged<>(service, ServiceManager.tagOf(service));
    }

    private static ServiceDto toDto(HeimConfig config, Service service) {
        return ServiceDto.from(service, Membership.tabIdsOf(config, service.getId()));
    }

    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return null;
        }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).strip();
        return name.length() > MAX_ORIGINAL_NAME ? name.substring(0, MAX_ORIGINAL_NAME) : name;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Aborts a commit without saving: the icon changed while it was being fetched. */
    private static final class Superseded extends RuntimeException {
        Superseded() {
            super(null, null, false, false);
        }
    }
}
