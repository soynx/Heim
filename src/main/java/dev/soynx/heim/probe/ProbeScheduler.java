package dev.soynx.heim.probe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.ConfigChangedEvent;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.Service;

/**
 * Keeps one periodic task per probe-enabled link in sync with the config. A single platform thread
 * only does the timing; every probe runs on its own virtual thread. Links whose effective
 * {@link ProbeTarget} is unchanged keep their schedule across config changes.
 */
@Component
public class ProbeScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProbeScheduler.class);

    private final ConfigStore store;
    private final HttpProber prober;
    private final StatusRegistry registry;
    private final StatusBroadcaster broadcaster;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("heim-probe-timer").daemon().factory());
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("heim-probe-", 0).factory());
    /** Snapshots go out from here so that a slow SSE client never holds up a config save. */
    private final ExecutorService snapshots = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("heim-status-snapshot").daemon().factory());

    /** linkId -> running schedule; guarded by {@code this}. */
    private final Map<String, Scheduled> tasks = new HashMap<>();
    /** Revision of the config the schedules reflect; guarded by {@code this}. */
    private long syncedRevision = -1;

    public ProbeScheduler(ConfigStore store, HttpProber prober, StatusRegistry registry,
            StatusBroadcaster broadcaster) {
        this.store = store;
        this.prober = prober;
        this.registry = registry;
        this.broadcaster = broadcaster;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        sync(store.read());
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        sync(event.config());
    }

    /**
     * Re-syncs the schedules with {@code config}; cheap and non-blocking, so safe in event listeners.
     * Events are published outside the store lock and may arrive out of order, so older configs are ignored.
     */
    void sync(HeimConfig config) {
        if (syncSchedules(config)) {
            try {
                snapshots.execute(broadcaster::snapshot);
            } catch (RejectedExecutionException e) {
                // Shutting down.
            }
        }
    }

    /** Returns whether the set of probed links changed. */
    private synchronized boolean syncSchedules(HeimConfig config) {
        if (timer.isShutdown() || config.getRevision() < syncedRevision) {
            return false;
        }
        syncedRevision = config.getRevision();
        Map<String, ProbeTarget> wanted = new LinkedHashMap<>();
        Map<String, List<String>> layout = new LinkedHashMap<>();
        for (Service service : config.getServices()) {
            List<String> linkIds = service.getLinks().stream()
                    .filter(link -> link.getProbe() != null && link.getProbe().isEnabled())
                    .map(Link::getId)
                    .toList();
            if (linkIds.isEmpty()) {
                continue;
            }
            layout.put(service.getId(), linkIds);
            for (Link link : service.getLinks()) {
                if (linkIds.contains(link.getId())) {
                    wanted.put(link.getId(), ProbeTarget.of(service, link, config.getSettings().getProbe()));
                }
            }
        }

        tasks.entrySet().removeIf(entry -> {
            boolean keep = entry.getValue().target.equals(wanted.get(entry.getKey()));
            if (!keep) {
                entry.getValue().future.cancel(false);
            }
            return !keep;
        });
        boolean layoutChanged = registry.retain(layout);
        wanted.forEach((linkId, target) -> tasks.computeIfAbsent(linkId, id -> schedule(target)));
        return layoutChanged;
    }

    private Scheduled schedule(ProbeTarget target) {
        Scheduled scheduled = new Scheduled(target);
        long interval = target.interval().toMillis();
        scheduled.future = timer.scheduleAtFixedRate(() -> dispatch(scheduled), 0, interval, TimeUnit.MILLISECONDS);
        return scheduled;
    }

    /** Hands the probe to a virtual thread, skipping the tick if the previous run is still going. */
    private void dispatch(Scheduled scheduled) {
        if (!scheduled.running.compareAndSet(false, true)) {
            return;
        }
        try {
            workers.execute(() -> run(scheduled));
        } catch (RuntimeException e) {
            scheduled.running.set(false);
        }
    }

    private void run(Scheduled scheduled) {
        try {
            ProbeTarget target = scheduled.target;
            LinkStatus status = prober.probe(target);
            if (!isCurrent(scheduled)) {
                return;
            }
            registry.record(target.serviceId(), target.linkId(), status).ifPresent(broadcaster::update);
        } catch (RuntimeException e) {
            log.warn("Probe of link {} failed unexpectedly", scheduled.target.linkId(), e);
        } finally {
            scheduled.running.set(false);
        }
    }

    private synchronized boolean isCurrent(Scheduled scheduled) {
        return tasks.get(scheduled.target.linkId()) == scheduled;
    }

    synchronized int scheduledCount() {
        return tasks.size();
    }

    @Override
    public void destroy() throws InterruptedException {
        synchronized (this) {
            tasks.values().forEach(s -> s.future.cancel(false));
            tasks.clear();
        }
        timer.shutdownNow();
        snapshots.shutdownNow();
        workers.shutdownNow();
        workers.awaitTermination(2, TimeUnit.SECONDS);
    }

    private static final class Scheduled {

        final ProbeTarget target;
        final AtomicBoolean running = new AtomicBoolean();
        volatile ScheduledFuture<?> future;

        Scheduled(ProbeTarget target) {
            this.target = target;
        }
    }
}
