package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class DivRemWTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long ZERO_LOW_WORD_DIVISOR = 0x100000000L;
    private static final long DIVIDEND = 0x123456789ABCDEF0L;
    private static final long DIVIDEND_LOW_WORD = 0xFFFFFFFF9ABCDEF0L; // sign-extended (int) DIVIDEND

    // Low words are MIN_VALUE / -1 (the signed overflow case), upper bits junk.
    private static final long NON_CANONICAL_MIN = 0x0000000080000000L;
    private static final long NON_CANONICAL_MINUS_ONE = 0x00000000FFFFFFFFL;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
    }

    @Test
    public void divwByZeroLowWordDivisor() {
        assertEquals(-1, run(divw(3, 1, 2), 7, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void divuwByZeroLowWordDivisor() {
        assertEquals(-1, run(divuw(3, 1, 2), 7, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void remwByZeroLowWordDivisor() {
        assertEquals(DIVIDEND_LOW_WORD, run(remw(3, 1, 2), DIVIDEND, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void remuwByZeroLowWordDivisor() {
        assertEquals(DIVIDEND_LOW_WORD, run(remuw(3, 1, 2), DIVIDEND, ZERO_LOW_WORD_DIVISOR));
    }

    @Test
    public void divwOverflowWithNonCanonicalOperands() {
        assertEquals(0xFFFFFFFF80000000L, run(divw(3, 1, 2), NON_CANONICAL_MIN, NON_CANONICAL_MINUS_ONE));
    }

    @Test
    public void remwOverflowWithNonCanonicalOperands() {
        assertEquals(0, run(remw(3, 1, 2), NON_CANONICAL_MIN, NON_CANONICAL_MINUS_ONE));
    }

    @Test
    public void divwIgnoresUpperOperandBits() {
        assertEquals(3, run(divw(3, 1, 2), 0xDEADBEEF00000007L, 0xCAFEBABE00000002L));
    }

    private long run(final int instruction, final long rs1, final long rs2) {
        final long[] registers = vm.registers();
        registers[1] = rs1;
        registers[2] = rs2;
        registers[3] = 0;

        vm.execute(instruction);

        return registers[3];
    }
}
