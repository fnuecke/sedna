package li.cil.sedna.utils;

import java.math.BigInteger;

/**
 * Software implementation of double precision floating point operations according to IEEE754.
 * <p>
 * Unlike Java, this supports different rounding modes and exposes exceptions via a flags register.
 */
public final class SoftDouble {
    public static final int FLAG_INEXACT = 0b1; // Inexact.
    public static final int FLAG_UNDERFLOW = 0b1 << 1; // Underflow.
    public static final int FLAG_OVERFLOW = 0b1 << 2; // Overflow.
    public static final int FLAG_DIV_ZERO = 0b1 << 3; // Division by zero.
    public static final int FLAG_INVALID = 0b1 << 4; // Invalid operation.

    // NB: The flag bits must match the the RISC-V ones.
    public static final byte RM_RNE = 0b000; // Round to nearest, ties to even.
    public static final byte RM_RTZ = 0b001; // Round towards zero.
    public static final byte RM_RDN = 0b010; // Round down (towards negative infinity).
    public static final byte RM_RUP = 0b011; // Round up (towards positive infinity).
    public static final byte RM_RMM = 0b100; // Round to nearest, ties to max magnitude.

    public static final int FCLASS_NEGINF = 1; // Negative infinity.
    public static final int FCLASS_NEGNORM = 1 << 1; // Negative normal number.
    public static final int FCLASS_NEGSUBN = 1 << 2; // Negative subnormal number.
    public static final int FCLASS_NEGZERO = 1 << 3; // Negative zero.
    public static final int FCLASS_POSZERO = 1 << 4; // Positive zero.
    public static final int FCLASS_POSSUBN = 1 << 5; // Positive subnormal number.
    public static final int FCLASS_POSNORM = 1 << 6; // Positive normal number.
    public static final int FCLASS_POSINF = 1 << 7; // Positive infinity.
    public static final int FCLASS_SNAN = 1 << 8; // Signaling NaN.
    public static final int FCLASS_QNAN = 1 << 9; // Quiet NaN.

    public static final int SIZE = Double.SIZE;
    public static final int EXPONENT_SIZE = 11;
    public static final int MANTISSA_SIZE = 52;

    public static final long SIGN_MASK = 1L << (SIZE - 1);
    public static final int EXPONENT_MASK = (1 << EXPONENT_SIZE) - 1;
    public static final long MANTISSA_MASK = (1L << MANTISSA_SIZE) - 1;
    public static final int BIAS = EXPONENT_MASK / 2;

    private static final int INTERNAL_MANTISSA_SIZE = SIZE - 2;
    private static final int RND_SIZE = INTERNAL_MANTISSA_SIZE - MANTISSA_SIZE;
    private static final long MANTISSA_IMPLICIT_BIT = 1L << MANTISSA_SIZE;

    private static final long QUIET_NAN_MASK = 1L << (MANTISSA_SIZE - 1);
    private static final long QUIET_NAN = (Integer.toUnsignedLong(EXPONENT_MASK) << MANTISSA_SIZE) | QUIET_NAN_MASK;

    public SoftFloat.Flags flags;

    public SoftDouble() {
        flags = new SoftFloat.Flags();
    }

    public SoftDouble(final SoftFloat.Flags flags) {
        this.flags = flags;
    }

    public static long nan() {
        return QUIET_NAN;
    }

    public static boolean isNaN(final long a) {
        return ((a >>> MANTISSA_SIZE) & EXPONENT_MASK) == EXPONENT_MASK
               && (a & MANTISSA_MASK) != 0;
    }

    public static boolean isInfinity(final long a) {
        return ((a >>> MANTISSA_SIZE) & EXPONENT_MASK) == EXPONENT_MASK
               && (a & MANTISSA_MASK) == 0;
    }

    public int sign(final long a) {
        if (isNaN(a)) {
            if (isSignalingNaN(a)) {
                flags.raise(FLAG_INVALID);
            }
            return 0;
        }
        return (a & SIGN_MASK) == 0 ? 1 : -1;
    }

    public long neg(final long a) {
        if (isNaN(a)) {
            if (isSignalingNaN(a)) {
                flags.raise(FLAG_INVALID);
            }
            return nan();
        }
        return a ^ SIGN_MASK;
    }

