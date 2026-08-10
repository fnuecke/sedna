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

public final class PrivilegeTLBTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 16 * 1024 * 1024;

    private static final int MEGAPAGE = 2 * 1024 * 1024;

    /** Identity mapped in supervisor mode, so code can be fetched at the same address in both modes. */
    private static final long CODE = PHYSICAL_MEMORY_START;

    private static final long SHARED_ADDRESS = PHYSICAL_MEMORY_START + MEGAPAGE;
    private static final long SUPERVISOR_TARGET = PHYSICAL_MEMORY_START + 2 * MEGAPAGE;

    private static final long REMAP_TARGET = PHYSICAL_MEMORY_START + 4 * MEGAPAGE;

    private static final long ROOT_TABLE = PHYSICAL_MEMORY_START + 3 * MEGAPAGE;
    private static final long LEVEL1_TABLE = ROOT_TABLE + 0x1000;

    private static final long MARKER_MACHINE = 0x1111222233334444L;
    private static final long MARKER_SUPERVISOR = 0x5555666677778888L;
    private static final long MARKER_REMAPPED = 0x99AABBCCDDEEFF00L;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        // Megapage 0 identity maps the code; megapage 1, which contains SHARED_ADDRESS, is sent
        // somewhere else entirely so the two privilege levels disagree about what lives there.
        memoryMap.store(ROOT_TABLE + index(CODE, 2) * 8L, pointerPTE(LEVEL1_TABLE), Sizes.SIZE_64_LOG2);
        memoryMap.store(LEVEL1_TABLE + index(CODE, 1) * 8L, leafPTE(PHYSICAL_MEMORY_START), Sizes.SIZE_64_LOG2);
        memoryMap.store(LEVEL1_TABLE + index(SHARED_ADDRESS, 1) * 8L, leafPTE(SUPERVISOR_TARGET), Sizes.SIZE_64_LOG2);

        memoryMap.store(SHARED_ADDRESS, MARKER_MACHINE, Sizes.SIZE_64_LOG2);
        memoryMap.store(SUPERVISOR_TARGET, MARKER_SUPERVISOR, Sizes.SIZE_64_LOG2);
        memoryMap.store(REMAP_TARGET, MARKER_REMAPPED, Sizes.SIZE_64_LOG2);

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, CODE);
        cpu.setXLEN(R5.XLEN_64);

        // Setup in machine mode; supervisor mode is not allowed to write mtvec.
        writeCSR(R5.CSR_MTVEC, CODE);
    }

    @Test
    public void machineModeEntriesAreNotUsedInSupervisorMode() throws MemoryAccessException {
        assertEquals(MARKER_MACHINE, readSharedAddress(), "machine mode must not translate");

        enterSupervisor();

        assertEquals(MARKER_SUPERVISOR, readSharedAddress(), "supervisor mode must use its own translation");
    }

    @Test
    public void supervisorModeEntriesAreNotUsedInMachineMode() throws MemoryAccessException {
        enterSupervisor();

        assertEquals(MARKER_SUPERVISOR, readSharedAddress());

        returnToMachine();

        assertEquals(MARKER_MACHINE, readSharedAddress(), "machine mode must not translate");
    }

    @Test
    public void repeatedCrossingsKeepResolvingCorrectly() throws MemoryAccessException {
        for (int i = 0; i < 8; i++) {
            enterSupervisor();
            assertEquals(MARKER_SUPERVISOR, readSharedAddress(), "iteration " + i);

            returnToMachine();
            assertEquals(MARKER_MACHINE, readSharedAddress(), "iteration " + i);
        }
    }

    @Test
    public void mretStayingInMachineModeRetagsDataAccesses() throws MemoryAccessException {
        // The OpenSBI pattern: machine mode uses MPRV to access memory with a lower privilege
        // level's translation. An MRET whose MPP is M stays in machine mode; but it still clears
        // MPP, which moves the effective privilege of data accesses when MPRV remains set. The TLB
        // must not keep serving machine-mode entries after that.
        writeCSR(R5.CSR_SATP, R5.SATP_MODE_SV39 | (ROOT_TABLE >>> R5.PAGE_ADDRESS_SHIFT));

        // Make the shared page's mapping accessible to effective user-mode accesses.
        memoryMap.store(LEVEL1_TABLE + index(SHARED_ADDRESS, 1) * 8L,
            leafPTE(SUPERVISOR_TARGET) | R5.PTE_U_MASK, Sizes.SIZE_64_LOG2);

        // MPP = M, MPRV = 1: data accesses are effectively machine mode, i.e. untranslated.
        setCSRBits(R5.CSR_MSTATUS, R5.STATUS_MPP_MASK | R5.STATUS_MPRV_MASK);
        assertEquals(MARKER_MACHINE, readSharedAddress(), "MPRV with MPP=M must not translate");

        // MRET with MPP = M: privilege stays M (so no flush happens on that path), MPP becomes U,
        // MPRV stays set, so data accesses are now effectively user mode and must translate.
        writeCSR(R5.CSR_MEPC, CODE);
        execute(MRET);

        assertEquals(MARKER_SUPERVISOR, readSharedAddress(),
            "after MRET, MPRV data accesses must use the new MPP privilege, not stale TLB entries");
    }

    @Test
    public void flushingAPageInvalidatesEveryPrivilegeVariant() throws MemoryAccessException {
        assertEquals(MARKER_MACHINE, readSharedAddress());
        enterSupervisor();
        assertEquals(MARKER_SUPERVISOR, readSharedAddress());

        // Rewrite the mapping, then invalidate just that page the way a guest would, with SFENCE.VMA.
        memoryMap.store(LEVEL1_TABLE + index(SHARED_ADDRESS, 1) * 8L, leafPTE(REMAP_TARGET), Sizes.SIZE_64_LOG2);
        flushPage(SHARED_ADDRESS);

        assertEquals(MARKER_REMAPPED, readSharedAddress(), "the remapped page must resolve to its new target");
    }

    /** Issues {@code sfence.vma rs1, x0}, invalidating the translations for one page. */
    private void flushPage(final long address) throws MemoryAccessException {
        cpu.getDebugInterface().getGeneralRegisters()[3] = address;
        execute(sfenceVma(3));
    }

    private long readSharedAddress() throws MemoryAccessException {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = SHARED_ADDRESS;
        registers[2] = 0;
        execute(ld(2, 1, 0));
        return registers[2];
    }

    private void enterSupervisor() throws MemoryAccessException {
        writeCSR(R5.CSR_SATP, R5.SATP_MODE_SV39 | (ROOT_TABLE >>> R5.PAGE_ADDRESS_SHIFT));
        setCSRBits(R5.CSR_MSTATUS, (long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);
        writeCSR(R5.CSR_MEPC, CODE);
        execute(MRET);
    }

    private void returnToMachine() throws MemoryAccessException {
        execute(ECALL);
    }

    private void writeCSR(final int csr, final long value) throws MemoryAccessException {
        cpu.getDebugInterface().getGeneralRegisters()[31] = value;
        execute(csrrw(0, csr, 31));
    }

    private void setCSRBits(final int csr, final long mask) throws MemoryAccessException {
        cpu.getDebugInterface().getGeneralRegisters()[31] = mask;
        execute(csrrs(0, csr, 31));
    }

    private void execute(final int instruction) throws MemoryAccessException {
        memoryMap.store(CODE, instruction, Sizes.SIZE_32_LOG2);
        cpu.getDebugInterface().setProgramCounter(CODE);
        cpu.getDebugInterface().step();
    }

    private static int index(final long address, final int level) {
        return (int) ((address >>> (R5.PAGE_ADDRESS_SHIFT + 9 * level)) & 0x1FF);
    }

    private static long pointerPTE(final long table) {
        return ((table >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | R5.PTE_V_MASK;
    }

    private static long leafPTE(final long page) {
        return ((page >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS)
            | R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK | R5.PTE_X_MASK
            | R5.PTE_A_MASK | R5.PTE_D_MASK;
    }
}
