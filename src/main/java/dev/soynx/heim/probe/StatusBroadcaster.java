package dev.soynx.heim.probe;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Access is only checked when a stream is opened, so streams are finite and are closed when the
 * session that opened them ends or the login rules change; clients then reconnect and are re-authorized.
 */
@Component
public class StatusBroadcaster implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StatusBroadcaster.class);
    static final long HEARTBEAT_SECONDS = 25;
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    private static final String NO_SESSION = "";

    private final StatusRegistry registry;
    /** Emitter to the id of the HTTP session that opened it ({@link #NO_SESSION} if none). */
    private final Map<SseEmitter, String> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("heim-sse-heartbeat").daemon().factory());

    public StatusBroadcaster(StatusRegistry registry) {
        this.registry = registry;
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Registers a new subscriber for the given HTTP session (may be null) and sends it the current snapshot. */
    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.put(emitter, Optional.ofNullable(sessionId).orElse(NO_SESSION));
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

    /** Ends every stream, e.g. because the login rules changed. */
    public void closeAll() {
        emitters.keySet().forEach(this::close);
    }

    /** Ends the streams opened by one HTTP session, e.g. on logout. */
    public void closeSession(String sessionId) {
        emitters.forEach((emitter, session) -> {
            if (Objects.equals(session, sessionId)) {
                close(emitter);
            }
        });
    }

    public int subscriberCount() {
        return emitters.size();
    }

    private void close(SseEmitter emitter) {
        emitters.remove(emitter);
        emitter.complete();
    }

    private void sendHeartbeat() {
        broadcast(SseEmitter.event().comment("heartbeat"));
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        // build() is not idempotent, so build once and send the same parts to everyone.
        Set<ResponseBodyEmitter.DataWithMediaType> parts = event.build();
        for (SseEmitter emitter : emitters.keySet()) {
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
        closeAll();
    }
}
