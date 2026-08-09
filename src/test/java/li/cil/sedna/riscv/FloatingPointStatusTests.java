package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class FloatingPointStatusTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final int CSR_MSTATUS = 0x300;
    private static final int CSR_FFLAGS = 0x001;
    private static final int CSR_FRM = 0x002;
    private static final int CSR_FCSR = 0x003;

    private static final long TRAP_VECTOR = PHYSICAL_MEMORY_START + 0x800;

    private static final int ADDRESS_REGISTER = 5;
    private static final long DATA_ADDRESS = PHYSICAL_MEMORY_START + 0x100;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);

        setMTVEC(TRAP_VECTOR);
        cpu.getDebugInterface().getGeneralRegisters()[ADDRESS_REGISTER] = DATA_ADDRESS;
    }

    @Test
    public void fsIsOffAfterReset() {
        // The whole point of the trap is that a supervisor can start with the FPU off, so a reset
        // CPU must not already have it enabled.
        assertTrapped(FADD_D);
    }

    @Test
    public void floatingPointArithmeticTrapsWhileFSIsOff() {
        assertTrapped(FADD_S);
        assertTrapped(FADD_D);
        assertTrapped(FSQRT_S);
    }

    @Test
    public void floatingPointLoadsAndMovesTrapWhileFSIsOff() {
        assertTrapped(FLW);
        assertTrapped(FLD);
        assertTrapped(FMV_W_X);
        assertTrapped(FMV_X_W);
    }

    @Test
    public void floatingPointStoresTrapWhileFSIsOff() {
        assertTrapped(FSW);
        assertTrapped(FSD);
    }

    @Test
    public void floatingPointCSRsTrapWhileFSIsOff() {
        assertTrapped(csrrs(1, CSR_FFLAGS, 0));
        assertTrapped(csrrs(1, CSR_FRM, 0));
        assertTrapped(csrrs(1, CSR_FCSR, 0));
        assertTrapped(csrrw(0, CSR_FFLAGS, 1));
        assertTrapped(csrrw(0, CSR_FRM, 1));
        assertTrapped(csrrw(0, CSR_FCSR, 1));
    }

    @Test
    public void nothingTrapsOnceFSIsEnabled() {
        enableFPU();

        assertCompleted(FADD_S);
        assertCompleted(FADD_D);
        assertCompleted(FSQRT_S);
        assertCompleted(FLW);
        assertCompleted(FLD);
        assertCompleted(FMV_W_X);
        assertCompleted(FMV_X_W);
        assertCompleted(FSW);
        assertCompleted(FSD);
        assertCompleted(csrrs(1, CSR_FFLAGS, 0));
        assertCompleted(csrrs(1, CSR_FRM, 0));
        assertCompleted(csrrs(1, CSR_FCSR, 0));
    }

    @Test
    public void usingTheFPUMarksStateDirty() {
        enableFPU();
        assertCompleted(FADD_D);

        assertEquals(R5.FS_DIRTY, (readMSTATUS() & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT);
    }

    private void enableFPU() {
        // csrrs x0, mstatus, x1 with x1 selecting FS=Initial.
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT;
        execute(csrrs(0, CSR_MSTATUS, 1));

        assertNotEquals(R5.FS_OFF, (readMSTATUS() & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT);
    }

    private void setMTVEC(final long value) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = value;
        execute(csrrw(0, 0x305 /* mtvec */, 1));
    }

    private long readMSTATUS() {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[2] = 0;
        execute(csrrs(2, CSR_MSTATUS, 0));
        return registers[2];
    }

    private long execute(final int instruction) {
        try {
            memoryMap.store(PHYSICAL_MEMORY_START, instruction, Sizes.SIZE_32_LOG2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }

        cpu.getDebugInterface().setProgramCounter(PHYSICAL_MEMORY_START);
        cpu.getDebugInterface().step();
        return cpu.getDebugInterface().getProgramCounter();
    }

    private void assertTrapped(final int instruction) {
        assertEquals(TRAP_VECTOR, execute(instruction),
            String.format("expected instruction %08x to trap while mstatus.FS is Off", instruction));
    }

    private void assertCompleted(final int instruction) {
        assertEquals(PHYSICAL_MEMORY_START + 4, execute(instruction),
            String.format("expected instruction %08x to complete while mstatus.FS is on", instruction));
    }

    // Encoded operands are all register 0/1, which keeps the encodings simple; which registers are
    // used does not matter, only whether the instruction is allowed to run at all.
    private static final int FADD_S = fp(0b0000000, 1, 1, 0b000, 1, 0b1010011);
    private static final int FADD_D = fp(0b0000001, 1, 1, 0b000, 1, 0b1010011);
    private static final int FSQRT_S = fp(0b0101100, 0, 1, 0b000, 1, 0b1010011);
    private static final int FMV_W_X = fp(0b1111000, 0, 1, 0b000, 1, 0b1010011);
    private static final int FMV_X_W = fp(0b1110000, 0, 1, 0b000, 1, 0b1010011);
    private static final int FLW = load(1, ADDRESS_REGISTER, 0, 0b010);
    private static final int FLD = load(1, ADDRESS_REGISTER, 0, 0b011);
    private static final int FSW = store(1, ADDRESS_REGISTER, 0, 0b010);
    private static final int FSD = store(1, ADDRESS_REGISTER, 0, 0b011);

    private static int fp(final int funct7, final int rs2, final int rs1, final int rm, final int rd, final int opcode) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (rm << 12) | (rd << 7) | opcode;
    }

    private static int load(final int rd, final int rs1, final int offset, final int width) {
        return (offset << 20) | (rs1 << 15) | (width << 12) | (rd << 7) | 0b0000111;
    }

    private static int store(final int rs2, final int rs1, final int offset, final int width) {
        return (((offset >> 5) & 0b1111111) << 25) | (rs2 << 20) | (rs1 << 15) | (width << 12)
            | ((offset & 0b11111) << 7) | 0b0100111;
    }

    private static int csrrw(final int rd, final int csr, final int rs1) {
        return csr(rd, csr, rs1, 0b001);
    }

    private static int csrrs(final int rd, final int csr, final int rs1) {
        return csr(rd, csr, rs1, 0b010);
    }

    private static int csr(final int rd, final int csr, final int rs1, final int funct3) {
        return (csr << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0b1110011;
    }
}
