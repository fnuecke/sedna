package li.cil.sedna.gdbstub;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import li.cil.sedna.api.debug.CPUDebugInterface;
import li.cil.sedna.api.memory.MemoryAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

public final class GDBStubTests {
    private static final Duration MUST_NOT_BLOCK = Duration.ofSeconds(5);
    private static final String SUPPORTED = "PacketSize=2000;qXfer:features:read+";

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

        assertEquals(SUPPORTED, readPacket(), "a dribbled packet must still be understood");
    }

    @Test
    public void theStubOffersTheTargetDescription() throws Exception {
        connect();
        poll();
        readPacket();

        send("qSupported:multiprocess+");
        poll();

        assertTrue(readPacket().contains("qXfer:features:read+"),
                "a debugger only asks for the target description if we say we have one");
    }

    @Test
    public void theTargetDescriptionIsServedInChunks() throws Exception {
        // Long enough that it cannot be sent in one packet, so the debugger has to ask twice.
        final String description = "A".repeat(5000);
        cpu.targetDescription = description.getBytes(StandardCharsets.US_ASCII);
        connect();
        poll();
        readPacket();

        send("qXfer:features:read:target.xml:0,1000");
        poll();
        final String first = readPacket();
        assertEquals('m', first.charAt(0), "'m' says more is to come");

        final int sent = first.length() - 1;
        assertTrue(sent > 0 && sent < description.length(), "the first chunk must be a real prefix");

        send("qXfer:features:read:target.xml:%x,1000".formatted(sent));
        poll();
        final String second = readPacket();
        assertEquals('l', second.charAt(0), "'l' says this was the last chunk");

        assertEquals(description, first.substring(1) + second.substring(1),
                "the chunks must reassemble into the description");
    }

    @Test
    public void bytesThatWouldConfuseTheProtocolAreEscaped() throws Exception {
        cpu.targetDescription = "#$}*".getBytes(StandardCharsets.US_ASCII);
        connect();
        poll();
        readPacket();

        send("qXfer:features:read:target.xml:0,1000");
        poll();

        // Escaped as '}' + byte^0x20: '#'->0x03, '$'->0x04, '}'->0x5d (']'), '*'->0x0a ('\n').
        assertEquals("l}\u0003}\u0004}]}\n", readPacket(),
                "every byte escaped, and 'l' because it all fits in one chunk");
    }

    @Test
    public void unknownTargetDescriptionIsAnError() throws Exception {
        connect();
        poll();
        readPacket();

        send("qXfer:features:read:nope.xml:0,1000");
        poll();

        assertEquals("E00", readPacket());
    }

    @Test
    public void readingASingleRegisterReportsItsOwnWidth() throws Exception {
        cpu.addRegister(65, 4, 0x1234); // Something narrower than a general register.
        connect();
        poll();
        readPacket();

        send("p41");
        poll();

        assertEquals("34120000", readPacket(), "4 bytes, little endian");
    }

    @Test
    public void unknownRegistersAreRejected() throws Exception {
        connect();
        poll();
        readPacket();

        send("p999");
        poll();
        assertEquals("E01", readPacket(), "reading a register the CPU does not have must fail");

        send("P999=0000000000000000");
        poll();
        assertEquals("E01", readPacket(), "and so must writing one");
    }

    @Test
    public void singleRegisterRoundTrips() throws Exception {
        connect();
        poll();
        readPacket();

        send("P5=efbeadde00000000");
        poll();
        assertEquals("OK", readPacket());
        assertEquals(0xDEADBEEFL, cpu.registers[5], "the write must reach the CPU");

        send("p5");
        poll();
        assertEquals("efbeadde00000000", readPacket(), "and read back little endian");
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
        static final int REG_PC = 32;

        final long[] registers = new long[32];
        final LongSet breakpoints = new LongOpenHashSet();
        final List<LongConsumer> listeners = new ArrayList<>();
        // Registers beyond the general ones, as id -> (size, value). Empty unless a test adds one.
        final Map<Integer, Integer> extraRegisterSizes = new HashMap<>();
        final Map<Integer, Long> extraRegisters = new HashMap<>();
        @Nullable
        byte[] targetDescription = "<target></target>".getBytes(StandardCharsets.US_ASCII);
        long pc;
        int steps;

        void addRegister(final int id, final int size, final long value) {
            extraRegisterSizes.put(id, size);
            extraRegisters.put(id, value);
        }

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

        @Nullable
        @Override
        public byte[] getTargetDescription() {
            return targetDescription;
        }

        @Override
        public int getRegisterSize(final int id) {
            if (id >= 0 && id < registers.length) return 8;
            if (id == REG_PC) return 8;
            return extraRegisterSizes.getOrDefault(id, 0);
        }

        @Override
        public long getRegister(final int id) {
            if (id >= 0 && id < registers.length) return registers[id];
            if (id == REG_PC) return pc;
            return extraRegisters.getOrDefault(id, 0L);
        }

        @Override
        public boolean setRegister(final int id, final long value) {
            if (id >= 0 && id < registers.length) {
                registers[id] = value;
                return true;
            }
            if (id == REG_PC) {
                pc = value;
                return true;
            }
            if (!extraRegisterSizes.containsKey(id)) {
                return false;
            }
            extraRegisters.put(id, value);
            return true;
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
