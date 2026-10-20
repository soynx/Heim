package dev.soynx.heim.probe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.model.ProbeMethod;

/**
 * Performs a single HTTP probe. One client per (ignoreTls, followRedirects) combination; the
 * trust-all variant uses an {@link X509ExtendedTrustManager} that also skips hostname checks,
 * which keeps the relaxation local to that client instead of a JVM-wide system property.
 */
@Component
public class HttpProber implements DisposableBean {

    static final String USER_AGENT = "Heim/1.0";
    private static final int MAX_ERROR_LENGTH = 200;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Indexed by {@code (ignoreTls ? 2 : 0) + (followRedirects ? 1 : 0)}. */
    private final HttpClient[] clients = new HttpClient[4];

    public HttpProber() {
        SSLContext trustAll = trustAllContext();
        for (int i = 0; i < clients.length; i++) {
            boolean ignoreTls = (i & 2) != 0;
            boolean followRedirects = (i & 1) != 0;
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .executor(executor)
                    .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
            if (ignoreTls) {
                builder.sslContext(trustAll);
            }
            clients[i] = builder.build();
        }
    }

    /** Probes {@code target}; never throws, failures become a DOWN result with a short message. */
    public LinkStatus probe(ProbeTarget target) {
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        Duration timeout = target.timeout();
        CompletableFuture<HttpResponse<Void>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.url().trim()))
                    .method(target.method() == ProbeMethod.HEAD ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody())
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .build();
            pending = client(target).sendAsync(request, HttpResponse.BodyHandlers.discarding());
            // The request timeout only covers the response headers; this bounds the body as well.
            HttpResponse<Void> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long latency = elapsedMs(started);
            int code = response.statusCode();
            if (StatusMatcher.of(target.expectedStatus()).matches(code)) {
                return LinkStatus.up(code, latency, checkedAt);
            }
            return LinkStatus.down(code, latency, checkedAt, "Unexpected status " + code);
        } catch (TimeoutException e) {
            return LinkStatus.down(null, null, checkedAt, timeoutMessage(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LinkStatus.down(null, null, checkedAt, "Interrupted");
        } catch (ExecutionException e) {
            return LinkStatus.down(null, null, checkedAt, describe(e.getCause() != null ? e.getCause() : e, timeout));
        } catch (RuntimeException e) {
            return LinkStatus.down(null, null, checkedAt, describe(e, timeout));
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    @Override
    public void destroy() {
        for (HttpClient client : clients) {
            client.shutdownNow();
        }
        executor.shutdownNow();
    }

    private HttpClient client(ProbeTarget target) {
        return clients[(target.ignoreTls() ? 2 : 0) + (target.followRedirects() ? 1 : 0)];
    }

    /** Short, human readable reason for a failed probe. */
    static String describe(Throwable error, Duration timeout) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) {
                return timeoutMessage(timeout);
            }
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) {
                return "Unknown host";
            }
            if (t instanceof SSLException) {
                return truncate("TLS error: " + messageOf(t));
            }
            if (t instanceof NoRouteToHostException) {
                return "No route to host";
            }
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return "Connection refused";
            }
        }
        if (error instanceof IllegalArgumentException) {
            return truncate("Invalid URL: " + messageOf(error));
        }
        if (error instanceof IOException) {
            return truncate("Connection failed: " + messageOf(error));
        }
        return truncate(messageOf(error));
    }

    private static String timeoutMessage(Duration timeout) {
        return "Timed out after " + timeout.toSeconds() + "s";
    }

    private static String messageOf(Throwable t) {
        return t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static SSLContext trustAllContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new TrustAllManager()}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create trust-all SSL context", e);
        }
    }

    /** Accepts every certificate and, being an extended manager, skips the hostname check too. */
    private static final class TrustAllManager extends X509ExtendedTrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
