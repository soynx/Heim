package dev.soynx.heim.net.dns;

import static java.net.spi.InetAddressResolver.LookupPolicy.IPV4;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV4_FIRST;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV6;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV6_FIRST;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves host overrides first, then the configured DNS servers, then the system. The system step
 * keeps names working that only the container runtime knows, such as Docker service names.
 */
final class HeimAddressResolver implements InetAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(HeimAddressResolver.class);

    private final NetworkConfig config;
    private final DnsClient dns;
    private final InetAddressResolver system;
    private final Set<DnsServer> failing = ConcurrentHashMap.newKeySet();

    HeimAddressResolver(NetworkConfig config, DnsClient dns, InetAddressResolver system) {
        this.config = config;
        this.dns = dns;
        this.system = system;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        String name = HostNames.normalize(host);
        if (isLocalhost(name)) {
            return system.lookupByName(host, policy);
        }
        Optional<HostTarget> override = config.hosts().find(name);
        if (override.isEmpty()) {
            return resolve(host, name, policy);
        }
        return switch (override.get()) {
            case HostTarget.Ip ip -> fixedAddress(host, ip.address(), policy);
            // Straight to DNS: aliases are not looked up in the overrides again, so they cannot loop.
            case HostTarget.Alias alias -> resolve(alias.host(), alias.host(), policy);
        };
    }

    @Override
    public String lookupByAddress(byte[] address) throws UnknownHostException {
        return system.lookupByAddress(address);
    }

    private Stream<InetAddress> resolve(String host, String name, LookupPolicy policy) throws UnknownHostException {
        if (config.dnsServers().isEmpty() || isLocalhost(name)) {
            return system.lookupByName(host, policy);
        }
        try {
            for (String candidate : candidates(name)) {
                List<InetAddress> addresses = queryServers(candidate, policy);
                if (!addresses.isEmpty()) {
                    return addresses.stream();
                }
            }
        } catch (IOException e) {
            // Asking the system for a dotted name could return the public side of a split-horizon domain.
            if (!HostNames.isSingleLabel(name)) {
                UnknownHostException unknown = new UnknownHostException(host + ": no DNS server reachable");
                unknown.initCause(e);
                throw unknown;
            }
        }
        return system.lookupByName(host, policy);
    }

    private List<String> candidates(String name) {
        List<String> candidates = new ArrayList<>();
        if (HostNames.isSingleLabel(name)) {
            config.searchDomains().forEach(domain -> candidates.add(name + "." + domain));
        }
        candidates.add(name);
        return candidates;
    }

    /** The first server that answers decides, even when its answer is that the name does not exist. */
    private List<InetAddress> queryServers(String name, LookupPolicy policy) throws IOException {
        IOException failure = new IOException("No DNS server answered for " + name);
        for (DnsServer server : config.dnsServers()) {
            try {
                List<InetAddress> addresses = queryServer(server, name, policy);
                if (failing.remove(server)) {
                    log.info("DNS server {} is answering again", server);
                }
                return addresses;
            } catch (IOException e) {
                if (failing.add(server)) {
                    log.warn("DNS server {} failed for {}: {}", server, name, e.getMessage());
                }
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    private List<InetAddress> queryServer(DnsServer server, String name, LookupPolicy policy) throws IOException {
        List<InetAddress> addresses = new ArrayList<>();
        IOException failure = null;
        for (RecordType type : recordTypes(policy)) {
            try {
                addresses.addAll(dns.query(server, name, type));
            } catch (IOException e) {
                failure = e;
                // No answer for the first type means the server is down; asking again only doubles the wait.
                break;
            }
        }
        // A server that drops AAAA queries is still usable for its IPv4 answers.
        if (failure != null && addresses.isEmpty()) {
            throw failure;
        }
        return addresses.stream().sorted(preferredFamily(policy)).toList();
    }

    private static List<RecordType> recordTypes(LookupPolicy policy) {
        List<RecordType> types = new ArrayList<>();
        if (has(policy, IPV4)) {
            types.add(RecordType.A);
        }
        if (has(policy, IPV6)) {
            types.add(RecordType.AAAA);
        }
        return types;
    }

    private static Comparator<InetAddress> preferredFamily(LookupPolicy policy) {
        if (has(policy, IPV6_FIRST)) {
            return Comparator.comparing(address -> address instanceof Inet4Address);
        }
        if (has(policy, IPV4_FIRST)) {
            return Comparator.comparing(address -> !(address instanceof Inet4Address));
        }
        return (a, b) -> 0;
    }

    private static Stream<InetAddress> fixedAddress(String host, InetAddress address, LookupPolicy policy)
            throws UnknownHostException {
        if (!has(policy, address instanceof Inet4Address ? IPV4 : IPV6)) {
            throw new UnknownHostException(host);
        }
        return Stream.of(InetAddress.getByAddress(host, address.getAddress()));
    }

    private static boolean has(LookupPolicy policy, int flag) {
        return (policy.characteristics() & flag) != 0;
    }

    private static boolean isLocalhost(String name) {
        return name.equals("localhost") || name.endsWith(".localhost");
    }
}
