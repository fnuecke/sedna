package li.cil.sedna;

import li.cil.sedna.utils.SoftDouble;
import li.cil.sedna.utils.SoftFloat;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

public final class SoftDoubleTests {
    private static final byte JAVA_ROUNDING_MODE = SoftDouble.RM_RNE;

    @TestFactory
    public Collection<DynamicTest> testSoftDouble() {
        return Arrays.stream(OPERATIONS)
                .map(op -> DynamicTest.dynamicTest(op.getName(), URI.create(op.getName()), () -> {
                    final SoftDouble fpu = new SoftDouble();
                    final Random random = new Random(0);
                    final long[] longArgs = new long[op.getArgCount()];
                    final double[] doubleArgs = new double[longArgs.length];
                    for (int i = 0; i < 100000; i++) {
                        for (int j = 0; j < longArgs.length; j++) {
                            longArgs[j] = random.nextLong();
                            doubleArgs[j] = Double.longBitsToDouble(longArgs[j]);
                        }

                        final OperationResult result0 = op.runSoftDouble(fpu, longArgs);
                        final OperationResult result1 = op.runJavaDouble(doubleArgs, longArgs);

                        if (!Objects.equals(result0, result1)) {
                            fail(i + ": " + result0 + " != " + result1 + "\nargs=" + Arrays.toString(longArgs) + "," + Arrays.toString(doubleArgs));
                        }
                    }
                })).collect(Collectors.toList());
    }

