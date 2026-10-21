package dev.soynx.heim.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.soynx.heim.probe.StatusBroadcaster;
import dev.soynx.heim.probe.StatusRegistry;
import dev.soynx.heim.probe.StatusSnapshot;

/** Live link/service health. Only links with probing enabled (and their services) are listed. */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final StatusRegistry registry;
    private final StatusBroadcaster broadcaster;

    public StatusController(StatusRegistry registry, StatusBroadcaster broadcaster) {
        this.registry = registry;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public StatusSnapshot status() {
        return registry.snapshot();
    }

    /** Sends {@code snapshot} on connect, then {@code update} events and a heartbeat comment every ~25s. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return broadcaster.subscribe(session != null ? session.getId() : null);
    }

    /**
     * A closed SSE stream is routine; without this the global handler would log it as an error and
     * then fail to write a JSON body into an event stream.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientGone() {
        // Nothing to send, the client is gone.
    }
}
