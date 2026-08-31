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
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public final class GDBStubCPUTests {
    private static final long RAM_START = 0x80000000L;
    private static final int RAM_SIZE = 4 * 1024;

    private static final int REG_PC = 32;

    private R5CPU cpu;
    private CPUDebugInterface debug;
    private GDBTestClient client;

    @BeforeEach
    public void setUp() throws IOException {
        final MemoryMap memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(RAM_START, Memory.create(RAM_SIZE));
        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, RAM_START);
        cpu.setXLEN(R5.XLEN_64);
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
    public void theDescriptionIsLongEnoughToNeedMoreThanOneChunk() throws Exception {
        // Otherwise the test above would not actually be testing chunking.
        assertEquals('m', client.request("qXfer:features:read:target.xml:0,ffb").charAt(0));
    }

    @Test
    public void aCsrCanBeReadThroughTheStub() throws Exception {
        // misa is 0x301, and reports the ISA the CPU was built with, so it is never zero.
        final String reply = client.request("p1301");

        assertEquals(16, reply.length(), "a CSR is eight bytes of hex");
        assertNotEquals(0, Long.reverseBytes(Long.parseUnsignedLong(reply, 16)), "misa must be set");
    }

    @Test
    public void theProgramCounterAgreesWithTheCpu() throws Exception {
        assertEquals(RAM_START, Long.reverseBytes(Long.parseUnsignedLong(client.request("p%x".formatted(REG_PC)), 16)));
    }

    @Test
    public void writingARegisterThroughTheStubReachesTheCpu() throws Exception {
        assertEquals("OK", client.request("P5=efbeadde00000000"));
        assertEquals(0xDEADBEEFL, cpu.getGeneralRegisters()[5]);
    }

    @Test
    public void generalRegistersAreThirtyThreeEightByteValuesEndingInThePc() throws Exception {
        cpu.getGeneralRegisters()[5] = 0xDEADBEEFL;

        final String reply = client.request("g");

        assertEquals(33 * 8 * 2, reply.length(), "33 registers of eight bytes, hex encoded");
        assertEquals("efbeadde00000000", reply.substring(5 * 16, 6 * 16), "x5, little endian");
        assertEquals("0000008000000000", reply.substring(REG_PC * 16), "the pc comes last");
    }

    @Test
    public void writingAllGeneralRegistersKeepsX0Zero() throws Exception {
        final StringBuilder request = new StringBuilder("G");
        for (int id = 0; id < 33; id++) {
            request.append("0100000000000000");
        }

        assertEquals("OK", client.request(request.toString()));
        assertEquals(0, cpu.getGeneralRegisters()[0], "x0 is hardwired to zero");
        assertEquals(1, cpu.getGeneralRegisters()[1]);
        assertEquals(1, debug.getProgramCounter());
    }

    @Test
    public void aShortGeneralRegisterWriteIsRejected() throws Exception {
        assertEquals("E01", client.request("G0100000000000000"));
    }

    // ------------------------------------------------------------- //

    private byte[] requireDescription() {
        final byte[] description = debug.getTargetDescription();
        assertNotNull(description);
        return description;
    }
}
