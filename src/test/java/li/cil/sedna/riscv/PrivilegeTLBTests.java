package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static final int CSR_SATP = 0x180;
    private static final int CSR_MSTATUS = 0x300;
    private static final int CSR_MEPC = 0x341;
    private static final int CSR_MTVEC = 0x305;

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
        writeCSR(CSR_MTVEC, CODE);
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
        writeCSR(CSR_SATP, R5.SATP_MODE_SV39 | (ROOT_TABLE >>> R5.PAGE_ADDRESS_SHIFT));
        setCSRBits(CSR_MSTATUS, (long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);
        writeCSR(CSR_MEPC, CODE);
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

    private static final int MRET = (0b0011000 << 25) | (0b00010 << 20) | 0b1110011;
    private static final int ECALL = 0b1110011;

    private static int ld(final int rd, final int rs1, final int imm) {
        return (imm << 20) | (rs1 << 15) | (0b011 << 12) | (rd << 7) | 0b0000011;
    }

    private static int csrrw(final int rd, final int csr, final int rs1) {
        return (csr << 20) | (rs1 << 15) | (0b001 << 12) | (rd << 7) | 0b1110011;
    }

    private static int sfenceVma(final int rs1) {
        return (0b0001001 << 25) | (rs1 << 15) | 0b1110011;
    }

    private static int csrrs(final int rd, final int csr, final int rs1) {
        return (csr << 20) | (rs1 << 15) | (0b010 << 12) | (rd << 7) | 0b1110011;
    }
}
