package dev.soynx.heim.net.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/** Sends a single query to a single DNS server. */
interface DnsClient {

    /**
     * @return the addresses; empty when the server answered that there are none
     * @throws IOException if the server gave no usable answer (timeout, SERVFAIL, refused)
     */
    List<InetAddress> query(DnsServer server, String host, RecordType type) throws IOException;
}
