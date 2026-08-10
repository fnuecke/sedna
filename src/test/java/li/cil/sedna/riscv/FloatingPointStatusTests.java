package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class FloatingPointStatusTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

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
        assertTrapped(csrrs(1, R5.CSR_FFLAGS, 0));
        assertTrapped(csrrs(1, R5.CSR_FRM, 0));
        assertTrapped(csrrs(1, R5.CSR_FCSR, 0));
        assertTrapped(csrrw(0, R5.CSR_FFLAGS, 1));
        assertTrapped(csrrw(0, R5.CSR_FRM, 1));
        assertTrapped(csrrw(0, R5.CSR_FCSR, 1));
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
        assertCompleted(csrrs(1, R5.CSR_FFLAGS, 0));
        assertCompleted(csrrs(1, R5.CSR_FRM, 0));
        assertCompleted(csrrs(1, R5.CSR_FCSR, 0));
    }

    @Test
    public void usingTheFPUMarksStateDirty() {
        enableFPU();
        assertCompleted(FADD_D);

        assertEquals(R5.FS_DIRTY, (readMSTATUS() & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT);
    }

    @Test
    public void comparesAndConversionsMarkStateDirty() {
        assertMarksStateDirty(FEQ_S);
        assertMarksStateDirty(FEQ_D);
        assertMarksStateDirty(FCVT_W_S);
        assertMarksStateDirty(FCVT_L_D);
    }

    private void assertMarksStateDirty(final int instruction) {
        // Reset FS to Initial via a full mstatus write; csrrs cannot lower a Dirty FS.
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT;
        execute(csrrw(0, R5.CSR_MSTATUS, 1));

        assertCompleted(instruction);
        assertEquals(R5.FS_DIRTY, (readMSTATUS() & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT,
            String.format("expected instruction %08x to mark floating point state dirty", instruction));
    }

    private void enableFPU() {
        // csrrs x0, mstatus, x1 with x1 selecting FS=Initial.
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT;
        execute(csrrs(0, R5.CSR_MSTATUS, 1));

        assertNotEquals(R5.FS_OFF, (readMSTATUS() & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT);
    }

    private void setMTVEC(final long value) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = value;
        execute(csrrw(0, R5.CSR_MTVEC, 1));
    }

    private long readMSTATUS() {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[2] = 0;
        execute(csrrs(2, R5.CSR_MSTATUS, 0));
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

    // The operands are all register 0/1; which registers are used does not matter, only whether the
    // instruction is allowed to run at all.
    private static final int FADD_S = faddS(1, 1, 1, R5.FCSR_FRM_RNE);
    private static final int FADD_D = faddD(1, 1, 1, R5.FCSR_FRM_RNE);
    private static final int FSQRT_S = fsqrtS(1, 1, R5.FCSR_FRM_RNE);
    private static final int FMV_W_X = fmvWX(1, 1);
    private static final int FMV_X_W = fmvXW(1, 1);
    private static final int FEQ_S = feqS(1, 1, 1);
    private static final int FEQ_D = feqD(1, 1, 1);
    private static final int FCVT_W_S = fcvtWS(1, 1, R5.FCSR_FRM_RNE);
    private static final int FCVT_L_D = fcvtLD(1, 1, R5.FCSR_FRM_RNE);
    private static final int FLW = flw(1, ADDRESS_REGISTER, 0);
    private static final int FLD = fld(1, ADDRESS_REGISTER, 0);
    private static final int FSW = fsw(1, ADDRESS_REGISTER, 0);
    private static final int FSD = fsd(1, ADDRESS_REGISTER, 0);
}
