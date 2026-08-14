package li.cil.sedna.riscv;

import li.cil.sedna.api.debug.CPUDebugInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public final class DebugRegisterTests {
    private static final int RAM_SIZE = 4 * 1024;

    // Re-declared instead of using the internal consts for test independence.
    private static final int REG_X0 = 0;
    private static final int REG_X5 = 5;
    private static final int REG_PC = 32;
    private static final int REG_F0 = 33;
    private static final int REG_F31 = 64;
    private static final int REG_FFLAGS = 65;
    private static final int REG_FRM = 66;
    private static final int REG_FCSR = 67;
    private static final int REG_PRIV = 68;
    private static final int REG_CSR = 0x1000;

    private Vm vm;
    private CPUDebugInterface debug;

    // ------------------------------------------------------------- //

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        debug = vm.cpu().getDebugInterface();
    }

    @Test
    public void targetDescriptionIsOnTheClasspath() {
        final byte[] description = debug.getTargetDescription();
        assertNotNull(description, "without this a debugger only ever sees the general registers");
        assertTrue(new String(description, StandardCharsets.UTF_8).contains("riscv:rv64"),
                "the description must actually describe this CPU");
    }

    @Test
    public void targetDescriptionIsWellFormed() throws Exception {
        assertEquals("target", parseDescription().getDocumentElement().getNodeName());
    }

    @Test
    public void integerRegistersAreReachableByNumber() {
        vm.registers()[REG_X5] = 0xDEADBEEFL;

        assertEquals(8, debug.getRegisterSize(REG_X5));
        assertEquals(0xDEADBEEFL, debug.getRegister(REG_X5));

        assertTrue(debug.setRegister(REG_X5, 0x1234));
        assertEquals(0x1234, vm.registers()[REG_X5]);
    }

    @Test
    public void writingX0DoesNothing() {
        assertTrue(debug.setRegister(REG_X0, 0xFF), "the write is accepted...");
        assertEquals(0, debug.getRegister(REG_X0), "...but x0 stays hardwired to zero");
    }

    @Test
    public void theProgramCounterIsItsOwnRegister() {
        vm.setProgramCounter(Vm.RAM_START);

        assertEquals(8, debug.getRegisterSize(REG_PC));
        assertEquals(Vm.RAM_START, debug.getRegister(REG_PC));

        assertTrue(debug.setRegister(REG_PC, Vm.RAM_START + 4));
        assertEquals(Vm.RAM_START + 4, vm.programCounter());
    }

    @Test
    public void floatRegistersAreReachable() {
        assertEquals(8, debug.getRegisterSize(REG_F0));
        assertEquals(8, debug.getRegisterSize(REG_F31));

        assertTrue(debug.setRegister(REG_F0, 0x400921FB54442D18L));
        assertEquals(0x400921FB54442D18L, debug.getRegister(REG_F0));
    }

    @Test
    public void floatingPointStatusStaysVisibleWhileTheFpuIsOff() {
        assertEquals(4, debug.getRegisterSize(REG_FFLAGS), "fflags is four bytes wide");
        assertEquals(4, debug.getRegisterSize(REG_FRM));
        assertEquals(4, debug.getRegisterSize(REG_FCSR));
        assertEquals(0, debug.getRegister(REG_FFLAGS));

        assertEquals(0, debug.getRegisterSize(REG_CSR + R5CSR.FFLAGS),
                "the CSR-numbered alias reports itself unreadable, because it traps");
    }

    @Test
    public void csrsAreReachableAtTheirNumberPlusOffset() {
        final long satp = Vm.satpSv39(Vm.RAM_START);

        assertTrue(debug.setRegister(REG_CSR + R5CSR.SATP, satp));
        assertEquals(satp, debug.getRegister(REG_CSR + R5CSR.SATP));
        assertEquals(satp, vm.readCSR(R5CSR.SATP), "the write must be a real CSR write");
    }

    @Test
    public void unimplementedCsrsReportThemselvesAbsent() {
        assertEquals(0, debug.getRegisterSize(REG_CSR + 0x000), "ustatus is not implemented");
        assertFalse(debug.setRegister(REG_CSR + 0x000, 1));
    }

    @Test
    public void theWriteOnlyXlenSwitchReadsAsZero() {
        // GDB reads a register before writing it, so a register that only supports writes has to
        // answer reads with something rather than an error, or it could never be written at all.
        assertEquals(8, debug.getRegisterSize(REG_CSR + R5CSR.SEDNA_SWITCH_TO_XLEN32));
        assertEquals(0, debug.getRegister(REG_CSR + R5CSR.SEDNA_SWITCH_TO_XLEN32));
    }

    @Test
    public void privilegeIsReadableAndWritable() {
        assertEquals(8, debug.getRegisterSize(REG_PRIV));
        assertEquals(R5.PRIVILEGE_M, debug.getRegister(REG_PRIV), "reset leaves the CPU in M mode");

        assertTrue(debug.setRegister(REG_PRIV, R5.PRIVILEGE_S));
        assertEquals(R5.PRIVILEGE_S, debug.getRegister(REG_PRIV));
    }

    @Test
    public void nonsensePrivilegeLevelsAreRejected() {
        assertFalse(debug.setRegister(REG_PRIV, 4));
        assertEquals(R5.PRIVILEGE_M, debug.getRegister(REG_PRIV), "and leave the CPU alone");
    }

    @Test
    public void registersOutsideTheMapDoNotExist() {
        assertEquals(0, debug.getRegisterSize(69), "just past priv");
        assertEquals(0, debug.getRegisterSize(0x2000), "just past the CSRs");
        assertEquals(0, debug.getRegisterSize(-1));
        assertFalse(debug.setRegister(69, 1));
    }

    // ------------------------------------------------------------- //

    private Document parseDescription() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // For the gdb-target.dtd in the DOCTYPE.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(requireDescription()));
    }

    private byte[] requireDescription() {
        final byte[] description = debug.getTargetDescription();
        assertNotNull(description);
        return description;
    }
}
