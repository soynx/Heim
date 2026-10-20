package dev.soynx.heim.probe;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out of status changes to SSE subscribers. Each new subscriber gets a {@code snapshot}, then
 * {@code update} events; a comment heartbeat keeps proxies from closing idle streams and is also
 * how disconnected clients are noticed and dropped.
 */
@Component
public class StatusBroadcaster implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StatusBroadcaster.class);
    static final long HEARTBEAT_SECONDS = 25;

    private final StatusRegistry registry;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("heim-sse-heartbeat").daemon().factory());

    public StatusBroadcaster(StatusRegistry registry) {
        this.registry = registry;
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Registers a new subscriber (no timeout) and sends it the current snapshot. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, SseEmitter.event().name("snapshot").data(registry.snapshot()));
        return emitter;
    }

    public void update(StatusUpdate update) {
        broadcast(SseEmitter.event().name("update").data(update));
    }

    /** Re-sends the full snapshot, e.g. after links were added or removed. */
    public void snapshot() {
        broadcast(SseEmitter.event().name("snapshot").data(registry.snapshot()));
    }

    int subscriberCount() {
        return emitters.size();
    }

    private void sendHeartbeat() {
        broadcast(SseEmitter.event().comment("heartbeat"));
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        // build() is not idempotent, so build once and send the same parts to everyone.
        Set<ResponseBodyEmitter.DataWithMediaType> parts = event.build();
        for (SseEmitter emitter : emitters) {
            send(emitter, parts);
        }
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        send(emitter, event.build());
    }

    private void send(SseEmitter emitter, Set<ResponseBodyEmitter.DataWithMediaType> parts) {
        try {
            emitter.send(parts);
        } catch (IOException | IllegalStateException e) {
            // Client went away or the emitter already completed.
            emitters.remove(emitter);
            log.debug("Dropping SSE subscriber: {}", e.toString());
        }
    }

    @Override
    public void destroy() {
        heartbeat.shutdownNow();
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }
}