    public long add(long a, long b, final int rm) {
        // Make sure a is the larger of the two. This way we can unify NaN and Infinity detection.
        if ((a & ~SIGN_MASK) < (b & ~SIGN_MASK)) {
            final long tmp = a;
            a = b;
            b = tmp;
        }

        final int signA = getSign(a);
        final int signB = getSign(b);
        int exponentA = getExponent(a);
        int exponentB = getExponent(b);
        long mantissaA = getMantissa(a) << 3;
        long mantissaB = getMantissa(b) << 3;

        if (exponentA == EXPONENT_MASK) { // NaN or Infinity
            if (mantissaA != 0) { // NaN
                if (isSignalingNaN(a) || isSignalingNaN(b)) {
                    flags.raise(FLAG_INVALID);
                }
                return nan();
            } else if (exponentB == EXPONENT_MASK  // -inf + inf. b cannot be NaN if a is not NaN because we sorted the
                       && signA != signB) {        // two up top, and NaN > Infinity when excluding the sign bit.
                flags.raise(FLAG_INVALID);
                return nan();
            } else { // Infinity
                return a;
            }
        }

        if (exponentA == 0) { // subnormal or zero
            exponentA = 1;
        } else { // normal, make implicit leading 1 explicit
            mantissaA |= MANTISSA_IMPLICIT_BIT << 3;
        }

        if (exponentB == 0) { // subnormal or zero
            exponentB = 1;
        } else { // normal, make implicit leading 1 explicit
            mantissaB |= MANTISSA_IMPLICIT_BIT << 3;
        }

        mantissaB = shiftRightAndJam(mantissaB, exponentA - exponentB);
        final int sign;
        final long mantissa;
        if (signA == signB) {
            mantissa = mantissaA + mantissaB;
            sign = signA;
        } else {
            mantissa = mantissaA - mantissaB;
            if (mantissa == 0) {
                sign = rm == RM_RDN ? 1 : 0;
            } else {
                sign = signA;
            }
        }

        final int exponent = exponentA + (RND_SIZE - 3);

        return normalize(sign, exponent, mantissa, rm, flags);
    }

    public long sub(final long a, final long b, final int rm) {
        return add(a, neg(b), rm);
    }

    public long mul(final long a, final long b, final int rm) {
        final int signA = getSign(a);
        final int signB = getSign(b);
        int exponentA = getExponent(a);
        int exponentB = getExponent(b);
        long mantissaA = getMantissa(a);
        long mantissaB = getMantissa(b);

        final int sign = signA ^ signB;

        if (exponentA == EXPONENT_MASK || exponentB == EXPONENT_MASK) {
            if (isNaN(a) || isNaN(b)) { // nan * b || a * nan
                if (isSignalingNaN(a) || isSignalingNaN(b)) {
                    flags.raise(FLAG_INVALID);
                }
                return nan();
            } else {
                // inf * b || a * inf
                if ((exponentA == EXPONENT_MASK && (exponentB == 0 && mantissaB == 0)) ||
                    (exponentB == EXPONENT_MASK && (exponentA == 0 && mantissaA == 0))) {
                    // inf * 0 || 0 * inf
                    flags.raise(FLAG_INVALID);
                    return nan();
                } else {
                    return pack(sign, EXPONENT_MASK, 0);
                }
            }
        }

        if (exponentA == 0) {
            if (mantissaA == 0) { // 0 * b
                return pack(sign, 0, 0);
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaA);
                exponentA = exponentAndMantissa.a;
                mantissaA = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaA |= MANTISSA_IMPLICIT_BIT;
        }
        if (exponentB == 0) {
            if (mantissaB == 0) { // a * 0
                return pack(sign, 0, 0);
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaB);
                exponentB = exponentAndMantissa.a;
                mantissaB = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaB |= MANTISSA_IMPLICIT_BIT;
        }

        final int exponent = exponentA + exponentB - (1 << (EXPONENT_SIZE - 1)) + 2;

        final long2 mantissaHighAndLow = multiply(mantissaA << RND_SIZE, mantissaB << (RND_SIZE + 1));
        final long mantissa = mantissaHighAndLow.a | (mantissaHighAndLow.b != 0 ? 1 : 0);

        return normalize(sign, exponent, mantissa, rm, flags);
    }

