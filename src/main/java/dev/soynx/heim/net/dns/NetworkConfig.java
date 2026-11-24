package dev.soynx.heim.net.dns;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Network overrides from the environment. Read before Spring starts, because the JDK picks its
 * address resolver at the first lookup.
 */
public record NetworkConfig(List<DnsServer> dnsServers, List<String> searchDomains, HostOverrides hosts) {

    static final String DNS_SERVERS = "HEIM_DNS_SERVERS";
    static final String DNS_SEARCH = "HEIM_DNS_SEARCH";
    static final String HOSTS = "HEIM_HOSTS";

    public static final NetworkConfig NONE = new NetworkConfig(List.of(), List.of(), new HostOverrides(Map.of()));

    public NetworkConfig {
        dnsServers = List.copyOf(dnsServers);
        searchDomains = List.copyOf(searchDomains);
    }

    /** @throws InvalidNetworkConfigException if a variable is malformed */
    public static NetworkConfig fromEnv(Map<String, String> env) {
        List<DnsServer> servers = entries(env, DNS_SERVERS).stream().map(NetworkConfig::parseServer).distinct().toList();
        List<String> search = entries(env, DNS_SEARCH).stream().map(NetworkConfig::parseSearchDomain).distinct().toList();
        if (servers.isEmpty() && !search.isEmpty()) {
            throw new InvalidNetworkConfigException(DNS_SEARCH, "requires " + DNS_SERVERS + " to be set");
        }
        return new NetworkConfig(servers, search, parseHosts(entries(env, HOSTS)));
    }

    public boolean isEmpty() {
        return dnsServers.isEmpty() && hosts.isEmpty();
    }

    private static DnsServer parseServer(String value) {
        return DnsServer.parse(value).orElseThrow(() -> new InvalidNetworkConfigException(DNS_SERVERS,
                "'" + value + "' is not an IP address with an optional port"));
    }

    private static String parseSearchDomain(String value) {
        return requireHostName(DNS_SEARCH, value);
    }

    private static HostOverrides parseHosts(List<String> entries) {
        Map<String, HostTarget> hosts = new HashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator < 0) {
                throw new InvalidNetworkConfigException(HOSTS, "'" + entry + "' is not in the form host=target");
            }
            String key = parseHostKey(entry.substring(0, separator).strip());
            HostTarget target = parseTarget(entry.substring(separator + 1).strip());
            if (hosts.putIfAbsent(key, target) != null) {
                throw new InvalidNetworkConfigException(HOSTS, "'" + key + "' is listed more than once");
            }
        }
        return new HostOverrides(hosts);
    }

    private static String parseHostKey(String value) {
        boolean wildcard = value.startsWith(HostOverrides.WILDCARD);
        String host = requireHostName(HOSTS, wildcard ? value.substring(HostOverrides.WILDCARD.length()) : value);
        return wildcard ? HostOverrides.WILDCARD + host : host;
    }

    private static HostTarget parseTarget(String value) {
        return HostNames.parseIp(value).<HostTarget>map(HostTarget.Ip::new)
                .orElseGet(() -> new HostTarget.Alias(requireHostName(HOSTS, value)));
    }

    private static String requireHostName(String variable, String value) {
        String host = HostNames.normalize(value);
        if (!HostNames.isValid(host)) {
            throw new InvalidNetworkConfigException(variable, "'" + value + "' is not a valid host name");
        }
        return host;
    }

    private static List<String> entries(Map<String, String> env, String variable) {
        return Arrays.stream(env.getOrDefault(variable, "").split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}
