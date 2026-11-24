package dev.soynx.heim.net.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

/**
 * Plugs {@link HeimAddressResolver} into the JDK (via {@code META-INF/services}), so every outbound
 * request uses it while URLs, TLS names and certificate checks stay untouched.
 */
public final class HeimAddressResolverProvider extends InetAddressResolverProvider {

    private static volatile NetworkConfig config = NetworkConfig.NONE;
    private static volatile boolean installed;

    /**
     * Makes the JDK use {@code networkConfig}. The JDK asks for its resolver only once, so this must
     * run before the first lookup of the process.
     */
    public static void install(NetworkConfig networkConfig) {
        if (networkConfig.isEmpty()) {
            return;
        }
        config = networkConfig;
        try {
            InetAddress.getByName("localhost");
        } catch (UnknownHostException e) {
            // Irrelevant: the lookup only makes the JDK load its resolver.
        }
        if (!installed) {
            throw new IllegalStateException("The network settings could not be applied: "
                    + "the JVM is already using another address resolver");
        }
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        NetworkConfig current = config;
        if (current.isEmpty()) {
            return configuration.builtinResolver();
        }
        installed = true;
        return new HeimAddressResolver(current, new JndiDnsClient(), configuration.builtinResolver());
    }

    @Override
    public String name() {
        return "heim";
    }

    static NetworkConfig activeConfig() {
        return config;
    }
}
