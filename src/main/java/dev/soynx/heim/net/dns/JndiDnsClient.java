package dev.soynx.heim.net.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/** {@link DnsClient} on the JDK's own DNS provider, which handles CNAME answers and TCP fallback. */
final class JndiDnsClient implements DnsClient {

    private static final String TIMEOUT_MS = "1000";
    private static final String ATTEMPTS = "1";

    @Override
    public List<InetAddress> query(DnsServer server, String host, RecordType type) throws IOException {
        DirContext context = null;
        try {
            context = new InitialDirContext(environment(server));
            Attribute records = context.getAttributes(host, new String[] {type.name()}).get(type.name());
            return records == null ? List.of() : addresses(host, records);
        } catch (NameNotFoundException | InvalidNameException e) {
            return List.of();
        } catch (NamingException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            closeQuietly(context);
        }
    }

    private static Hashtable<String, String> environment(DnsServer server) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL, "dns://" + server);
        env.put("com.sun.jndi.dns.timeout.initial", TIMEOUT_MS);
        env.put("com.sun.jndi.dns.timeout.retries", ATTEMPTS);
        return env;
    }

    private static List<InetAddress> addresses(String host, Attribute records) throws NamingException, IOException {
        List<InetAddress> addresses = new ArrayList<>();
        NamingEnumeration<?> values = records.getAll();
        while (values.hasMore()) {
            String ip = String.valueOf(values.next());
            InetAddress address = HostNames.parseIp(ip)
                    .orElseThrow(() -> new IOException("Malformed address record: " + ip));
            addresses.add(InetAddress.getByAddress(host, address.getAddress()));
        }
        return addresses;
    }

    private static void closeQuietly(DirContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (NamingException ignored) {
            // The answer, or the failure, is already known.
        }
    }
}
