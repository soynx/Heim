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
        HeimConfig config = store.read();
        Service service = config.findService(serviceId).orElseThrow(() -> NotFoundException.service(serviceId));
        if (service.getIcon().getSource() != IconSource.UPLOAD) {
            IconKey key = IconKey.of(service);
            failures.remove(serviceId);
            Optional<IconFetcher.IconFile> icon = fetcher.fetch(service);
            if (icon.isEmpty()) {
                failures.put(serviceId, new Failure(key, Instant.now()));
                log.info("No icon found for service {} ({})", serviceId, key.source());
            } else {
                Optional<ServiceDto> committed = commit(serviceId, key, icon.get(), true);
                if (committed.isPresent()) {
                    return tagged(committed.get());
                }
            }
            config = store.read();
            service = config.findService(serviceId).orElseThrow(() -> NotFoundException.service(serviceId));
        }
        return tagged(toDto(config, service));
    }

    /** Starts background fetches for services that lack a cached icon. */
    void scan(HeimConfig config) {
        Instant now = Instant.now();
        for (Service service : config.getServices()) {
            IconRef icon = service.getIcon();
            if (icon == null || icon.getSource() == IconSource.UPLOAD || icon.getCachedFile() != null) {
                continue;
            }
            IconKey key = IconKey.of(service);
            Failure failure = failures.get(service.getId());
            if (failure != null && failure.key().equals(key) && failure.at().plus(FAILURE_BACKOFF).isAfter(now)) {
                continue;
            }
            if (inFlight.putIfAbsent(service.getId(), key) != null) {
                continue;
            }
            try {
                executor.execute(() -> fetchInBackground(service, key));
            } catch (RejectedExecutionException e) {
                inFlight.remove(service.getId(), key);
                return; // shutting down
            }
        }
    }

    private void fetchInBackground(Service service, IconKey key) {
        String id = service.getId();
        boolean superseded = false;
        try {
            Optional<IconFetcher.IconFile> icon;
            fetchPermits.acquire();
            try {
                icon = fetcher.fetch(service);
            } finally {
                fetchPermits.release();
            }
            if (icon.isEmpty()) {
                failures.put(id, new Failure(key, Instant.now()));
                log.info("No icon found for service {} ({}); retrying in {}", id, key.source(), FAILURE_BACKOFF);
            } else {
                failures.remove(id);
                superseded = commit(id, key, icon.get(), false).isEmpty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Background icon fetch for service {} failed", id, e);
        } finally {
            inFlight.remove(id, key);
        }
        if (superseded) {
            // The icon changed while we were fetching; the change's own scan skipped it as in flight.
            scan(store.read());
        }
    }

    /**
     * Saves the icon file and references it, provided the service's icon definition still matches
     * {@code key} (and, unless {@code overwrite}, nothing else has cached an icon meanwhile).
     *
     * @return the updated service, or empty if the result was discarded
     */
    private Optional<ServiceDto> commit(String serviceId, IconKey key, IconFetcher.IconFile icon,
            boolean overwrite) {
        String stored = files.save(serviceId, icon);
        pendingFiles.add(stored);
        try {
            return Optional.of(store.update(null, config -> {
                Service service = config.findService(serviceId).orElseThrow(Superseded::new);
                if (!IconKey.of(service).equals(key) || !overwrite && service.getIcon().getCachedFile() != null) {
                    throw new Superseded();
                }
                service.getIcon().setCachedFile(stored);
                return toDto(config, service);
            }).value());
        } catch (Superseded e) {
            files.delete(stored);
            return Optional.empty();
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
