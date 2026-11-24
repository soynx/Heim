package dev.soynx.heim.net.dns;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A UDP DNS server on the loopback interface. */
final class FakeDnsServer implements AutoCloseable {

    static final int NXDOMAIN = 3;
    static final int SERVFAIL = 2;
    private static final int TYPE_CNAME = 5;

    private record Answer(String name, int type, byte[] data) {
    }

    private final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    private final Map<String, List<Answer>> records = new ConcurrentHashMap<>();
    private final Map<String, Integer> errors = new ConcurrentHashMap<>();

    FakeDnsServer() throws IOException {
        Thread.ofVirtual().start(this::serve);
    }

    DnsServer address() {
        return new DnsServer(socket.getLocalAddress(), socket.getLocalPort());
    }

    void address(String name, String ip) throws IOException {
        byte[] data = InetAddress.getByName(ip).getAddress();
        add(name, new Answer(name, data.length == 4 ? 1 : 28, data));
    }

    /** Answers queries for {@code alias} with the CNAME followed by the target's address. */
    void cname(String alias, String target, String ip) throws IOException {
        add(alias, new Answer(alias, TYPE_CNAME, encode(target)));
        add(alias, new Answer(target, 1, InetAddress.getByName(ip).getAddress()));
    }

    void error(String name, int code) {
        errors.put(name, code);
    }

    private void add(String name, Answer answer) {
        records.computeIfAbsent(name, key -> new ArrayList<>()).add(answer);
    }

    private void serve() {
        byte[] buffer = new byte[512];
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] reply = reply(ByteBuffer.wrap(buffer, 0, packet.getLength()));
                socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
            } catch (IOException e) {
                return;
            }
        }
    }

    private byte[] reply(ByteBuffer query) throws IOException {
        short id = query.getShort();
        query.position(12);
        StringBuilder name = new StringBuilder();
        for (int length = query.get(); length > 0; length = query.get()) {
            byte[] label = new byte[length];
            query.get(label);
            name.append(name.isEmpty() ? "" : ".").append(new String(label, StandardCharsets.US_ASCII));
        }
        int type = query.getShort();
        List<Answer> answers = records.getOrDefault(name.toString(), List.of()).stream()
                .filter(answer -> answer.type() == type || answer.type() == TYPE_CNAME).toList();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(id);
        out.writeShort(0x8180 | errors.getOrDefault(name.toString(), 0));
        out.writeShort(1);
        out.writeShort(answers.size());
        out.writeInt(0);
        out.write(encode(name.toString()));
        out.writeShort(type);
        out.writeShort(1);
        for (Answer answer : answers) {
            out.write(encode(answer.name()));
            out.writeShort(answer.type());
            out.writeShort(1);
            out.writeInt(60);
            out.writeShort(answer.data().length);
            out.write(answer.data());
        }
        return bytes.toByteArray();
    }

    private static byte[] encode(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            out.write(label.length());
            out.write(label.getBytes(StandardCharsets.US_ASCII));
        }
        out.write(0);
        return out.toByteArray();
    }

    @Override
    public void close() {
        socket.close();
    }
}