    // NB: min and max are not tested here, because for RISC-V they return the non-NaN value for a
    //     (NaN, not-NaN) argument pair, whereas Java will return NaN. And we want to be RISC-V correct.
    // NB: Java converts NaNs to zero whereas RISC-V expects them to be treated as positive infinity, so
    //     we manually catch NaNs in doubleToInt, doubleToUnsignedInt and doubleToLong.
    private static final OperationDescriptor[] OPERATIONS = {
            new LambdaOperationDescriptor("add", 2,
                    (fpu, longs) -> OperationResult.of(fpu.add(longs[0], longs[1], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(doubles[0] + doubles[1])),
            new LambdaOperationDescriptor("sub", 2,
                    (fpu, longs) -> OperationResult.of(fpu.sub(longs[0], longs[1], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(doubles[0] - doubles[1])),
            new LambdaOperationDescriptor("mul", 2,
                    (fpu, longs) -> OperationResult.of(fpu.mul(longs[0], longs[1], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(doubles[0] * doubles[1])),
            new LambdaOperationDescriptor("div", 2,
                    (fpu, longs) -> OperationResult.of(fpu.div(longs[0], longs[1], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(doubles[0] / doubles[1])),
            new LambdaOperationDescriptor("sqrt", 1,
                    (fpu, longs) -> OperationResult.of(fpu.sqrt(longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(Math.sqrt(doubles[0]))),
            new LambdaOperationDescriptor("isNaN", 1,
                    (fpu, longs) -> OperationResult.of(SoftDouble.isNaN(longs[0])),
                    (doubles, longs) -> OperationResult.of(Double.isNaN(doubles[0]))),
            new LambdaOperationDescriptor("neg", 1,
                    (fpu, longs) -> OperationResult.of(fpu.neg(longs[0])),
                    (doubles, longs) -> OperationResult.of(-doubles[0])),
            new LambdaOperationDescriptor("sign", 1,
                    (fpu, longs) -> OperationResult.of(fpu.sign(longs[0])),
                    (doubles, longs) -> OperationResult.of((long) Math.signum(doubles[0]))),
            new LambdaOperationDescriptor("lessThan", 2,
                    (fpu, longs) -> OperationResult.of(fpu.lessThan(longs[0], longs[1])),
                    (doubles, longs) -> OperationResult.of(doubles[0] < doubles[1])),
            new LambdaOperationDescriptor("lessOrEqual", 2,
                    (fpu, longs) -> OperationResult.of(fpu.lessOrEqual(longs[0], longs[1])),
                    (doubles, longs) -> OperationResult.of(doubles[0] <= doubles[1])),
            new LambdaOperationDescriptor("intToDouble", 1,
                    (fpu, longs) -> OperationResult.of(fpu.intToDouble((int) longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of((double) (int) longs[0])),
            new LambdaOperationDescriptor("unsignedIntToDouble", 1,
                    (fpu, longs) -> OperationResult.of(fpu.unsignedIntToDouble((int) longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of((double) (longs[0] & 0xFFFFFFFFL))),
            new LambdaOperationDescriptor("doubleToInt", 1,
                    (fpu, longs) -> OperationResult.of(SoftDouble.isNaN(longs[0]) ? 0 : fpu.doubleToInt(longs[0], SoftFloat.RM_RTZ)),
                    (doubles, longs) -> OperationResult.of((int) doubles[0])),
            new LambdaOperationDescriptor("doubleToUnsignedInt", 1,
                    (fpu, longs) -> OperationResult.of(SoftDouble.isNaN(longs[0]) ? 0 : fpu.doubleToUnsignedInt(longs[0], SoftFloat.RM_RTZ)),
                    (doubles, longs) -> OperationResult.of((int) Math.min(0xFFFFFFFFL, (long) Math.max(0, doubles[0])))),
            new LambdaOperationDescriptor("longToDouble", 1,
                    (fpu, longs) -> OperationResult.of(fpu.longToDouble(longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of((double) longs[0])),
            new LambdaOperationDescriptor("unsignedLongToDouble", 1,
                    (fpu, longs) -> OperationResult.of(fpu.unsignedLongToDouble(longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> {
                        final BigInteger a = BigInteger.valueOf(longs[0] & Long.MAX_VALUE);
                        if (longs[0] >= 0) {
                            return OperationResult.of(a.doubleValue());
                        } else {
                            return OperationResult.of(a.add(BigInteger.ONE.shiftLeft(63)).doubleValue());
                        }
                    }),
            new LambdaOperationDescriptor("doubleToLong", 1,
                    (fpu, longs) -> OperationResult.of(SoftDouble.isNaN(longs[0]) ? 0L : fpu.doubleToLong(longs[0], SoftFloat.RM_RTZ)),
                    (doubles, longs) -> OperationResult.of((long) doubles[0])),
//            new LambdaOperationDescriptor("doubleToUnsignedLong", 1,
//                    (fpu, longs) -> OperationResult.of(SoftDouble.isNaN(longs[0]) ? 0L : fpu.doubleToUnsignedLong(longs[0], SoftFloat.RM_RTZ)),
//                    (doubles, longs) -> OperationResult.of(BigDecimal.valueOf(doubles[0]).max(BigDecimal.ZERO).min(new BigDecimal(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))).toBigInteger().longValue())),
            new LambdaOperationDescriptor("floatToDouble", 1,
                    (fpu, longs) -> OperationResult.of(fpu.floatToDouble((int) longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(Float.intBitsToFloat((int) longs[0]))),
            new LambdaOperationDescriptor("doubleToFloat", 1,
                    (fpu, longs) -> OperationResult.of(fpu.doubleToFloat(longs[0], JAVA_ROUNDING_MODE)),
                    (doubles, longs) -> OperationResult.of(Float.floatToIntBits((float) doubles[0]))),
    };

    private static abstract class OperationDescriptor {
        public abstract String getName();

        public abstract int getArgCount();

        public abstract OperationResult runSoftDouble(final SoftDouble fpu, final long[] args);

        public abstract OperationResult runJavaDouble(final double[] doubles, final long[] longs);
    }

    private static final class LambdaOperationDescriptor extends OperationDescriptor {
        private final String name;
        private final int argCount;
        private final SoftDoubleOperation softDoubleOperation;
        private final JavaDoubleOperation javaDoubleOperation;

        @FunctionalInterface
        public interface SoftDoubleOperation {
            OperationResult run(final SoftDouble fpu, final long[] longs);
        }

        @FunctionalInterface
        public interface JavaDoubleOperation {
            OperationResult run(final double[] doubles, final long[] longs);
        }

        public LambdaOperationDescriptor(final String name, final int argCount, final SoftDoubleOperation softDoubleOperation, final JavaDoubleOperation javaDoubleOperation) {
            this.name = name;
            this.argCount = argCount;
            this.softDoubleOperation = softDoubleOperation;
            this.javaDoubleOperation = javaDoubleOperation;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getArgCount() {
            return argCount;
        }

        @Override
        public OperationResult runSoftDouble(final SoftDouble fpu, final long[] longs) {
            return softDoubleOperation.run(fpu, longs);
        }

        @Override
        public OperationResult runJavaDouble(final double[] doubles, final long[] longs) {
            return javaDoubleOperation.run(doubles, longs);
        }
    }

    private static final class OperationResult {
        public boolean boolValue;
        public long doubleBits;
        private boolean isBoolean;

        public double asDouble() {
            return Double.longBitsToDouble(doubleBits);
        }

        public static OperationResult of(final boolean value) {
            final OperationResult result = new OperationResult();
            result.boolValue = value;
            result.isBoolean = true;
            return result;
        }

        public static OperationResult of(final double value) {
            final OperationResult result = new OperationResult();
            result.doubleBits = Double.doubleToLongBits(value);
            return result;
        }

        public static OperationResult of(final long value) {
            final OperationResult result = new OperationResult();
            result.doubleBits = value;
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final OperationResult that = (OperationResult) o;
            return boolValue == that.boolValue &&
                   doubleBits == that.doubleBits;
        }

        @Override
        public int hashCode() {
            return Objects.hash(boolValue, doubleBits);
        }

        @Override
        public String toString() {
            return isBoolean ? String.valueOf(boolValue) : (asDouble() + " / " + doubleBits + " / " + Long.toUnsignedString(doubleBits));
        }
    }
}
