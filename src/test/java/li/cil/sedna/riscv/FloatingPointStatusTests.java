package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class FloatingPointStatusTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long TRAP_VECTOR = Vm.RAM_START + 0x800;

    private static final int ADDRESS_REGISTER = 5;
    private static final long DATA_ADDRESS = Vm.RAM_START + 0x100;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.writeCSR(R5CSR.MTVEC, TRAP_VECTOR);
        vm.registers()[ADDRESS_REGISTER] = DATA_ADDRESS;
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
        assertTrapped(csrrs(1, R5CSR.FFLAGS, 0));
        assertTrapped(csrrs(1, R5CSR.FRM, 0));
        assertTrapped(csrrs(1, R5CSR.FCSR, 0));
        assertTrapped(csrrw(0, R5CSR.FFLAGS, 1));
        assertTrapped(csrrw(0, R5CSR.FRM, 1));
        assertTrapped(csrrw(0, R5CSR.FCSR, 1));
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
        assertCompleted(csrrs(1, R5CSR.FFLAGS, 0));
        assertCompleted(csrrs(1, R5CSR.FRM, 0));
        assertCompleted(csrrs(1, R5CSR.FCSR, 0));
    }

    @Test
    public void usingTheFPUMarksStateDirty() {
        enableFPU();
        assertCompleted(FADD_D);

        assertEquals(R5.FS_DIRTY, floatingPointState());
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
        vm.writeCSR(R5CSR.MSTATUS, (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT);

        assertCompleted(instruction);
        assertEquals(R5.FS_DIRTY, floatingPointState(),
                String.format("expected instruction %08x to mark floating point state dirty", instruction));
    }

    private void enableFPU() {
        vm.setCSRBits(R5CSR.MSTATUS, (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT);
        assertNotEquals(R5.FS_OFF, floatingPointState());
    }

    private long floatingPointState() {
        return (vm.readCSR(R5CSR.MSTATUS) & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT;
    }

    private void assertTrapped(final int instruction) {
        assertEquals(TRAP_VECTOR, vm.execute(instruction),
                String.format("expected instruction %08x to trap while mstatus.FS is Off", instruction));
    }

    private void assertCompleted(final int instruction) {
        assertEquals(Vm.RAM_START + 4, vm.execute(instruction),
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
