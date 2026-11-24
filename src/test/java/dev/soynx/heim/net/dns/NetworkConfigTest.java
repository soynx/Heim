package dev.soynx.heim.net.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NetworkConfigTest {

    @Test
    void emptyWhenUnsetOrBlank() {
        assertThat(NetworkConfig.fromEnv(Map.of()).isEmpty()).isTrue();
        assertThat(NetworkConfig.fromEnv(Map.of("HEIM_DNS_SERVERS", " ", "HEIM_DNS_SEARCH", "", "HEIM_HOSTS", " , "))
                .isEmpty()).isTrue();
    }

    @Test
    void parsesServersInOrder() {
        NetworkConfig config = NetworkConfig.fromEnv(
                Map.of("HEIM_DNS_SERVERS", "10.0.0.2, 10.0.0.3:5353,fd00::1,[fd00::2]:54,[fd00::3],10.0.0.2"));

        assertThat(config.dnsServers()).extracting(DnsServer::toString).containsExactly("10.0.0.2:53",
                "10.0.0.3:5353", "[fd00:0:0:0:0:0:0:1]:53", "[fd00:0:0:0:0:0:0:2]:54", "[fd00:0:0:0:0:0:0:3]:53");
    }

    @Test
    void parsesSearchDomainsAndHosts() {
        NetworkConfig config = NetworkConfig.fromEnv(Map.of("HEIM_DNS_SERVERS", "10.0.0.2",
                "HEIM_DNS_SEARCH", "Lan.Test., corp.test",
                "HEIM_HOSTS", "NAS = 10.0.0.5, *.lab.test=fd00::5,old.lan=New.lan."));

        assertThat(config.searchDomains()).containsExactly("lan.test", "corp.test");
        assertThat(config.hosts().find("nas")).hasToString("Optional[10.0.0.5]");
        assertThat(config.hosts().find("a.lab.test")).hasToString("Optional[fd00:0:0:0:0:0:0:5]");
        assertThat(config.hosts().find("old.lan")).contains(new HostTarget.Alias("new.lan"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dns.lan", "10.0.0", "256.0.0.1", "10.0.0.1:0", "10.0.0.1:65536", "10.0.0.1:",
            "[10.0.0.1]:53", "[fd00::1", "fd00:::1", "10.0.0.1 10.0.0.2"})
    void rejectsInvalidServers(String value) {
        assertRejected(Map.of("HEIM_DNS_SERVERS", value), "HEIM_DNS_SERVERS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nas", "=10.0.0.1", "nas=", "nas=10.0.0.256", "nas=1:2", "na s=10.0.0.1", "*=10.0.0.1",
            "*.=10.0.0.1", "a.*.b=10.0.0.1", "nas=*.lan", "http://nas=10.0.0.1", "nas=10.0.0.1,NAS=10.0.0.2"})
    void rejectsInvalidHosts(String value) {
        assertRejected(Map.of("HEIM_HOSTS", value), "HEIM_HOSTS");
    }

    @Test
    void rejectsInvalidSearchDomains() {
        assertRejected(Map.of("HEIM_DNS_SERVERS", "10.0.0.2", "HEIM_DNS_SEARCH", "bad domain"), "HEIM_DNS_SEARCH");
        assertRejected(Map.of("HEIM_DNS_SEARCH", "lan"), "HEIM_DNS_SEARCH");
    }

    private static void assertRejected(Map<String, String> env, String variable) {
        assertThatThrownBy(() -> NetworkConfig.fromEnv(env))
                .isInstanceOf(InvalidNetworkConfigException.class)
                .hasMessageStartingWith(variable + ":");
    }
}
