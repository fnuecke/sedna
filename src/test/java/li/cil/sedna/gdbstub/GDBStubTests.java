package li.cil.sedna.gdbstub;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import li.cil.sedna.api.debug.CPUDebugInterface;
import li.cil.sedna.api.memory.MemoryAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class GDBStubTests {
    private static final Duration MUST_NOT_BLOCK = Duration.ofSeconds(5);

    private FakeCPU cpu;
    private ServerSocketChannel channel;
    private GDBStub stub;
    private int port;
    private Socket client;

    @BeforeEach
    public void setUp() throws IOException {
        cpu = new FakeCPU();
        channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        port = channel.socket().getLocalPort();
        stub = new GDBStub(channel, cpu);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        channel.close();
    }

    @Test
    public void machineRunsWhenNoDebuggerIsAttached() {
        poll();

        assertFalse(stub.isHalted(), "with no debugger attached the machine must be free to run");
    }

    @Test
    public void attachingHaltsTheMachineAndReportsAStop() throws Exception {
        connect();

        poll();

        assertTrue(stub.isHalted(), "a debugger takes charge the moment it attaches");
        assertEquals("S05", readPacket(), "the debugger must be told the machine is stopped");
    }

    @Test
    public void continueResumesTheMachine() throws Exception {
        connect();
        poll();
        readPacket(); // the attach stop reply

        send("c");
        poll();

        assertFalse(stub.isHalted(), "'c' must let the machine run again");
    }

    @Test
    public void singleStepAdvancesTheCpuAndStopsAgain() throws Exception {
        connect();
        poll();
        readPacket();

        send("s");
        poll();

        assertEquals(1, cpu.steps, "'s' must advance the CPU exactly once");
        assertTrue(stub.isHalted(), "the machine stays stopped after a single step");
        assertEquals("S05", readPacket());
    }

    @Test
    public void detachingDoesNotFreezeTheStub() throws Exception {
        stub.waitForAttach();
        assertTrue(stub.isHalted());

        connect();
        poll();
        readPacket();

        send("D");
        poll();
        client.close();
        client = null;

        // Whatever happened above, polling must still return promptly and the machine must run.
        poll();
        poll();
        assertFalse(stub.isHalted(), "after a detach the machine must resume, not hang");
    }

    @Test
    public void waitForAttachHoldsTheMachineUntilResumed() throws Exception {
        stub.waitForAttach();

        poll();
        assertTrue(stub.isHalted(), "the machine waits for a debugger");

        connect();
        poll();
        readPacket();
        send("c");
        poll();

        assertFalse(stub.isHalted(), "once resumed the machine runs");
    }

    @Test
    public void breakpointsAreRemovedWhenTheDebuggerGoesAway() throws Exception {
        connect();
        poll();
        readPacket();

        send("Z0,1000,4");
        poll();
        assertEquals("OK", readPacket());
        assertTrue(cpu.breakpoints.contains(0x1000L), "the breakpoint must reach the CPU");

        send("D");
        poll();

        assertTrue(cpu.breakpoints.isEmpty(), "breakpoints must not outlive the debugger");
        assertFalse(stub.isHalted(), "the machine must resume once the debugger is gone");
    }

    @Test
    public void breakpointHitHaltsAndReportsOnTheNextPoll() throws Exception {
        connect();
        poll();
        readPacket();
        send("c");
        poll();
        assertFalse(stub.isHalted());

        cpu.hitBreakpoint(0x1234);

        assertTrue(stub.isHalted(), "a breakpoint hit halts the machine immediately");
        poll();
        assertEquals("S05", readPacket(), "and is reported on the next poll");
    }

    @Test
    public void interruptRequestStopsTheMachine() throws Exception {
        connect();
        poll();
        readPacket();
        send("c");
        poll();
        assertFalse(stub.isHalted());

        client.getOutputStream().write(0x03); // Ctrl+C
        client.getOutputStream().flush();
        poll();

        assertTrue(stub.isHalted(), "Ctrl+C must stop the machine");
        assertEquals("S02", readPacket());
    }

    @Test
    public void packetsSplitAcrossPollsAreReassembled() throws Exception {
        connect();
        poll();
        readPacket();

        final String packet = frame("qSupported:multiprocess+");
        final OutputStream out = client.getOutputStream();
        for (int i = 0; i < packet.length(); i++) {
            out.write(packet.charAt(i));
            out.flush();
            poll();
        }

        assertEquals("PacketSize=2000", readPacket(), "a dribbled packet must still be understood");
    }

    @Test
    public void badChecksumIsRejectedAndTheNextPacketStillWorks() throws Exception {
        connect();
        poll();
        readPacket();

        final OutputStream out = client.getOutputStream();
        out.write("$qSupported:x#00".getBytes(StandardCharsets.US_ASCII)); // deliberately wrong
        out.flush();
        poll();

        assertEquals('-', readAck(), "a corrupt packet must be negatively acknowledged");

        send("qAttached");
        poll();
        assertEquals("1", readPacket(), "the stream must resynchronise after a bad packet");
    }

    @Test
    public void readingRegistersReportsThePc() throws Exception {
        cpu.pc = 0xDEADBEEFL;
        connect();
        poll();
        readPacket();

        send("g");
        poll();

        final String reply = readPacket();
        assertEquals((32 + 1) * 16, reply.length(), "32 x-registers plus the pc, 8 bytes each in hex");
        final String pc = reply.substring(reply.length() - 16);
        assertEquals(0xDEADBEEFL, Long.reverseBytes(Long.parseUnsignedLong(pc, 16)));
    }

    // ------------------------------------------------------------- //

    private void poll() {
        assertTimeoutPreemptively(MUST_NOT_BLOCK, () -> stub.poll(),
            "poll must never block the thread stepping the machine");
    }

    private void connect() throws IOException {
        client = new Socket(InetAddress.getLoopbackAddress(), port);
        client.setTcpNoDelay(true);
    }

    private void send(final String contents) throws IOException {
        client.getOutputStream().write(frame(contents).getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    private static String frame(final String contents) {
        int checksum = 0;
        for (int i = 0; i < contents.length(); i++) {
            checksum += contents.charAt(i);
        }
        return String.format("$%s#%02x", contents, checksum & 0xFF);
    }

    private char readAck() throws IOException {
        client.setSoTimeout(5000);
        final InputStream in = client.getInputStream();
        return (char) in.read();
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

    private static final class FakeCPU implements CPUDebugInterface {
        final long[] registers = new long[32];
        final LongSet breakpoints = new LongOpenHashSet();
        final List<LongConsumer> listeners = new ArrayList<>();
        long pc;
        int steps;

        void hitBreakpoint(final long address) {
            for (final LongConsumer listener : listeners) {
                listener.accept(address);
            }
        }

        @Override
        public long getProgramCounter() {
            return pc;
        }

        @Override
        public void setProgramCounter(final long value) {
            pc = value;
        }

        @Override
        public void step() {
            steps++;
        }

        @Override
        public long[] getGeneralRegisters() {
            return registers;
        }

        @Override
        public byte[] loadDebug(final long address, final int size) throws MemoryAccessException {
            throw new MemoryAccessException();
        }

        @Override
        public int storeDebug(final long address, final byte[] data) throws MemoryAccessException {
            throw new MemoryAccessException();
        }

        @Override
        public void addBreakpointListener(final LongConsumer listener) {
            listeners.add(listener);
        }

        @Override
        public void removeBreakpointListener(final LongConsumer listener) {
            listeners.remove(listener);
        }

        @Override
        public void addBreakpoint(final long address) {
            breakpoints.add(address);
        }

        @Override
        public void removeBreakpoint(final long address) {
            breakpoints.remove(address);
        }
    }
}
