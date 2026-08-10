package li.cil.sedna.riscv;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TLBSerializationTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 8 * 1024 * 1024;

    private static final long PROGRAM = PHYSICAL_MEMORY_START;

    private static final long ROOT_TABLE_A = PHYSICAL_MEMORY_START + 0x1000;
    private static final long LEVEL1_TABLE_A = PHYSICAL_MEMORY_START + 0x2000;
    private static final long ROOT_TABLE_B = PHYSICAL_MEMORY_START + 0x3000;
    private static final long LEVEL1_TABLE_B = PHYSICAL_MEMORY_START + 0x4000;

    private static final long TARGET_A = PHYSICAL_MEMORY_START + 0x200000;
    private static final long TARGET_B = PHYSICAL_MEMORY_START + 0x400000;

    private static final long VIRTUAL_ADDRESS = 0x1000;

    private static final long MARKER_A = 0x1111222233334444L;
    private static final long MARKER_B = 0x5555666677778888L;

    private static final long MSTATUS_TRANSLATE_DATA_AS_SUPERVISOR =
        R5.STATUS_MPRV_MASK | ((long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);

    private MemoryMap memoryMap;

    @BeforeAll
    public static void setupSedna() {
        Sedna.initialize();
    }

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        // csrrw x0, satp, x1     ; switch address space to the one described by x1
        // csrrs x0, mstatus, x2  ; translate data accesses as supervisor
        // ld    x4, 0(x3)        ; x4 = *x3, translated
        memoryMap.store(PROGRAM, csrrw(0, R5.CSR_SATP, 1), Sizes.SIZE_32_LOG2);
        memoryMap.store(PROGRAM + 4, csrrs(0, R5.CSR_MSTATUS, 2), Sizes.SIZE_32_LOG2);
        memoryMap.store(PROGRAM + 8, ld(4, 3, 0), Sizes.SIZE_32_LOG2);

        mapMegapage(ROOT_TABLE_A, LEVEL1_TABLE_A, TARGET_A);
        mapMegapage(ROOT_TABLE_B, LEVEL1_TABLE_B, TARGET_B);

        memoryMap.store(TARGET_A + VIRTUAL_ADDRESS, MARKER_A, Sizes.SIZE_64_LOG2);
        memoryMap.store(TARGET_B + VIRTUAL_ADDRESS, MARKER_B, Sizes.SIZE_64_LOG2);
    }

    @Test
    public void theTwoAddressSpacesResolveDifferently() {
        assertEquals(MARKER_A, loadThroughAddressSpace(newCPU(), ROOT_TABLE_A));
        assertEquals(MARKER_B, loadThroughAddressSpace(newCPU(), ROOT_TABLE_B));
    }

    @Test
    public void deserializationDiscardsStaleTLBEntries() {
        // Run a CPU in address space A, leaving a TLB entry for VIRTUAL_ADDRESS behind.
        final R5CPU cpu = newCPU();
        assertEquals(MARKER_A, loadThroughAddressSpace(cpu, ROOT_TABLE_A));

        // Capture the state of an otherwise identical CPU running in address space B.
        final R5CPU source = newCPU();
        assertEquals(MARKER_B, loadThroughAddressSpace(source, ROOT_TABLE_B));
        final ByteBuffer serialized = BinarySerialization.serialize(source, R5CPU.class);

        // Restore that state into the CPU holding the address space A entry.
        BinarySerialization.deserialize(serialized, R5CPU.class, cpu);

        // The restored state selects address space B, so the load must see TARGET_B.
        assertEquals(MARKER_B, repeatLoad(cpu));
    }

    private R5CPU newCPU() {
        final R5CPU cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PROGRAM);
        cpu.setXLEN(R5.XLEN_64);
        return cpu;
    }

    private static long loadThroughAddressSpace(final R5CPU cpu, final long rootTable) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = R5.SATP_MODE_SV39 | (rootTable >>> R5.PAGE_ADDRESS_SHIFT);
        registers[2] = MSTATUS_TRANSLATE_DATA_AS_SUPERVISOR;
        registers[3] = VIRTUAL_ADDRESS;
        registers[4] = 0;

        cpu.getDebugInterface().setProgramCounter(PROGRAM);
        cpu.getDebugInterface().step();
        cpu.getDebugInterface().step();
        cpu.getDebugInterface().step();

        return registers[4];
    }

    private static long repeatLoad(final R5CPU cpu) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[3] = VIRTUAL_ADDRESS;
        registers[4] = 0;

        cpu.getDebugInterface().setProgramCounter(PROGRAM + 8);
        cpu.getDebugInterface().step();

        return registers[4];
    }

    private void mapMegapage(final long rootTable, final long level1Table, final long target) throws MemoryAccessException {
        memoryMap.store(rootTable, pointerPTE(level1Table), Sizes.SIZE_64_LOG2);
        memoryMap.store(level1Table, leafPTE(target), Sizes.SIZE_64_LOG2);
    }

    private static long pointerPTE(final long tableAddress) {
        return ((tableAddress >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | R5.PTE_V_MASK;
    }

    private static long leafPTE(final long physicalAddress) {
        return ((physicalAddress >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS)
            | R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK | R5.PTE_A_MASK | R5.PTE_D_MASK;
    }
}
