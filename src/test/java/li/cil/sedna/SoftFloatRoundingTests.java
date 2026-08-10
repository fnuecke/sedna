package li.cil.sedna;

import li.cil.sedna.utils.SoftDouble;
import li.cil.sedna.utils.SoftFloat;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static li.cil.sedna.utils.SoftFloat.RM_RDN;
import static li.cil.sedna.utils.SoftFloat.RM_RNE;
import static li.cil.sedna.utils.SoftFloat.RM_RTZ;
import static li.cil.sedna.utils.SoftFloat.RM_RUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class SoftFloatRoundingTests {
    private static final int F_ONE = 0x3f800000; // 1.0f
    private static final int F_ONE_EPS = 0x30800000; // 2^-30, half-ulp-ish increment for 1.0f
    private static final int F_MAX = 0x7f7fffff;
    private static final int F_INF = 0x7f800000;
    private static final int F_TWO = 0x40000000;
    private static final int F_THREE_HALVES = 0x3fc00000; // 1.5f

    private static final long D_ONE = 0x3ff0000000000000L; // 1.0
    private static final long D_ONE_EPS = 0x3c30000000000000L; // 2^-60
    private static final long D_MAX = 0x7fefffffffffffffL;
    private static final long D_INF = 0x7ff0000000000000L;
    private static final long D_TWO = 0x4000000000000000L;
    private static final long D_THREE_HALVES = 0x3ff8000000000000L; // 1.5

    private static final int SIGN_F = 0x80000000;
    private static final long SIGN_D = 0x8000000000000000L;

    @Test
    public void directedRoundingOfInexactFloatSum() {
        final SoftFloat fpu = new SoftFloat();
        // 1.0 + 2^-30 is inexact; toward -inf it must truncate, toward +inf it must round up.
        assertEquals(F_ONE, fpu.add(F_ONE, F_ONE_EPS, RM_RDN));
        assertEquals(F_ONE + 1, fpu.add(F_ONE, F_ONE_EPS, RM_RUP));
        assertEquals(F_ONE, fpu.add(F_ONE, F_ONE_EPS, RM_RTZ));
        // Mirrored for a negative result.
        assertEquals((F_ONE + 1) | SIGN_F, fpu.add(F_ONE | SIGN_F, F_ONE_EPS | SIGN_F, RM_RDN));
        assertEquals(F_ONE | SIGN_F, fpu.add(F_ONE | SIGN_F, F_ONE_EPS | SIGN_F, RM_RUP));
    }

    @Test
    public void directedRoundingOfInexactDoubleSum() {
        final SoftDouble fpu = new SoftDouble();
        assertEquals(D_ONE, fpu.add(D_ONE, D_ONE_EPS, RM_RDN));
        assertEquals(D_ONE + 1, fpu.add(D_ONE, D_ONE_EPS, RM_RUP));
        assertEquals(D_ONE, fpu.add(D_ONE, D_ONE_EPS, RM_RTZ));
        assertEquals((D_ONE + 1) | SIGN_D, fpu.add(D_ONE | SIGN_D, D_ONE_EPS | SIGN_D, RM_RDN));
        assertEquals(D_ONE | SIGN_D, fpu.add(D_ONE | SIGN_D, D_ONE_EPS | SIGN_D, RM_RUP));
    }

    @Test
    public void directedRoundingSaturatesOverflowTowardTheRoundingDirection() {
        final SoftFloat fpu32 = new SoftFloat();
        // Overflow may only produce infinity when rounding toward it; otherwise it saturates at MAX.
        assertEquals(F_MAX, fpu32.mul(F_MAX, F_TWO, RM_RDN));
        assertEquals(F_INF, fpu32.mul(F_MAX, F_TWO, RM_RUP));
        assertEquals(F_MAX, fpu32.mul(F_MAX, F_TWO, RM_RTZ));
        assertEquals(F_INF, fpu32.mul(F_MAX, F_TWO, RM_RNE));
        assertEquals(F_INF | SIGN_F, fpu32.mul(F_MAX | SIGN_F, F_TWO, RM_RDN));
        assertEquals(F_MAX | SIGN_F, fpu32.mul(F_MAX | SIGN_F, F_TWO, RM_RUP));

        final SoftDouble fpu64 = new SoftDouble();
        assertEquals(D_MAX, fpu64.mul(D_MAX, D_TWO, RM_RDN));
        assertEquals(D_INF, fpu64.mul(D_MAX, D_TWO, RM_RUP));
        assertEquals(D_INF | SIGN_D, fpu64.mul(D_MAX | SIGN_D, D_TWO, RM_RDN));
        assertEquals(D_MAX | SIGN_D, fpu64.mul(D_MAX | SIGN_D, D_TWO, RM_RUP));
    }

    @Test
    public void directedRoundingOfConversionsToInteger() {
        final SoftFloat fpu32 = new SoftFloat();
        assertEquals(1, fpu32.floatToInt(F_THREE_HALVES, RM_RDN));
        assertEquals(2, fpu32.floatToInt(F_THREE_HALVES, RM_RUP));
        assertEquals(-2, fpu32.floatToInt(F_THREE_HALVES | SIGN_F, RM_RDN));
        assertEquals(-1, fpu32.floatToInt(F_THREE_HALVES | SIGN_F, RM_RUP));

        final SoftDouble fpu64 = new SoftDouble();
        assertEquals(1, fpu64.doubleToLong(D_THREE_HALVES, RM_RDN));
        assertEquals(2, fpu64.doubleToLong(D_THREE_HALVES, RM_RUP));
        assertEquals(-2, fpu64.doubleToLong(D_THREE_HALVES | SIGN_D, RM_RDN));
        assertEquals(-1, fpu64.doubleToLong(D_THREE_HALVES | SIGN_D, RM_RUP));
    }

    @Test
    public void fusedMultiplyAddCarriesAcrossMantissaWords() {
        final SoftFloat fpu = new SoftFloat();
        // Off by 1 ulp when the low-word carry was computed with a signed comparison.
        assertEquals(0x41c27c72, fpu.muladd(0x674e41bb, 0x19f16400, 0xb4e0fb16, RM_RNE));
    }

    @Test
    public void fusedMultiplyAddMatchesJavaFmaOnRandomFloats() {
        final SoftFloat fpu = new SoftFloat();
        final Random random = new Random(12345);
        for (int i = 0; i < 100_000; i++) {
            final int a = random.nextInt(), b = random.nextInt(), c = random.nextInt();
            final float expected = Math.fma(Float.intBitsToFloat(a), Float.intBitsToFloat(b), Float.intBitsToFloat(c));
            final int actual = fpu.muladd(a, b, c, RM_RNE);
            if (Float.isNaN(expected)) {
                assertTrue(SoftFloat.isNaN(actual), () -> String.format("fma(%08x, %08x, %08x) must be NaN, was %08x", a, b, c, actual));
            } else {
                assertEquals(Float.floatToRawIntBits(expected), actual,
                    () -> String.format("fma(%08x, %08x, %08x)", a, b, c));
            }
        }
    }

    @Test
    public void fusedMultiplyAddMatchesJavaFmaOnRandomDoubles() {
        final SoftDouble fpu = new SoftDouble();
        final Random random = new Random(23456);
        for (int i = 0; i < 100_000; i++) {
            final long a = random.nextLong(), b = random.nextLong(), c = random.nextLong();
            assertFmaMatchesJava(fpu, a, b, c);
        }
    }

    @Test
    public void fusedMultiplyAddMatchesJavaFmaOnCorrelatedDoubles() {
        final SoftDouble fpu = new SoftDouble();
        final Random random = new Random(34567);
        for (int i = 0; i < 100_000; i++) {
            final int exponentA = 900 + random.nextInt(250);
            final int exponentB = 900 + random.nextInt(250);
            final int exponentC = Math.max(1, Math.min(2046, exponentA + exponentB - 1023 + random.nextInt(261) - 130));
            final long a = makeDouble(random, exponentA);
            final long b = makeDouble(random, exponentB);
            final long c = makeDouble(random, exponentC);
            assertFmaMatchesJava(fpu, a, b, c);
        }
    }

    private static void assertFmaMatchesJava(final SoftDouble fpu, final long a, final long b, final long c) {
        final double expected = Math.fma(Double.longBitsToDouble(a), Double.longBitsToDouble(b), Double.longBitsToDouble(c));
        final long actual = fpu.muladd(a, b, c, RM_RNE);
        if (Double.isNaN(expected)) {
            assertTrue(SoftDouble.isNaN(actual), () -> String.format("fma(%016x, %016x, %016x) must be NaN, was %016x", a, b, c, actual));
        } else {
            assertEquals(Double.doubleToRawLongBits(expected), actual,
                () -> String.format("fma(%016x, %016x, %016x)", a, b, c));
        }
    }

    private static long makeDouble(final Random random, final int exponent) {
        return ((long) (random.nextInt(2)) << 63) | ((long) exponent << 52) | (random.nextLong() >>> 12);
    }
}