    public long muladd(final long a, final long b, final long c, final int rm) {
        final int signA = getSign(a);
        final int signB = getSign(b);
        int signC = getSign(c);
        int exponentA = getExponent(a);
        int exponentB = getExponent(b);
        int exponentC = getExponent(c);
        long mantissaA = getMantissa(a);
        long mantissaB = getMantissa(b);
        long mantissaC = getMantissa(c);

        int sign = signA ^ signB;

        if (exponentA == EXPONENT_MASK || exponentB == EXPONENT_MASK || exponentC == EXPONENT_MASK) {
            if (isNaN(a) || isNaN(b) || isNaN(c)) { // nan * b + c || a * nan + c || a * b + nan
                if (isSignalingNaN(a) || isSignalingNaN(b) || isSignalingNaN(c)) {
                    flags.raise(FLAG_INVALID);
                }
                return nan();
            } else {
                // inf * b + c || a * inf + c || a * b + inf
                if ((exponentA == EXPONENT_MASK && (exponentB == 0 && mantissaB == 0)) || // inf * 0 + c
                    (exponentB == EXPONENT_MASK && (exponentA == 0 && mantissaA == 0)) || // 0 * inf + c
                    (exponentA == EXPONENT_MASK || exponentB == EXPONENT_MASK) && // a = inf || b = inf
                    (exponentC == EXPONENT_MASK && sign != signC)) { // c = inf && sign(c) != sign(a*b)
                    flags.raise(FLAG_INVALID);
                    return nan();
                } else if (exponentC == EXPONENT_MASK) {
                    return pack(signC, EXPONENT_MASK, 0);
                } else {
                    return pack(sign, EXPONENT_MASK, 0);
                }
            }
        }

        if (exponentA == 0) {
            if (mantissaA == 0) { // 0 * b + c
                if (exponentC == 0 && mantissaC == 0) { // 0 * b + 0
                    if (signC != sign) {
                        return pack(rm == RM_RDN ? 1 : 0, 0, 0);
                    } else {
                        return pack(sign, 0, 0);
                    }
                } else { // 0 * b + c, c != 0
                    return c;
                }
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaA);
                exponentA = exponentAndMantissa.a;
                mantissaA = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaA |= MANTISSA_IMPLICIT_BIT;
        }

        if (exponentB == 0) {
            if (mantissaB == 0) { // a * 0 + c
                if (exponentC == 0 && mantissaC == 0) { // a * 0 + 0
                    if (signC != sign) {
                        return pack(rm == RM_RDN ? 1 : 0, 0, 0);
                    } else {
                        return pack(sign, 0, 0);
                    }
                } else { // a * 0 + c, c != 0
                    return c;
                }
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaB);
                exponentB = exponentAndMantissa.a;
                mantissaB = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaB |= MANTISSA_IMPLICIT_BIT;
        }

        int exponent = exponentA + exponentB - (1 << EXPONENT_SIZE - 1) + 3;

        final long2 mantissaHighAndLow = multiply(mantissaA << RND_SIZE, mantissaB << RND_SIZE);
        long mantissa0 = mantissaHighAndLow.b;
        long mantissa1 = mantissaHighAndLow.a;
        if (mantissa1 < (1L << (SIZE - 3))) {
            mantissa1 = (mantissa1 << 1) | (mantissa0 >>> (SIZE - 1));
            mantissa0 = mantissa0 << 1;
            exponent--;
        }

        if (exponentC == 0) {
            if (mantissaC == 0) { // a * b + c
                return normalize(sign, exponent, mantissa1, rm, flags);
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaC);
                exponentC = exponentAndMantissa.a;
                mantissaC = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaC |= MANTISSA_IMPLICIT_BIT;
        }

        exponentC++;
        long mantissaC0 = 0;
        long mantissaC1 = mantissaC << (RND_SIZE - 1);

        if (!(exponent > exponentC || (exponent == exponentC && mantissa1 >= mantissaC1))) {
            long tmp = mantissa0;
            mantissa0 = mantissaC0;
            mantissaC0 = tmp;

            tmp = mantissa1;
            mantissa1 = mantissaC1;
            mantissaC1 = tmp;

            int tmpi = exponent;
            exponent = exponentC;
            exponentC = tmpi;

            tmpi = sign;
            sign = signC;
            signC = tmpi;
        }

        final int shift = exponent - exponentC;
        if (shift >= 2 * SIZE) {
            mantissaC0 = (mantissaC0 | mantissaC1) != 0 ? 1 : 0;
            mantissaC1 = 0;
        } else if (shift >= SIZE + 1) {
            mantissaC0 = shiftRightAndJam(mantissaC1, shift - SIZE);
            mantissaC1 = 0;
        } else if (shift == SIZE) {
            mantissaC0 = mantissaC1 | (mantissaC0 != 0 ? 1 : 0);
            mantissaC1 = 0;
        } else if (shift != 0) {
            mantissaC0 = (mantissaC1 << (SIZE - shift)) | (mantissaC0 >> shift) | ((mantissaC0 & ((1L << shift) - 1)) != 0 ? 1 : 0);
            mantissaC1 = mantissaC1 >>> shift;
        }

        if (sign == signC) {
            mantissa0 += mantissaC0;
            mantissa1 += mantissaC1 + (mantissa0 < mantissaC0 ? 1 : 0);
        } else {
            final long tmp = mantissa0;
            mantissa0 -= mantissaC0;
            mantissa1 = mantissa1 - mantissaC1 - (mantissa0 > tmp ? 1 : 0);
            if ((mantissa0 | mantissa1) == 0) {
                sign = (rm == RM_RDN) ? 1 : 0;
            }
        }

        return normalize(sign, exponent, mantissa0, mantissa1, rm, flags);
    }

