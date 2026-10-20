package dev.soynx.heim.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.soynx.heim.config.model.ProbeMethod;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

class HttpProberTest {

    private final HttpProber prober = new HttpProber();
    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
        prober.destroy();
    }

    @Test
    void upWhenStatusMatches() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello"));

        LinkStatus status = prober.probe(target(server.url("/health").toString(), ProbeMethod.GET, "200-399", false));

        assertThat(status.state()).isEqualTo(LinkState.UP);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(status.checkedAt()).isNotNull();
        assertThat(status.error()).isNull();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getHeader("User-Agent")).isEqualTo(HttpProber.USER_AGENT);
    }

    @Test
    void headRequestIsSent() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        LinkStatus status = prober.probe(target(server.url("/").toString(), ProbeMethod.HEAD, "204", false));

        assertThat(status.state()).isEqualTo(LinkState.UP);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getMethod()).isEqualTo("HEAD");
    }

    @Test
    void downOnUnexpectedStatus() {
        server.enqueue(new MockResponse().setResponseCode(503));

        LinkStatus status = prober.probe(target(server.url("/").toString(), ProbeMethod.GET, "200-399", false));

        assertThat(status.state()).isEqualTo(LinkState.DOWN);
        assertThat(status.statusCode()).isEqualTo(503);
        assertThat(status.error()).isEqualTo("Unexpected status 503");
    }

    @Test
    void downOnTimeout() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long started = System.nanoTime();
        LinkStatus status = prober.probe(target(server.url("/").toString(), ProbeMethod.GET, "200", false));

        assertThat(status.state()).isEqualTo(LinkState.DOWN);
        assertThat(status.statusCode()).isNull();
        assertThat(status.error()).isEqualTo("Timed out after 1s");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void downOnSlowBody() {
        server.enqueue(new MockResponse().setBody("x".repeat(64)).throttleBody(1, 1, TimeUnit.SECONDS));

        LinkStatus status = prober.probe(target(server.url("/").toString(), ProbeMethod.GET, "200", false));

        assertThat(status.state()).isEqualTo(LinkState.DOWN);
        assertThat(status.error()).isEqualTo("Timed out after 1s");
    }

    @Test
    void downOnConnectionRefused() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        LinkStatus status = prober.probe(target("http://127.0.0.1:" + port + "/", ProbeMethod.GET, "200", false));

        assertThat(status.state()).isEqualTo(LinkState.DOWN);
        assertThat(status.error()).isEqualTo("Connection refused");
    }

    @Test
    void downOnInvalidUrl() {
        LinkStatus status = prober.probe(target("smb://nas/share", ProbeMethod.GET, "200", false));

        assertThat(status.state()).isEqualTo(LinkState.DOWN);
        assertThat(status.error()).startsWith("Invalid URL");
    }

    @Test
    void redirectNotFollowedByDefault() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/target"));

        LinkStatus status = prober.probe(target(server.url("/").toString(), ProbeMethod.GET, "200", false));

        assertThat(status.state()).isEqualTo(LinkState.DOWN);
        assertThat(status.statusCode()).isEqualTo(302);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void redirectFollowedWhenEnabled() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/target"));
        server.enqueue(new MockResponse().setResponseCode(200));

        LinkStatus status = prober.probe(target(server.url("/").toString(), ProbeMethod.GET, "200", true));

        assertThat(status.state()).isEqualTo(LinkState.UP);
        assertThat(status.statusCode()).isEqualTo(200);
        server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/target");
    }

    private static ProbeTarget target(String url, ProbeMethod method, String expected, boolean followRedirects) {
        return new ProbeTarget("s1", "l1", url, method, expected, false, followRedirects,
                Duration.ofSeconds(60), Duration.ofSeconds(1));
    }
}
