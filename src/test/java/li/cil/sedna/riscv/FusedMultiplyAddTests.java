package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class FusedMultiplyAddTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long ONE = 0x3ff0000000000000L;
    private static final long ONE_EPS = 0x3c30000000000000L; // 2^-60
    private static final long TWO = 0x4000000000000000L;
    private static final long THREE = 0x4008000000000000L;
    private static final long FOUR = 0x4010000000000000L;
    private static final long SIGN = 0x8000000000000000L;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.setCSRBits(R5CSR.MSTATUS, (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT);
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
        final long[] registers = vm.registers();

        registers[1] = a;
        vm.execute(fmvDX(0, 1));
        registers[1] = b;
        vm.execute(fmvDX(1, 1));
        registers[1] = c;
        vm.execute(fmvDX(2, 1));

        vm.execute(instruction);

        registers[4] = 0;
        vm.execute(fmvXD(4, 3));
        return registers[4];
    }
}
