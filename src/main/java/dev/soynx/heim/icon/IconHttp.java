package dev.soynx.heim.icon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Small HTTP GET helper for icon downloads: follows redirects, enforces a size cap and an overall
 * deadline, and never throws on network trouble.
 */
@Component
public class IconHttp {

    private static final Logger log = LoggerFactory.getLogger(IconHttp.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; Heim)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient strict;
    private final HttpClient insecure;

    public IconHttp() {
        this.strict = builder().build();
        this.insecure = builder().sslContext(trustAllContext()).build();
    }

    /** A completed exchange; {@code uri} is the final URI after redirects. */
    public record Response(int status, URI uri, String contentType, byte[] body) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public boolean isHtml() {
            return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html");
        }
    }

    /**
     * Fetches {@code uri}. Bodies larger than {@code maxBytes} make the request fail, unless
     * {@code truncate} is set, in which case the first {@code maxBytes} are returned.
     *
     * @param ignoreTls accept any certificate and host name (self-signed homelab services)
     * @return empty on any network error, timeout, oversized body or non-HTTP URI
     */
    public Optional<Response> get(URI uri, boolean ignoreTls, long maxBytes, boolean truncate, Duration timeout) {
        if (uri == null || uri.getHost() == null || !isHttp(uri)) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,image/*,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = (ignoreTls ? insecure : strict)
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                OptionalLong length = response.headers().firstValueAsLong("Content-Length");
                if (!truncate && length.isPresent() && length.getAsLong() > maxBytes) {
                    log.debug("Icon fetch {}: body of {} bytes exceeds cap", uri, length.getAsLong());
                    return Optional.empty();
                }
                byte[] body = readCapped(in, maxBytes, truncate, deadline);
                if (body == null) {
                    log.debug("Icon fetch {}: body exceeds {} bytes", uri, maxBytes);
                    return Optional.empty();
                }
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                return Optional.of(new Response(response.statusCode(), response.uri(), contentType, body));
            }
        } catch (IOException | IllegalArgumentException e) {
            log.debug("Icon fetch {} failed: {}", uri, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * Reads at most {@code maxBytes}; returns null if there is more and {@code truncate} is off.
     * The request timeout only covers the response headers, so a watchdog closes the stream at the deadline.
     */
    private static byte[] readCapped(InputStream in, long maxBytes, boolean truncate, long deadline)
            throws IOException {
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        CompletableFuture<Void> watchdog = CompletableFuture.runAsync(() -> closeQuietly(in),
                CompletableFuture.delayedExecutor(remainingMillis, TimeUnit.MILLISECONDS));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + (long) read > maxBytes) {
                    if (!truncate) {
                        return null;
                    }
                    out.write(buffer, 0, (int) (maxBytes - out.size()));
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            watchdog.cancel(false);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Only used to abort a stalled read.
        }
    }

    private static HttpClient.Builder builder() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
    }

    /**
     * Trusts every certificate. Extending {@link X509ExtendedTrustManager} matters: the JDK then skips
     * its own host name check, which a plain {@code X509TrustManager} would still get wrapped in.
     */
    private static SSLContext trustAllContext() {
        TrustManager trustAll = new X509ExtendedTrustManager() {
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
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create TLS context", e);
        }
    }
}
