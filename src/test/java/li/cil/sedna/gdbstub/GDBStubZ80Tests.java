package li.cil.sedna.gdbstub;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.debug.CPUDebugInterface;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import li.cil.sedna.z80.Z80CPU;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public final class GDBStubZ80Tests {
    private static final int G_PACKET_BYTES = 13 * 2 + 3;

    private static final int REG_AF = 0;
    private static final int REG_BC = 1;
    private static final int REG_SP = 4;
    private static final int REG_PC = 5;
    private static final int REG_IR = 12;
    private static final int REG_IM = 13;

    private static final int START_PC = 0x0100;

    private PhysicalMemory memory;
    private Z80CPU cpu;
    private CPUDebugInterface debug;
    private GDBTestClient client;

    @BeforeEach
    public void setUp() throws IOException {
        final SimpleMemoryMap memoryMap = new SimpleMemoryMap();
        memory = Memory.create(0x10000);
        assertTrue(memoryMap.addDevice(0, memory));
        cpu = Z80CPU.create(memoryMap, new SimpleMemoryMap());
        cpu.reset(true, START_PC);
        debug = cpu.getDebugInterface();

        client = GDBTestClient.connect(debug);
    }

    @AfterEach
    public void tearDown() throws IOException {
        client.close();
    }

    @Test
    public void targetDescriptionIsSent() throws Exception {
        final String expected = new String(requireDescription(), StandardCharsets.US_ASCII);

        final StringBuilder received = new StringBuilder();
        boolean last = false;
        for (int i = 0; i < 64 && !last; i++) { // Bounded, so a broken 'l' cannot hang the test.
            final String reply = client.request("qXfer:features:read:target.xml:%x,ffb".formatted(received.length()));
            last = reply.charAt(0) == 'l';
            received.append(GDBTestClient.unescape(reply.substring(1)));
        }

        assertTrue(last, "the transfer must terminate with a final 'l' chunk");
        assertEquals(expected, received.toString(),
                "the description the CPU serves must arrive byte for byte");
    }

    @Test
    public void theGPacketMatchesTheDescribedLayout() throws Exception {
        debug.setRegister(REG_BC, 0x1234);
        debug.setRegister(REG_IR, 0xABCD);
        debug.setRegister(REG_IM, 2);

        final String reply = client.request("g");

        assertEquals(G_PACKET_BYTES * 2, reply.length());
        assertEquals("ffff", hexAt(reply, REG_AF, 2), "af is 0xFFFF after a hard reset");
        assertEquals("3412", hexAt(reply, REG_BC, 2), "bc, little endian");
        assertEquals("ffff", hexAt(reply, REG_SP, 2), "sp is 0xFFFF after a hard reset");
        assertEquals("0001", hexAt(reply, REG_PC, 2), "pc, little endian");
        assertEquals("cdab", hexAt(reply, REG_IR, 2), "ir, little endian");
        assertEquals("02", reply.substring(26 * 2, 27 * 2), "im is a single byte after ir");
    }

    @Test
    public void writingAllRegistersRoundTrips() throws Exception {
        final StringBuilder request = new StringBuilder("G");
        for (int id = 0; id < 13; id++) {
            request.append("3412"); // 0x1234, little endian.
        }
        request.append("02").append("01").append("00"); // im, iff1, iff2.

        assertEquals("OK", client.request(request.toString()));
        assertEquals(0x1234, debug.getRegister(REG_AF));
        assertEquals(0x1234, debug.getRegister(REG_PC));
        assertEquals(2, debug.getRegister(REG_IM));
        assertEquals(request.substring(1), client.request("g"),
                "reading back must reproduce what was written");
    }

    @Test
    public void aShortGeneralRegisterWriteIsRejected() throws Exception {
        assertEquals("E01", client.request("G3412"));
    }

    @Test
    public void singleRegistersCanBeReadAndWritten() throws Exception {
        assertEquals("0001", client.request("p%x".formatted(REG_PC)));

        assertEquals("OK", client.request("P%x=3412".formatted(REG_SP)));
        assertEquals(0x1234, debug.getRegister(REG_SP));

        assertEquals("OK", client.request("P%x=02".formatted(REG_IM)));
        assertEquals(2, debug.getRegister(REG_IM));

        assertEquals("E01", client.request("P%x=03".formatted(REG_IM)), "there is no interrupt mode 3");
        assertEquals(2, debug.getRegister(REG_IM), "a rejected write must not change the register");
    }

    @Test
    public void memoryCanBeReadAndWritten() throws Exception {
        memory.store(0x2000, 0xC9, Sizes.SIZE_8_LOG2);

        assertEquals("c9", client.request("m2000,1"));

        assertEquals("OK", client.request("M2000,2:0076"));
        assertEquals(0x00, memory.load(0x2000, Sizes.SIZE_8_LOG2) & 0xFF);
        assertEquals(0x76, memory.load(0x2001, Sizes.SIZE_8_LOG2) & 0xFF);
    }

    @Test
    public void breakpointsReachTheCpuAndStopIt() throws Exception {
        memory.store(START_PC, 0x00, Sizes.SIZE_8_LOG2);     // NOP
        memory.store(START_PC + 1, 0x00, Sizes.SIZE_8_LOG2); // NOP
        memory.store(START_PC + 2, 0x76, Sizes.SIZE_8_LOG2); // HALT

        assertEquals("OK", client.request("Z0,%x,1".formatted(START_PC + 2)));

        cpu.step(1000);
        assertEquals(START_PC + 2, debug.getProgramCounter(), "the breakpoint must stop the cpu");
        assertFalse(cpu.isHalted(), "the HALT the breakpoint sits on must not have run");

        assertEquals("OK", client.request("z0,%x,1".formatted(START_PC + 2)));
        cpu.step(1000);
        assertTrue(cpu.isHalted(), "removing the breakpoint must let execution continue");
    }

    // ------------------------------------------------------------- //

    private static String hexAt(final String reply, final int id, final int size) {
        return reply.substring(id * size * 2, (id + 1) * size * 2);
    }

    private byte[] requireDescription() {
        final byte[] description = debug.getTargetDescription();
        assertNotNull(description);
        return description;
    }
}
