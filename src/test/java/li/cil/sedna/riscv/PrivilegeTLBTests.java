package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PrivilegeTLBTests {
    private static final int RAM_SIZE = 16 * 1024 * 1024;

    private static final int MEGAPAGE = 2 * 1024 * 1024;

    /** Identity mapped in supervisor mode, so code can be fetched at the same address in both modes. */
    private static final long CODE = Vm.RAM_START;

    private static final long SHARED_ADDRESS = Vm.RAM_START + MEGAPAGE;
    private static final long SUPERVISOR_TARGET = Vm.RAM_START + 2 * MEGAPAGE;

    private static final long REMAP_TARGET = Vm.RAM_START + 4 * MEGAPAGE;

    private static final long ROOT_TABLE = Vm.RAM_START + 3 * MEGAPAGE;
    private static final long LEVEL1_TABLE = ROOT_TABLE + 0x1000;

    private static final long MARKER_MACHINE = 0x1111222233334444L;
    private static final long MARKER_SUPERVISOR = 0x5555666677778888L;
    private static final long MARKER_REMAPPED = 0x99AABBCCDDEEFF00L;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);

        // Megapage 0 identity maps the code; megapage 1, which contains SHARED_ADDRESS, is sent
        // somewhere else entirely so the two privilege levels disagree about what lives there.
        vm.store64(ROOT_TABLE + Vm.pageTableIndex(CODE, 2) * 8L, Vm.pointerPTE(LEVEL1_TABLE));
        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(CODE, 1) * 8L, Vm.leafPTE(Vm.RAM_START));
        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(SHARED_ADDRESS, 1) * 8L, Vm.leafPTE(SUPERVISOR_TARGET));

        vm.store64(SHARED_ADDRESS, MARKER_MACHINE);
        vm.store64(SUPERVISOR_TARGET, MARKER_SUPERVISOR);
        vm.store64(REMAP_TARGET, MARKER_REMAPPED);

        // Setup in machine mode; supervisor mode is not allowed to write mtvec.
        vm.writeCSR(R5CSR.MTVEC, CODE);
    }

    @Test
    public void machineModeEntriesAreNotUsedInSupervisorMode() {
        assertEquals(MARKER_MACHINE, readSharedAddress(), "machine mode must not translate");

        enterSupervisor();

        assertEquals(MARKER_SUPERVISOR, readSharedAddress(), "supervisor mode must use its own translation");
    }

    @Test
    public void supervisorModeEntriesAreNotUsedInMachineMode() {
        enterSupervisor();

        assertEquals(MARKER_SUPERVISOR, readSharedAddress());

        returnToMachine();

        assertEquals(MARKER_MACHINE, readSharedAddress(), "machine mode must not translate");
    }

    @Test
    public void repeatedCrossingsKeepResolvingCorrectly() {
        for (int i = 0; i < 8; i++) {
            enterSupervisor();
            assertEquals(MARKER_SUPERVISOR, readSharedAddress(), "iteration " + i);

            returnToMachine();
            assertEquals(MARKER_MACHINE, readSharedAddress(), "iteration " + i);
        }
    }

    @Test
    public void mretStayingInMachineModeRetagsDataAccesses() {
        // The OpenSBI pattern: machine mode uses MPRV to access memory with a lower privilege
        // level's translation. An MRET whose MPP is M stays in machine mode; but it still clears
        // MPP, which moves the effective privilege of data accesses when MPRV remains set. The TLB
        // must not keep serving machine-mode entries after that.
        vm.writeCSR(R5CSR.SATP, Vm.satpSv39(ROOT_TABLE));

        // Make the shared page's mapping accessible to effective user-mode accesses.
        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(SHARED_ADDRESS, 1) * 8L,
            Vm.leafPTE(SUPERVISOR_TARGET, Vm.PTE_CODE | R5.PTE_U_MASK));

        // MPP = M, MPRV = 1: data accesses are effectively machine mode, i.e. untranslated.
        vm.setCSRBits(R5CSR.MSTATUS, R5.STATUS_MPP_MASK | R5.STATUS_MPRV_MASK);
        assertEquals(MARKER_MACHINE, readSharedAddress(), "MPRV with MPP=M must not translate");

        // MRET with MPP = M: privilege stays M (so no flush happens on that path), MPP becomes U,
        // MPRV stays set, so data accesses are now effectively user mode and must translate.
        vm.writeCSR(R5CSR.MEPC, CODE);
        vm.execute(MRET);

        assertEquals(MARKER_SUPERVISOR, readSharedAddress(),
            "after MRET, MPRV data accesses must use the new MPP privilege, not stale TLB entries");
    }

    @Test
    public void flushingAPageInvalidatesEveryPrivilegeVariant() {
        assertEquals(MARKER_MACHINE, readSharedAddress());
        enterSupervisor();
        assertEquals(MARKER_SUPERVISOR, readSharedAddress());

        // Rewrite the mapping, then invalidate just that page the way a guest would, with SFENCE.VMA.
        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(SHARED_ADDRESS, 1) * 8L, Vm.leafPTE(REMAP_TARGET));
        vm.flushPage(SHARED_ADDRESS);

        assertEquals(MARKER_REMAPPED, readSharedAddress(), "the remapped page must resolve to its new target");
    }

    private long readSharedAddress() {
        final long[] registers = vm.registers();
        registers[1] = SHARED_ADDRESS;
        registers[2] = 0;
        vm.execute(ld(2, 1, 0));
        return registers[2];
    }

    private void enterSupervisor() {
        vm.enterSupervisor(Vm.satpSv39(ROOT_TABLE), CODE);
    }

    private void returnToMachine() {
        vm.execute(ECALL);
    }
}
