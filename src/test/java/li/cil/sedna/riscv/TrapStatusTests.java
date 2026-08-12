package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TrapStatusTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long TRAP_VECTOR = Vm.RAM_START + 0x800;

    // WPRI bits adjacent to the IE/PIE bits, which the broken restore polluted.
    private static final long WPRI_BITS = (1L << 2) | (1L << 6);
    // UIE/UPIE, hardwired zero without the N extension.
    private static final long USER_IE_BITS = R5.STATUS_UIE_MASK | R5.STATUS_UPIE_MASK;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.writeCSR(R5CSR.MTVEC, TRAP_VECTOR);
    }

    @Test
    public void mretRestoresMIEFromMPIE() {
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_MPIE_MASK | ((long) R5.PRIVILEGE_M << R5.STATUS_MPP_SHIFT));
        vm.execute(MRET);

        final long mstatus = vm.readCSR(R5CSR.MSTATUS);
        assertEquals(R5.STATUS_MIE_MASK, mstatus & R5.STATUS_MIE_MASK, "MIE must be restored from MPIE");
        assertEquals(R5.STATUS_MPIE_MASK, mstatus & R5.STATUS_MPIE_MASK, "MPIE must be set after MRET");
        assertEquals(0, mstatus & R5.STATUS_MPP_MASK, "MPP must be set to U after MRET");
        assertEquals(0, mstatus & WPRI_BITS, "WPRI bits must remain zero");
    }

    @Test
    public void trapFromUModeSavesMachineIEIntoMPIE() {
        // Drop to U-mode with MIE set; the ecall's trap entry must save MIE (the target mode's
        // IE bit), not U-mode's nonexistent UIE.
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_MPIE_MASK); // MPP = U.
        vm.execute(MRET);
        vm.execute(ECALL);

        final long mstatus = vm.readCSR(R5CSR.MSTATUS);
        assertEquals(R5.STATUS_MPIE_MASK, mstatus & R5.STATUS_MPIE_MASK, "MPIE must hold the pre-trap MIE");
        assertEquals(0, mstatus & R5.STATUS_MIE_MASK, "MIE must be cleared on trap entry");
        assertEquals(0, mstatus & R5.STATUS_MPP_MASK, "MPP must hold the interrupted privilege (U)");

        // And returning must bring MIE back: after MRET we are in U-mode again (mstatus is not
        // readable there), so trap back in and check the IE bit the entry saved.
        vm.execute(MRET);
        vm.execute(ECALL);
        assertEquals(R5.STATUS_MPIE_MASK, vm.readCSR(R5CSR.MSTATUS) & R5.STATUS_MPIE_MASK,
                "MRET must restore MIE, observed via the MPIE the following trap saves");
    }

    @Test
    public void sretRestoresSIEFromSPIE() {
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_SPIE_MASK | R5.STATUS_SPP_MASK); // SPP = S.
        vm.execute(SRET);

        // Now in S-mode; sstatus is the window we have.
        final long sstatus = vm.readCSR(R5CSR.SSTATUS);
        assertEquals(R5.STATUS_SIE_MASK, sstatus & R5.STATUS_SIE_MASK, "SIE must be restored from SPIE");
        assertEquals(R5.STATUS_SPIE_MASK, sstatus & R5.STATUS_SPIE_MASK, "SPIE must be set after SRET");
        assertEquals(0, sstatus & R5.STATUS_SPP_MASK, "SPP must be set to U after SRET");
        assertEquals(0, sstatus & (WPRI_BITS | USER_IE_BITS), "WPRI and user IE bits must remain zero");
    }

    @Test
    public void sretToUModeRestoresSIENotUIE() {
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_SPIE_MASK); // SPP = U.
        vm.execute(SRET);

        // Now in U-mode; trap back to M to inspect state.
        vm.execute(ECALL);
        final long mstatus = vm.readCSR(R5CSR.MSTATUS);
        assertEquals(R5.STATUS_SIE_MASK, mstatus & R5.STATUS_SIE_MASK, "SIE must be restored even when returning to U");
        assertEquals(0, mstatus & (WPRI_BITS | USER_IE_BITS), "WPRI and user IE bits must remain zero");
    }

    @Test
    public void sstatusHardwiresUserIEBitsToZero() {
        vm.writeCSR(R5CSR.MSTATUS, USER_IE_BITS | R5.STATUS_SIE_MASK);
        assertEquals(0, vm.readCSR(R5CSR.SSTATUS) & USER_IE_BITS, "sstatus must hide UIE/UPIE without the N extension");
    }
}
