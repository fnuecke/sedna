package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class CanonicalAddressTests {
    private static final int RAM_SIZE = 16 * 1024;

    private static final long ROOT_PAGE_TABLE = Vm.RAM_START + 0x1000;
    private static final long DATA_ADDRESS = Vm.RAM_START + 0x100;
    private static final long SENTINEL = 0x1122334455667788L;

    // The canonical data address with junk in the upper bits: truncated it hits the identity
    // mapping below, so before the canonical check it would read successfully.
    private static final long NON_CANONICAL_DATA_ADDRESS = 0x0100000000000000L | DATA_ADDRESS;

    private static final long TRAP_VECTOR = Vm.RAM_START + 0x800;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.writeCSR(R5CSR.MTVEC, TRAP_VECTOR);
        vm.store64(DATA_ADDRESS, SENTINEL);
    }

    @Test
    public void nonCanonicalLoadFaultsUnderSv39() {
        enterSupervisorModeWithSv39();
        assertCanonicalLoadWorks();
        assertNonCanonicalAccessFaults(R5.EXCEPTION_LOAD_PAGE_FAULT, ld(6, 5, 0));
    }

    @Test
    public void nonCanonicalLoadFaultsUnderSv48() {
        enterSupervisorModeWithSv48();
        assertCanonicalLoadWorks();
        assertNonCanonicalAccessFaults(R5.EXCEPTION_LOAD_PAGE_FAULT, ld(6, 5, 0));
    }

    @Test
    public void nonCanonicalStoreFaultsUnderSv39() {
        enterSupervisorModeWithSv39();
        assertNonCanonicalAccessFaults(R5.EXCEPTION_STORE_PAGE_FAULT, sd(6, 5, 0));
        assertEquals(SENTINEL, vm.load64(DATA_ADDRESS), "the aliased store must not reach memory");
    }

    @Test
    public void nonCanonicalFetchFaultsUnderSv39() {
        enterSupervisorModeWithSv39();

        vm.setProgramCounter(NON_CANONICAL_DATA_ADDRESS);
        vm.stepOnce();

        assertEquals(R5.EXCEPTION_FETCH_PAGE_FAULT, vm.readCSR(R5CSR.MCAUSE));
        assertEquals(NON_CANONICAL_DATA_ADDRESS, vm.readCSR(R5CSR.MTVAL));
    }

    /**
     * Identity-maps [0x80000000, 0xC0000000) with a single 1 GiB superpage: VPN[2] of
     * 0x80000000 is 2, so leaf PTE index 2 in the root table.
     */
    private void enterSupervisorModeWithSv39() {
        vm.store64(ROOT_PAGE_TABLE + 2 * 8, Vm.leafPTE(Vm.RAM_START));
        enterSupervisorMode(R5.SATP_MODE_SV39);
    }

    /**
     * Identity-maps [0, 512G) with a single leaf in root PTE index 0 (VPN[3] of all our
     * addresses is 0).
     */
    private void enterSupervisorModeWithSv48() {
        vm.store64(ROOT_PAGE_TABLE, Vm.leafPTE(0));
        enterSupervisorMode(R5.SATP_MODE_SV48);
    }

    /**
     * Returns via SRET rather than MRET, so that the mapping under test is the one supervisor mode
     * itself uses to fetch and to access data.
     */
    private void enterSupervisorMode(final long satpMode) {
        vm.writeCSR(R5CSR.SATP, satpMode | (ROOT_PAGE_TABLE >>> R5.PAGE_ADDRESS_SHIFT));
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_SPP_MASK); // SPP = S.
        vm.execute(SRET);
    }

    private void assertCanonicalLoadWorks() {
        final long[] registers = vm.registers();
        registers[5] = DATA_ADDRESS;
        registers[6] = 0;
        vm.execute(ld(6, 5, 0));
        assertEquals(SENTINEL, registers[6], "the canonical address must translate");
    }

    private void assertNonCanonicalAccessFaults(final long cause, final int instruction) {
        final long[] registers = vm.registers();
        registers[5] = NON_CANONICAL_DATA_ADDRESS;
        registers[6] = 0;
        vm.execute(instruction);

        // The fault trapped to M-mode, where mcause/mtval are readable again.
        assertEquals(cause, vm.readCSR(R5CSR.MCAUSE));
        assertEquals(NON_CANONICAL_DATA_ADDRESS, vm.readCSR(R5CSR.MTVAL));
    }
}
