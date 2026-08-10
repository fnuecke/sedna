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

public final class CanonicalAddressTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 16 * 1024;

    private static final long ROOT_PAGE_TABLE = PHYSICAL_MEMORY_START + 0x1000;
    private static final long DATA_ADDRESS = PHYSICAL_MEMORY_START + 0x100;
    private static final long SENTINEL = 0x1122334455667788L;

    // The canonical data address with junk in the upper bits: truncated it hits the identity
    // mapping below, so before the canonical check it would read successfully.
    private static final long NON_CANONICAL_DATA_ADDRESS = 0x0100000000000000L | DATA_ADDRESS;

    private static final long TRAP_VECTOR = PHYSICAL_MEMORY_START + 0x800;

    // U clear so S-mode can access without SUM.
    private static final long PTE_FLAGS = R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK
        | R5.PTE_X_MASK | R5.PTE_A_MASK | R5.PTE_D_MASK;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);

        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = TRAP_VECTOR;
        execute(csrrw(0, R5CSR.MTVEC, 1));

        memoryMap.store(DATA_ADDRESS, SENTINEL, Sizes.SIZE_64_LOG2);
    }

    @Test
    public void nonCanonicalLoadFaultsUnderSv39() throws MemoryAccessException {
        enterSupervisorModeWithSv39();
        assertCanonicalLoadWorks();
        assertNonCanonicalAccessFaults(R5.EXCEPTION_LOAD_PAGE_FAULT, ld(6, 5, 0));
    }

    @Test
    public void nonCanonicalLoadFaultsUnderSv48() throws MemoryAccessException {
        enterSupervisorModeWithSv48();
        assertCanonicalLoadWorks();
        assertNonCanonicalAccessFaults(R5.EXCEPTION_LOAD_PAGE_FAULT, ld(6, 5, 0));
    }

    @Test
    public void nonCanonicalStoreFaultsUnderSv39() throws MemoryAccessException {
        enterSupervisorModeWithSv39();
        assertNonCanonicalAccessFaults(R5.EXCEPTION_STORE_PAGE_FAULT, sd(6, 5, 0));
        assertEquals(SENTINEL, memoryMap.load(DATA_ADDRESS, Sizes.SIZE_64_LOG2), "the aliased store must not reach memory");
    }

    @Test
    public void nonCanonicalFetchFaultsUnderSv39() throws MemoryAccessException {
        enterSupervisorModeWithSv39();

        cpu.getDebugInterface().setProgramCounter(NON_CANONICAL_DATA_ADDRESS);
        cpu.getDebugInterface().step();

        assertEquals(R5.EXCEPTION_FETCH_PAGE_FAULT, readCSR(R5CSR.MCAUSE));
        assertEquals(NON_CANONICAL_DATA_ADDRESS, readCSR(R5CSR.MTVAL));
    }

    /**
     * Identity-maps [0x80000000, 0xC0000000) with a single 1 GiB superpage: VPN[2] of
     * 0x80000000 is 2, so leaf PTE index 2 in the root table.
     */
    private void enterSupervisorModeWithSv39() throws MemoryAccessException {
        memoryMap.store(ROOT_PAGE_TABLE + 2 * 8,
            ((PHYSICAL_MEMORY_START >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | PTE_FLAGS, Sizes.SIZE_64_LOG2);
        enterSupervisorMode(R5.SATP_MODE_SV39);
    }

    /**
     * Identity-maps [0, 512G) with a single leaf in root PTE index 0 (VPN[3] of all our
     * addresses is 0).
     */
    private void enterSupervisorModeWithSv48() throws MemoryAccessException {
        memoryMap.store(ROOT_PAGE_TABLE, PTE_FLAGS, Sizes.SIZE_64_LOG2);
        enterSupervisorMode(R5.SATP_MODE_SV48);
    }

    private void enterSupervisorMode(final long satpMode) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = satpMode | (ROOT_PAGE_TABLE >>> R5.PAGE_ADDRESS_SHIFT);
        execute(csrrw(0, R5CSR.SATP, 1));

        registers[1] = R5.STATUS_SPP_MASK; // SPP = S.
        execute(csrrw(0, R5CSR.MSTATUS, 1));
        execute(SRET);
    }

    private void assertCanonicalLoadWorks() {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[5] = DATA_ADDRESS;
        registers[6] = 0;
        execute(ld(6, 5, 0));
        assertEquals(SENTINEL, registers[6], "the canonical address must translate");
    }

    private void assertNonCanonicalAccessFaults(final long cause, final int instruction) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[5] = NON_CANONICAL_DATA_ADDRESS;
        registers[6] = 0;
        execute(instruction);

        // The fault trapped to M-mode, where mcause/mtval are readable again.
        assertEquals(cause, readCSR(R5CSR.MCAUSE));
        assertEquals(NON_CANONICAL_DATA_ADDRESS, readCSR(R5CSR.MTVAL));
    }

    private long readCSR(final int csr) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[2] = 0;
        execute(csrrs(2, csr, 0));
        return registers[2];
    }

    private void execute(final int instruction) {
        try {
            memoryMap.store(PHYSICAL_MEMORY_START, instruction, Sizes.SIZE_32_LOG2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }

        cpu.getDebugInterface().setProgramCounter(PHYSICAL_MEMORY_START);
        cpu.getDebugInterface().step();
    }
}
