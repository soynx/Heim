package dev.soynx.heim.net.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class HeimAddressResolverTest {

    private static final LookupPolicy ANY = LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);
    private static final String SYSTEM_IP = "9.9.9.9";

    private final Map<String, List<String>> answers = new HashMap<>();
    private final List<String> broken = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private final List<String> systemLookups = new ArrayList<>();
    private final Map<String, String> env = new HashMap<>();

    @Test
    void customDnsBeatsSystem() throws Exception {
        servers("10.0.0.1");
        answers.put("app.test", List.of("10.1.1.1"));

        assertThat(lookup("App.Test.", ANY)).containsExactly("10.1.1.1");
        assertThat(systemLookups).isEmpty();
    }

    @Test
    void overrideBeatsDnsAndKeepsTheRequestedName() throws Exception {
        servers("10.0.0.1");
        env.put("HEIM_HOSTS", "*.test=10.2.2.2");

        InetAddress address = resolver().lookupByName("app.test", ANY).findFirst().orElseThrow();

        assertThat(address.getHostAddress()).isEqualTo("10.2.2.2");
        assertThat(address.getHostName()).isEqualTo("app.test");
        assertThat(queries).isEmpty();
    }

    @Test
    void aliasIsResolvedByDnsWithoutASecondOverridePass() throws Exception {
        servers("10.0.0.1");
        env.put("HEIM_HOSTS", "a.test=b.test,b.test=a.test");
        answers.put("b.test", List.of("10.1.1.2"));

        assertThat(lookup("a.test", ANY)).containsExactly("10.1.1.2");
    }

    @Test
    void aliasUsesTheSystemWithoutDnsServers() throws Exception {
        env.put("HEIM_HOSTS", "a.test=container");

        assertThat(lookup("a.test", ANY)).containsExactly(SYSTEM_IP);
        assertThat(systemLookups).containsExactly("container");
    }

    @Test
    void nextServerIsUsedOnlyOnFailure() throws Exception {
        servers("10.0.0.1,10.0.0.2");
        broken.add("10.0.0.1");
        answers.put("app.test", List.of("10.1.1.1"));

        assertThat(lookup("app.test", ANY)).containsExactly("10.1.1.1");
        assertThat(queries).containsExactly("10.0.0.1 app.test A", "10.0.0.2 app.test A", "10.0.0.2 app.test AAAA");
    }

    @Test
    void negativeAnswerIsFinalForDnsAndFallsBackToSystem() throws Exception {
        servers("10.0.0.1,10.0.0.2");

        assertThat(lookup("host.docker.internal", ANY)).containsExactly(SYSTEM_IP);
        assertThat(queries).allMatch(query -> query.startsWith("10.0.0.1 "));
    }

    @Test
    void unreachableDnsFailsDottedNamesButNotContainerNames() throws Exception {
        servers("10.0.0.1");
        broken.add("10.0.0.1");

        assertThatThrownBy(() -> lookup("app.test", ANY)).isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("no DNS server reachable");
        assertThat(systemLookups).isEmpty();
        assertThat(lookup("container", ANY)).containsExactly(SYSTEM_IP);
    }

    @Test
    void searchDomainsApplyToSingleLabelsInOrder() throws Exception {
        servers("10.0.0.1");
        env.put("HEIM_DNS_SEARCH", "one.test,two.test");
        answers.put("nas.two.test", List.of("10.1.1.3"));

        assertThat(lookup("nas", ANY)).containsExactly("10.1.1.3");
        lookup("nas.other", ANY);
        assertThat(queries).noneMatch(query -> query.contains("nas.other.one.test"));
    }

    @Test
    void localhostAlwaysUsesTheSystem() throws Exception {
        servers("10.0.0.1");
        env.put("HEIM_HOSTS", "localhost=10.2.2.2");

        lookup("localhost", ANY);

        assertThat(systemLookups).containsExactly("localhost");
        assertThat(queries).isEmpty();
    }

    @Test
    void respectsFamilyAndOrder() throws Exception {
        servers("10.0.0.1");
        env.put("HEIM_HOSTS", "v4.test=10.2.2.2");
        answers.put("app.test", List.of("10.1.1.1", "fd00:0:0:0:0:0:0:1"));
        LookupPolicy v4 = LookupPolicy.of(LookupPolicy.IPV4);
        LookupPolicy v6 = LookupPolicy.of(LookupPolicy.IPV6);

        assertThat(lookup("app.test", v4)).containsExactly("10.1.1.1");
        assertThat(lookup("app.test", v6)).containsExactly("fd00:0:0:0:0:0:0:1");
        assertThat(lookup("app.test", LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV6_FIRST)))
                .containsExactly("fd00:0:0:0:0:0:0:1", "10.1.1.1");
        assertThatThrownBy(() -> lookup("v4.test", v6)).isInstanceOf(UnknownHostException.class);
    }

    private void servers(String value) {
        env.put("HEIM_DNS_SERVERS", value);
    }

    private List<String> lookup(String host, LookupPolicy policy) throws UnknownHostException {
        return resolver().lookupByName(host, policy).map(InetAddress::getHostAddress).toList();
    }

    private HeimAddressResolver resolver() {
        return new HeimAddressResolver(NetworkConfig.fromEnv(env), this::query, new FakeSystem());
    }

    private List<InetAddress> query(DnsServer server, String host, RecordType type) throws IOException {
        String ip = server.address().getHostAddress();
        queries.add(ip + " " + host + " " + type);
        if (broken.contains(ip)) {
            throw new IOException("timeout");
        }
        List<InetAddress> result = new ArrayList<>();
        for (String answer : answers.getOrDefault(host, List.of())) {
            if (answer.contains(":") == (type == RecordType.AAAA)) {
                result.add(InetAddress.getByName(answer));
            }
        }
        return result;
    }

    private final class FakeSystem implements InetAddressResolver {

        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
            systemLookups.add(host);
            return Stream.of(InetAddress.getByName(SYSTEM_IP));
        }

        @Override
        public String lookupByAddress(byte[] address) {
            return "reverse";
        }
    }
}