    public long mulsub(final long a, final long b, final long c, final int rm) {
        return muladd(a, b, neg(c), rm);
    }

    public long div(final long a, final long b, final int rm) {
        final int signA = getSign(a);
        final int signB = getSign(b);
        int exponentA = getExponent(a);
        int exponentB = getExponent(b);
        long mantissaA = getMantissa(a);
        long mantissaB = getMantissa(b);

        final int sign = signA ^ signB;

        if (exponentA == EXPONENT_MASK || exponentB == EXPONENT_MASK) {
            if (isNaN(a) || isNaN(b)) { // nan / b || a / nan
                if (isSignalingNaN(a) || isSignalingNaN(b)) {
                    flags.raise(FLAG_INVALID);
                }
                return nan();
            } else if (exponentA == EXPONENT_MASK && exponentB == EXPONENT_MASK) { // inf / inf
                flags.raise(FLAG_INVALID);
                return nan();
            } else if (exponentA == EXPONENT_MASK) { // inf / b
                return pack(sign, EXPONENT_MASK, 0);
            } else { // a / inf
                return pack(sign, 0, 0);
            }
        }

        if (exponentB == 0) {
            if (mantissaB == 0) { // a / 0
                if (exponentA == 0 && mantissaA == 0) { // 0 / 0 => NaN
                    flags.raise(FLAG_INVALID);
                    return nan();
                } else { // a / 0, a != 0 => Infinity
                    flags.raise(FLAG_DIV_ZERO);
                    return pack(sign, EXPONENT_MASK, 0);
                }
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaB);
                exponentB = exponentAndMantissa.a;
                mantissaB = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaB |= MANTISSA_IMPLICIT_BIT;
        }
        if (exponentA == 0) {
            if (mantissaA == 0) { // 0 / b
                return pack(sign, 0, 0);
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaA);
                exponentA = exponentAndMantissa.a;
                mantissaA = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaA |= MANTISSA_IMPLICIT_BIT;
        }

        final int exponent = exponentA - exponentB + (1 << (EXPONENT_SIZE - 1)) - 1;

        final long2 mantissaAndRemainder = divideAndRemainder(mantissaA, mantissaB << 2);
        final long mantissa = mantissaAndRemainder.a | (mantissaAndRemainder.b != 0 ? 1 : 0);

        return normalize(sign, exponent, mantissa, rm, flags);
    }

    public long sqrt(final long a, final int rm) {
        final int signA = getSign(a);
        int exponentA = getExponent(a);
        long mantissaA = getMantissa(a);

        if (exponentA == EXPONENT_MASK) {
            if (isNaN(a)) { // NaN
                if (isSignalingNaN(a)) {
                    flags.raise(FLAG_INVALID);
                }
                return nan();
            } else if (signA == 1) { // a is negative
                flags.raise(FLAG_INVALID);
                return nan();
            } else { // Infinity
                return a;
            }
        }

        if (signA == 1) { // a is negative
            if (exponentA == 0 && mantissaA == 0) { // -0
                return a;
            } else {
                flags.raise(FLAG_INVALID);
                return nan();
            }
        }

        if (exponentA == 0) {
            if (mantissaA == 0) { // zero
                return pack(0, 0, 0);
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissaA);
                exponentA = exponentAndMantissa.a;
                mantissaA = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissaA |= MANTISSA_IMPLICIT_BIT;
        }

        exponentA -= BIAS;

        if ((exponentA & 1) != 0) {
            exponentA--;
            mantissaA = mantissaA << 1;
        }

        exponentA = (exponentA >> 1) + BIAS;
        mantissaA = mantissaA << (SIZE - 4 - MANTISSA_SIZE);

        final long2 sqrtAndInexact = sqrtAndRemainder(mantissaA);
        mantissaA = sqrtAndInexact.a;
        if (sqrtAndInexact.b != 0) {
            mantissaA |= 1;
        }

        return normalize(signA, exponentA, mantissaA, rm, flags);
    }

