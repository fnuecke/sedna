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

public final class DivRemWTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final long ZERO_LOW_WORD_DIVISOR = 0x100000000L;
    private static final long DIVIDEND = 0x123456789ABCDEF0L;
    private static final long DIVIDEND_LOW_WORD = 0xFFFFFFFF9ABCDEF0L; // sign-extended (int) DIVIDEND

    // Low words are MIN_VALUE / -1 (the signed overflow case), upper bits junk.
    private static final long NON_CANONICAL_MIN = 0x0000000080000000L;
    private static final long NON_CANONICAL_MINUS_ONE = 0x00000000FFFFFFFFL;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));
        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);
    }

    @Test
    public void divwByZeroLowWordDivisor() throws MemoryAccessException {
        assertEquals(-1, run(divw(3, 1, 2), 7, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void divuwByZeroLowWordDivisor() throws MemoryAccessException {
        assertEquals(-1, run(divuw(3, 1, 2), 7, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void remwByZeroLowWordDivisor() throws MemoryAccessException {
        assertEquals(DIVIDEND_LOW_WORD, run(remw(3, 1, 2), DIVIDEND, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void remuwByZeroLowWordDivisor() throws MemoryAccessException {
        assertEquals(DIVIDEND_LOW_WORD, run(remuw(3, 1, 2), DIVIDEND, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void divwOverflowWithNonCanonicalOperands() throws MemoryAccessException {
        assertEquals(0xFFFFFFFF80000000L, run(divw(3, 1, 2), NON_CANONICAL_MIN, NON_CANONICAL_MINUS_ONE));
    }

    @Test
    public void remwOverflowWithNonCanonicalOperands() throws MemoryAccessException {
        assertEquals(0, run(remw(3, 1, 2), NON_CANONICAL_MIN, NON_CANONICAL_MINUS_ONE));
    }

    @Test
    public void divwIgnoresUpperOperandBits() throws MemoryAccessException {
        assertEquals(3, run(divw(3, 1, 2), 0xDEADBEEF00000007L, 0xCAFEBABE00000002L));
    }

    private long run(final int instruction, final long rs1, final long rs2) throws MemoryAccessException {
        memoryMap.store(PHYSICAL_MEMORY_START, instruction, Sizes.SIZE_32_LOG2);

        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = rs1;
        registers[2] = rs2;
        registers[3] = 0;

        cpu.getDebugInterface().setProgramCounter(PHYSICAL_MEMORY_START);
        cpu.getDebugInterface().step();

        return registers[3];
    }
}
