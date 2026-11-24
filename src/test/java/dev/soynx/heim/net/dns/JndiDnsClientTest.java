package dev.soynx.heim.net.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JndiDnsClientTest {

    private final DnsClient client = new JndiDnsClient();
    private FakeDnsServer server;

    @BeforeEach
    void start() throws IOException {
        server = new FakeDnsServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void readsBothFamilies() throws IOException {
        server.address("app.test", "10.1.1.1");
        server.address("app.test", "10.1.1.2");
        server.address("app.test", "fd00::1");

        assertThat(query("app.test", RecordType.A)).containsExactlyInAnyOrder("10.1.1.1", "10.1.1.2");
        assertThat(query("app.test", RecordType.AAAA)).containsExactly("fd00:0:0:0:0:0:0:1");
    }

    @Test
    void followsCnameAnswers() throws IOException {
        server.cname("alias.test", "real.test", "10.1.1.3");

        List<InetAddress> addresses = client.query(server.address(), "alias.test", RecordType.A);

        assertThat(addresses).extracting(InetAddress::getHostAddress).containsExactly("10.1.1.3");
        assertThat(addresses.getFirst().getHostName()).isEqualTo("alias.test");
    }

    @Test
    void emptyForMissingNamesAndRecords() throws IOException {
        server.error("missing.test", FakeDnsServer.NXDOMAIN);

        assertThat(query("missing.test", RecordType.A)).isEmpty();
        assertThat(query("nodata.test", RecordType.A)).isEmpty();
    }

    @Test
    void failsOnServerError() {
        server.error("broken.test", FakeDnsServer.SERVFAIL);

        assertThatThrownBy(() -> query("broken.test", RecordType.A)).isInstanceOf(IOException.class);
    }

    @Test
    void failsQuicklyWhenTheServerIsSilent() throws IOException {
        try (DatagramSocket silent = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            DnsServer address = new DnsServer(silent.getLocalAddress(), silent.getLocalPort());
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.query(address, "app.test", RecordType.A)).isInstanceOf(IOException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }

    private List<String> query(String host, RecordType type) throws IOException {
        return client.query(server.address(), host, type).stream().map(InetAddress::getHostAddress).toList();
    }
}