    public long min(final long a, final long b) {
        if (isNaN(a) || isNaN(b)) {
            return handleMinMaxNaN(a, b);
        }

        final int signA = getSign(a);
        final int signB = getSign(b);

        if (signA != signB) {
            if (signA != 0) { // a is negative, b is positive
                return a;
            } else { // b is negative, a is positive
                return b;
            }
        } else {
            if ((Long.compareUnsigned(a, b) < 0) ^ (signA != 0)) {
                return a; // a < b if a, b positive || b <= a if a, b negative
            } else {
                return b;
            }
        }
    }

    public long max(final long a, final long b) {
        if (isNaN(a) || isNaN(b)) {
            return handleMinMaxNaN(a, b);
        }

        final int signA = getSign(a);
        final int signB = getSign(b);

        if (signA != signB) {
            if (signA != 0) { // a is negative, b is positive
                return b;
            } else { // b is negative, a is positive
                return a;
            }
        } else {
            if ((Long.compareUnsigned(a, b) < 0) ^ (signA != 0)) {
                return b; // a < b if a, b positive || b <= a if a, b negative
            } else {
                return a;
            }
        }
    }

    public boolean equals(final long a, final long b) {
        if (isNaN(a) || isNaN(b)) {
            if (isSignalingNaN(a) || isSignalingNaN(b)) {
                flags.raise(FLAG_INVALID);
            }
            return false;
        }

        if (((a | b) << 1) == 0) { // -0 || 0
            return true;
        }

        return a == b;
    }

    public boolean lessOrEqual(final long a, final long b) {
        if (isNaN(a) || isNaN(b)) {
            flags.raise(FLAG_INVALID);
            return false;
        }

        final int signA = getSign(a);
        final int signB = getSign(b);

        if (signA != signB) {
            return (signA != 0) // a negative, b positive
                   || (((a | b) << 1) == 0); // a = b = 0 with -0 = 0
        } else {
            if (signA != 0) {
                return a >= b;
            } else {
                return a <= b;
            }
        }
    }

    public boolean lessThan(final long a, final long b) {
        if (isNaN(a) || isNaN(b)) {
            flags.raise(FLAG_INVALID);
            return false;
        }

        final int signA = getSign(a);
        final int signB = getSign(b);

        if (signA != signB) {
            return (signA != 0) // a negative, b positive
                   && (((a | b) << 1) != 0); // a != 0 || b != 0
        } else {
            if (signA != 0) {
                return a > b;
            } else {
                return a < b;
            }
        }
    }

    public int classify(final long a) {
        final int signA = getSign(a);
        final int exponentA = getExponent(a);
        final long mantissaA = getMantissa(a);

        if (exponentA == EXPONENT_MASK) {
            if (mantissaA == 0) { // Infinity
                if (signA != 0) {
                    return FCLASS_NEGINF;
                } else {
                    return FCLASS_POSINF;
                }
            } else { // NaN
                if ((mantissaA & QUIET_NAN_MASK) != 0) {
                    return FCLASS_QNAN;
                } else {
                    return FCLASS_SNAN;
                }
            }
        } else if (exponentA == 0) {
            if (mantissaA == 0) { // zero
                if (signA != 0) {
                    return FCLASS_NEGZERO;
                } else {
                    return FCLASS_POSZERO;
                }
            } else { // subnormal
                if (signA != 0) {
                    return FCLASS_NEGSUBN;
                } else {
                    return FCLASS_POSSUBN;
                }
            }
        } else { // normal number
            if (signA != 0) {
                return FCLASS_NEGNORM;
            } else {
                return FCLASS_POSNORM;
            }
        }
    }

    public long intToDouble(final int a, final int rm) {
        return intToDouble(a, rm, false);
    }

    public long unsignedIntToDouble(final int a, final int rm) {
        return intToDouble(a, rm, true);
    }

    public int doubleToInt(final long a, final int rm) {
        return doubleToInt(a, rm, false);
    }

    public int doubleToUnsignedInt(final long a, final int rm) {
        return doubleToInt(a, rm, true);
    }

