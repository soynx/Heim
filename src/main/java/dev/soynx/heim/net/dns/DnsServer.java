package dev.soynx.heim.net.dns;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Address of a DNS server. */
record DnsServer(InetAddress address, int port) {

    private static final int DEFAULT_PORT = 53;
    private static final int MAX_PORT = 65535;
    private static final Pattern BRACKETED = Pattern.compile("\\[([^]]+)](?::(\\d{1,5}))?");
    private static final Pattern WITH_PORT = Pattern.compile("([^:]+):(\\d{1,5})");

    /** Accepts {@code ip}, {@code ipv4:port}, {@code [ipv6]} and {@code [ipv6]:port}. */
    static Optional<DnsServer> parse(String value) {
        Matcher bracketed = BRACKETED.matcher(value);
        if (bracketed.matches()) {
            return create(bracketed.group(1), bracketed.group(2)).filter(s -> s.address instanceof Inet6Address);
        }
        Matcher withPort = WITH_PORT.matcher(value);
        if (withPort.matches()) {
            return create(withPort.group(1), withPort.group(2));
        }
        return create(value, null);
    }

    private static Optional<DnsServer> create(String ip, String portText) {
        int port = portText == null ? DEFAULT_PORT : Integer.parseInt(portText);
        if (port < 1 || port > MAX_PORT) {
            return Optional.empty();
        }
        return HostNames.parseIp(ip).map(address -> new DnsServer(address, port));
    }

    @Override
    public String toString() {
        String host = address.getHostAddress();
        return (address instanceof Inet6Address ? "[" + host + "]" : host) + ":" + port;
    }
}
