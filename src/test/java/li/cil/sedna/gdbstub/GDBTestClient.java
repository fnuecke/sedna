package li.cil.sedna.gdbstub;

import li.cil.sedna.api.debug.CPUDebugInterface;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

final class GDBTestClient implements AutoCloseable {
    private static final Duration MUST_NOT_BLOCK = Duration.ofSeconds(5);

    private final ServerSocketChannel channel;
    private final GDBStub stub;
    private final Socket socket;

    static GDBTestClient connect(final CPUDebugInterface debug) throws IOException {
        return new GDBTestClient(debug);
    }

    private GDBTestClient(final CPUDebugInterface debug) throws IOException {
        channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        stub = new GDBStub(channel, debug);

        socket = new Socket(InetAddress.getLoopbackAddress(), channel.socket().getLocalPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        poll();
        readPacket(); // The attach stop reply.
    }

    GDBStub stub() {
        return stub;
    }

    void poll() {
        assertTimeoutPreemptively(MUST_NOT_BLOCK, stub::poll);
    }

    void send(final String contents) throws IOException {
        int checksum = 0;
        for (int i = 0; i < contents.length(); i++) {
            checksum += contents.charAt(i);
        }
        final String packet = String.format("$%s#%02x", contents, checksum & 0xFF);
        socket.getOutputStream().write(packet.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    String request(final String contents) throws IOException {
        send(contents);
        poll();
        return readPacket();
    }

    String readPacket() throws IOException {
        final InputStream in = socket.getInputStream();
        int c;
        do {
            c = in.read();
            if (c < 0) {
                throw new IOException("connection closed while waiting for a packet");
            }
        } while (c != '$');

        final StringBuilder sb = new StringBuilder();
        while ((c = in.read()) != '#') {
            if (c < 0) {
                throw new IOException("connection closed mid-packet");
            }
            sb.append((char) c);
        }
        in.read();
        in.read(); // checksum
        return sb.toString();
    }

    static String unescape(final String escaped) {
        final StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            final char c = escaped.charAt(i);
            if (c == '}') {
                sb.append((char) (escaped.charAt(++i) ^ 0x20));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
        channel.close();
    }
}