    public int doubleToFloat(final long a, final int rm) {
        final int sign = getSign(a);
        int exponent = getExponent(a);
        long mantissa = getMantissa(a);

        if (exponent == EXPONENT_MASK) {
            if (mantissa != 0) { // NaN
                if (isSignalingNaN(a)) {
                    flags.raise(FLAG_INVALID);
                }
                return SoftFloat.nan();
            } else { // Infinity
                return SoftFloat.pack(sign, SoftFloat.EXPONENT_MASK, 0);
            }
        }
        if (exponent == 0) {
            if (mantissa == 0) { // zero
                return SoftFloat.pack(sign, 0, 0);
            } else { // subnormal
                final int_long exponentAndMantissa = normalizeSubnormal(mantissa);
                exponent = exponentAndMantissa.a;
                mantissa = exponentAndMantissa.b;
            }
        } else { // normal, make implicit leading 1 explicit
            mantissa |= MANTISSA_IMPLICIT_BIT;
        }

        return SoftFloat.normalize(sign,
                // make exponent relative to new bias
                exponent + (SoftFloat.BIAS - BIAS),
                // left align to implicit leading bit
                (int) shiftRightAndJam(mantissa, MANTISSA_SIZE - SoftFloat.INTERNAL_MANTISSA_SIZE), rm, flags);
    }

    public long floatToDouble(final int a, @SuppressWarnings("unused") final int rm) {
        final int sign = SoftFloat.getSign(a);
        int exponent = SoftFloat.getExponent(a);
        int mantissa = SoftFloat.getMantissa(a);

        if (exponent == SoftFloat.EXPONENT_MASK) {
            if (mantissa != 0) { // NaN
                if (SoftFloat.isSignalingNaN(a)) {
                    flags.raise(FLAG_INVALID);
                }
                return nan();
            } else { // Infinity
                return pack(sign, EXPONENT_MASK, 0);
            }
        }

        if (exponent == 0) {
            if (mantissa == 0) { // zero
                return pack(sign, 0, 0);
            } else { // subnormal
                final SoftFloat.int2 exponentAndMantissa = SoftFloat.normalizeSubnormal(mantissa);
                exponent = exponentAndMantissa.a;
                mantissa = exponentAndMantissa.b;
            }
        }

        return pack(sign,
                // make exponent relative to new bias
                exponent + (BIAS - SoftFloat.BIAS),
                // left align to implicit leading bit
                (long) mantissa << (MANTISSA_SIZE - SoftFloat.MANTISSA_SIZE)
        );
    }

    private long intToDouble(final int a, final int rm, final boolean isUnsigned) {
        final int sign;
        final int mantissa;
        if (!isUnsigned && a < 0) {
            sign = 1;
            mantissa = -a;
        } else {
            sign = 0;
            mantissa = a;
        }

        final int exponent = BIAS + SIZE - 2;
        return normalize(sign, exponent, Integer.toUnsignedLong(mantissa), rm, flags);
    }

    private int doubleToInt(final long a, final int rm, final boolean isUnsigned) {
        int sign = getSign(a);
        int exponent = getExponent(a);
        long mantissa = getMantissa(a);

        if (exponent == EXPONENT_MASK && mantissa != 0) { // NaN
            sign = 0; // Treat as positive infinity.
        }

        if (exponent == 0) { // Zero or subnormal
            exponent = 1;
        } else { // normal, make implicit leading 1 explicit
            mantissa |= MANTISSA_IMPLICIT_BIT;
        }

        mantissa = mantissa << RND_SIZE;
        exponent = exponent - BIAS - MANTISSA_SIZE;

        final int max;
        if (isUnsigned) {
            max = sign - 1;
        } else {
            max = (1 << (Integer.SIZE - 1)) - (sign ^ 1);
        }

        int result;
        if (exponent >= 0) { // overflow
            flags.raise(FLAG_INVALID);
            return max;
        } else {
            mantissa = shiftRightAndJam(mantissa, -exponent);

            final int addend;
            switch (rm) {
                case RM_RNE:
                case RM_RMM: {
                    addend = 1 << (RND_SIZE - 1);
                    break;
                }
                case RM_RTZ: {
                    addend = 0;
                    break;
                }
                case RM_RDN:
                case RM_RUP: {
                    if (sign == 0 ? (rm == RM_RDN) : (rm == RM_RUP)) {
                        addend = (1 << RND_SIZE) - 1;
                    } else {
                        addend = 0;
                    }
                    break;
                }
                default: {
                    throw new IllegalArgumentException();
                }
            }

            final long rnd_bits = mantissa & ((1 << RND_SIZE) - 1);
            mantissa = (mantissa + addend) >>> RND_SIZE;
            if (rm == RM_RNE && rnd_bits == (1 << (RND_SIZE - 1))) {
                mantissa &= ~1;
            }

            if (Long.compareUnsigned(mantissa, Integer.toUnsignedLong(max)) > 0) { // overflow
                flags.raise(FLAG_INVALID);
                return max;
            }

            result = (int) mantissa;
            if (rnd_bits != 0) {
                flags.raise(FLAG_INEXACT);
            }
        }

        if (sign != 0) {
            result = -result;
        }

        return result;
    }

