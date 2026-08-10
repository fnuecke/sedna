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

public final class FusedMultiplyAddTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final long ONE = 0x3ff0000000000000L;
    private static final long ONE_EPS = 0x3c30000000000000L; // 2^-60
    private static final long TWO = 0x4000000000000000L;
    private static final long THREE = 0x4008000000000000L;
    private static final long FOUR = 0x4010000000000000L;
    private static final long SIGN = 0x8000000000000000L;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);

        // csrrs x0, mstatus, x1 with x1 selecting FS=Initial.
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT;
        execute(csrrs(0, R5.CSR_MSTATUS, 1));
    }

    @Test
    public void fnmaddComputesNegatedProductMinusAddend() {
        assertEquals(0xc024000000000000L, fnmadd(TWO, THREE, FOUR, R5.FCSR_FRM_RNE)); // -(2*3)-4 = -10.0
    }

    @Test
    public void fnmsubComputesNegatedProductPlusAddend() {
        assertEquals(0xc000000000000000L, fnmsub(TWO, THREE, FOUR, R5.FCSR_FRM_RNE)); // -(2*3)+4 = -2.0
    }

    @Test
    public void fnmaddRoundsTheNegatedResultDirectionally() {
        // -(1*1)-2^-60 is exactly -(1 + 2^-60): toward +inf that is -1.0, toward -inf the next
        // double below it. Negating a result rounded the other way gives the opposite pairing.
        assertEquals(ONE | SIGN, fnmadd(ONE, ONE, ONE_EPS, R5.FCSR_FRM_RUP));
        assertEquals((ONE + 1) | SIGN, fnmadd(ONE, ONE, ONE_EPS, R5.FCSR_FRM_RDN));
    }

    @Test
    public void fnmaddExactZeroIsPositiveUnderRoundToNearest() {
        // -(1*1)-(-1.0) cancels exactly; IEEE requires +0 under RNE, which negate-after-round
        // turned into -0.
        assertEquals(0L, fnmadd(ONE, ONE, ONE | SIGN, R5.FCSR_FRM_RNE));
    }

    @Test
    public void fnmsubExactZeroSignFollowsTheRoundingMode() {
        assertEquals(0L, fnmsub(ONE, ONE, ONE, R5.FCSR_FRM_RNE));
        assertEquals(SIGN, fnmsub(ONE, ONE, ONE, R5.FCSR_FRM_RDN));
    }

    private long fnmadd(final long a, final long b, final long c, final int rm) {
        return run(fnmaddD(3, 0, 1, 2, rm), a, b, c);
    }

    private long fnmsub(final long a, final long b, final long c, final int rm) {
        return run(fnmsubD(3, 0, 1, 2, rm), a, b, c);
    }

    private long run(final int instruction, final long a, final long b, final long c) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();

        registers[1] = a;
        execute(fmvDX(0, 1));
        registers[1] = b;
        execute(fmvDX(1, 1));
        registers[1] = c;
        execute(fmvDX(2, 1));

        execute(instruction);

        registers[4] = 0;
        execute(fmvXD(4, 3));
        return registers[4];
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
