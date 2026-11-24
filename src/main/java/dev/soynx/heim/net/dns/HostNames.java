package dev.soynx.heim.net.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parsing of host names and IP literals; never performs a lookup. */
final class HostNames {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6 = Pattern.compile("(?=.*:)[0-9a-fA-F:.]+");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?");
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    private static final int MAX_LENGTH = 253;

    private HostNames() {
    }

    /** Lower case, without the trailing dot of a fully qualified name. */
    static String normalize(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    /** Expects a normalized name. A numeric last label is rejected: it can only be a mistyped IP address. */
    static boolean isValid(String host) {
        if (host.isEmpty() || host.length() > MAX_LENGTH) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return !NUMERIC.matcher(labels[labels.length - 1]).matches();
    }

    static boolean isSingleLabel(String host) {
        return host.indexOf('.') < 0;
    }

    static Optional<InetAddress> parseIp(String value) {
        if (!IPV4.matcher(value).matches() && !IPV6.matcher(value).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