    /**
     * Multiplies two unsigned longs and returns the high and low words of
     * the result (in that order).
     *
     * @param a the first operand.
     * @param b the second operand.
     * @return the result of the multiplication, with {@code {a=high, b=low}}.
     */
    private static long2 multiply(final long a, final long b) {
        final BigInteger result = unsignedLongTo128(a).multiply(unsignedLongTo128(b));
        return long2.of(result.shiftRight(64).longValue(), result.longValue());
    }

    private static long2 divideAndRemainder(final long ah, final long b) {
        final BigInteger[] result = BigInteger.valueOf(ah).shiftLeft(64).divideAndRemainder(BigInteger.valueOf(b));
        return long2.of(result[0].longValueExact(), result[1].longValueExact());
    }

    private static long2 sqrtAndRemainder(final long ah) {
        final int l;
        if (ah != 0) {
            l = 2 * SIZE - Long.numberOfLeadingZeros(ah - 1);
        } else {
            return long2.of(0, 0);
        }

        final BigInteger a = BigInteger.valueOf(ah).shiftLeft(64);
        BigInteger u = BigInteger.valueOf(1L).shiftLeft((l + 1) / 2);
        BigInteger s;
        do {
            s = u;
            u = a.divide(s).add(s).shiftRight(1);
        } while (u.compareTo(s) < 0);
        return long2.of(s.longValueExact(), a.subtract(s.multiply(s)).getLowestSetBit() != -1 ? 1 : 0);
    }

    private long handleMinMaxNaN(final long a, final long b) {
        if (isSignalingNaN(a) || isSignalingNaN(b)) {
            flags.raise(FLAG_INVALID);
        }
        // NB: For non-RISC-V we'd return NaN here. However, as the primary use-case
        //     of this is the RISC-V emulator, we stay true to the spec here, VIp68:
        //     "If both inputs are NaNs, the result is the canonical NaN. If only one operand
        //      is a NaN, the result is the non-NaN operand. Signaling NaN inputs set the
        //      invalid operation exception flag, even when the result is not NaN."
        //     Where the "canonical NaN" is the quiet NaN.
        if (isNaN(a)) {
            if (isNaN(b)) {
                return nan();
            } else {
                return b;
            }
        } else {
            return a;
        }
    }

    private static boolean isSignalingNaN(final long a) {
        return isNaN(a) && (a & QUIET_NAN_MASK) == 0;
    }

    private static int_long normalizeSubnormal(final long mantissa) {
        final int shift = MANTISSA_SIZE - (SIZE - 1 - Long.numberOfLeadingZeros(mantissa));
        return int_long.of(1 - shift, mantissa << shift);
    }

    private static long normalize(final int sign, final int exponent, final long mantissa, final int rm, final SoftFloat.Flags flags) {
        final int shift = Long.numberOfLeadingZeros(mantissa) - (SIZE - 1 - INTERNAL_MANTISSA_SIZE);
        assert shift >= 0;
        return round(sign, exponent - shift, mantissa << shift, rm, flags);
    }

    private static long normalize(final int sign, final int exponent, final long mantissa0, final long mantissa1, final int rm, final SoftFloat.Flags flags) {
        final int l;
        if (mantissa1 == 0) {
            l = SIZE + Long.numberOfLeadingZeros(mantissa0);
        } else {
            l = Long.numberOfLeadingZeros(mantissa1);
        }

        final int shift = l - (SIZE - 1 - INTERNAL_MANTISSA_SIZE);
        assert shift >= 0;

        final long mantissa;
        if (shift == 0) {
            mantissa = mantissa1 | (mantissa0 != 0 ? 1 : 0);
        } else if (shift < SIZE) {
            mantissa = (mantissa1 << shift)
                       | (mantissa0 >>> (SIZE - shift))
                       | ((mantissa0 << shift) != 0 ? 1 : 0);
        } else {
            mantissa = mantissa0 << (shift - SIZE);
        }

        return round(sign, exponent - shift, mantissa, rm, flags);
    }

