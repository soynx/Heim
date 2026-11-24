package dev.soynx.heim.net.dns;

import java.net.InetAddress;

/** What a host override points to. */
sealed interface HostTarget {

    record Ip(InetAddress address) implements HostTarget {

        @Override
        public String toString() {
            return address.getHostAddress();
        }
    }

    record Alias(String host) implements HostTarget {

        @Override
        public String toString() {
            return host;
        }
    }
}
