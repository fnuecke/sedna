package li.cil.sedna.gdbstub;

import li.cil.sedna.api.debug.CPUDebugInterface;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import li.cil.sedna.riscv.R5;
import li.cil.sedna.riscv.R5CPU;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public final class GDBStubCPUTests {
    private static final Duration MUST_NOT_BLOCK = Duration.ofSeconds(5);

    private static final long RAM_START = 0x80000000L;
    private static final int RAM_SIZE = 4 * 1024;

    private static final int REG_PC = 32;

    private CPUDebugInterface debug;
    private ServerSocketChannel channel;
    private GDBStub stub;
    private Socket client;

    @BeforeEach
    public void setUp() throws IOException {
        final MemoryMap memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(RAM_START, Memory.create(RAM_SIZE));
        final R5CPU cpu = R5CPU.create(memoryMap);
        cpu.reset(true, RAM_START);
        cpu.setXLEN(R5.XLEN_64);
        debug = cpu.getDebugInterface();

        channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        stub = new GDBStub(channel, debug);

        client = new Socket(InetAddress.getLoopbackAddress(), channel.socket().getLocalPort());
        client.setTcpNoDelay(true);
        poll();
        readPacket(); // the attach stop reply
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        channel.close();
    }

    @Test
    public void targetDescriptionIsSent() throws Exception {
        final String expected = new String(requireDescription(), StandardCharsets.US_ASCII);

        final StringBuilder received = new StringBuilder();
        boolean last = false;
        for (int i = 0; i < 64 && !last; i++) { // Bounded, so a broken 'l' cannot hang the test.
            send("qXfer:features:read:target.xml:%x,ffb".formatted(received.length()));
            poll();
            final String reply = readPacket();
            last = reply.charAt(0) == 'l';
            received.append(unescape(reply.substring(1)));
        }

        assertTrue(last, "the transfer must terminate with a final 'l' chunk");
        assertEquals(expected, received.toString(),
                "the description the CPU serves must arrive byte for byte");
    }

    @Test
    public void theDescriptionIsLongEnoughToNeedMoreThanOneChunk() throws Exception {
        // Otherwise the test above would not actually be testing chunking.
        send("qXfer:features:read:target.xml:0,ffb");
        poll();

        assertEquals('m', readPacket().charAt(0));
    }

    @Test
    public void aCsrCanBeReadThroughTheStub() throws Exception {
        // misa is 0x301, and reports the ISA the CPU was built with, so it is never zero.
        send("p1301");
        poll();

        final String reply = readPacket();
        assertEquals(16, reply.length(), "a CSR is eight bytes of hex");
        assertNotEquals(0, Long.reverseBytes(Long.parseUnsignedLong(reply, 16)), "misa must be set");
    }

    @Test
    public void theProgramCounterAgreesWithTheCpu() throws Exception {
        send("p%x".formatted(REG_PC));
        poll();

        assertEquals(RAM_START, Long.reverseBytes(Long.parseUnsignedLong(readPacket(), 16)));
    }

    @Test
    public void writingARegisterThroughTheStubReachesTheCpu() throws Exception {
        send("P5=efbeadde00000000");
        poll();

        assertEquals("OK", readPacket());
        assertEquals(0xDEADBEEFL, debug.getGeneralRegisters()[5]);
    }

    // ------------------------------------------------------------- //

    /**
     * The inverse of {@link GDBBinaryOutputStream}: {@code '}'} escapes the byte after it, which
     * has been xor-ed with 0x20.
     */
    private static String unescape(final String escaped) {
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

    private byte[] requireDescription() {
        final byte[] description = debug.getTargetDescription();
        assertNotNull(description);
        return description;
    }

    private void poll() {
        assertTimeoutPreemptively(MUST_NOT_BLOCK, () -> stub.poll());
    }

    private void send(final String contents) throws IOException {
        int checksum = 0;
        for (int i = 0; i < contents.length(); i++) {
            checksum += contents.charAt(i);
        }
        final String packet = String.format("$%s#%02x", contents, checksum & 0xFF);
        client.getOutputStream().write(packet.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    private String readPacket() throws IOException {
        client.setSoTimeout(5000);
        final InputStream in = client.getInputStream();
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
}