    private static long round(final int sign, int exponent, long mantissa, final int rm, final SoftFloat.Flags flags) {
        final int addend;
        switch (rm) {
            case RM_RNE:
            case RM_RMM: {
                addend = 1 << (RND_SIZE - 1);
                break;
            }
            case RM_RTZ: {
                addend = 0;
                break;
            }
            case RM_RDN:
            case RM_RUP: {
                if (sign == 0 ? (rm == RM_RDN) : (rm == RM_RUP)) {
                    addend = (1 << RND_SIZE) - 1;
                } else {
                    addend = 0;
                }
                break;
            }
            default:
                throw new IllegalArgumentException();
        }

        final int rnd_bits;
        if (exponent <= 0) {
            final long min = 1L << (SIZE - 1);
            final boolean isSubnormal = exponent < 0 || Long.compareUnsigned(mantissa + addend, min) < 0;
            final int diff = 1 - exponent;
            mantissa = shiftRightAndJam(mantissa, diff);
            rnd_bits = (int) (mantissa & ((1 << RND_SIZE) - 1));
            if (isSubnormal && rnd_bits != 0) {
                flags.raise(FLAG_UNDERFLOW);
            }
            exponent = 1;
        } else {
            rnd_bits = (int) (mantissa & ((1 << RND_SIZE) - 1));
        }

        if (rnd_bits != 0) {
            flags.raise(FLAG_INEXACT);
        }

        mantissa = (mantissa + addend) >>> RND_SIZE;
        if (rm == RM_RNE && rnd_bits == (1 << (RND_SIZE - 1))) {
            mantissa &= ~1;
        }
        exponent += mantissa >>> (MANTISSA_SIZE + 1);
        if (mantissa <= MANTISSA_MASK) {
            exponent = 0;
        } else if (exponent >= EXPONENT_MASK) {
            if (addend == 0) {
                exponent = EXPONENT_MASK - 1;
                mantissa = MANTISSA_MASK;
            } else {
                exponent = EXPONENT_MASK;
                mantissa = 0;
            }
            flags.raise(FLAG_OVERFLOW | FLAG_INEXACT);
        }
        return pack(sign, exponent, mantissa);
    }

    private static long shiftRightAndJam(final long a, final int d) {
        if (d == 0) {
            return a;
        }
        if (d >= SIZE) {
            return a != 0 ? 1 : 0;
        }
        return (a >>> d) | ((a & ((1L << d) - 1)) != 0 ? 1 : 0);
    }

    private static long pack(final int sign, final int exponent, final long mantissa) {
        return (long) sign << (SIZE - 1) | (Integer.toUnsignedLong(exponent) << MANTISSA_SIZE) | (mantissa & MANTISSA_MASK);
    }

    private static int getSign(final long a) {
        return (int) (a >>> (EXPONENT_SIZE + MANTISSA_SIZE));
    }

    private static int getExponent(final long a) {
        return (int) ((a >>> MANTISSA_SIZE) & EXPONENT_MASK);
    }

    private static long getMantissa(final long a) {
        return a & MANTISSA_MASK;
    }

    private static BigInteger unsignedLongTo128(final long value) {
        if (value >= 0L) {
            return BigInteger.valueOf(value);
        } else {
            return BigInteger.valueOf(Integer.toUnsignedLong((int) (value >>> 32))).shiftLeft(32).
                    or(BigInteger.valueOf(Integer.toUnsignedLong((int) value)));
        }
    }

    private static final class int_long {
        private static final ThreadLocal<int_long> INSTANCE = ThreadLocal.withInitial(int_long::new);

        public static int_long of(final int a, final long b) {
            final int_long instance = INSTANCE.get();
            instance.a = a;
            instance.b = b;
            return instance;
        }

        public int a;
        public long b;
    }

    private static final class long2 {
        private static final ThreadLocal<long2> INSTANCE = ThreadLocal.withInitial(long2::new);

        public static long2 of(final long a, final long b) {
            final long2 instance = INSTANCE.get();
            instance.a = a;
            instance.b = b;
            return instance;
        }

        public long a, b;
    }
}
