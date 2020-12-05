package li.cil.sedna;

import li.cil.sedna.utils.SoftFloat;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

public final class SoftFloatTests {
    private static final byte JAVA_ROUNDING_MODE = SoftFloat.RM_RNE;

    @TestFactory
    public Collection<DynamicTest> testSoftFloat() {
        return Arrays.stream(OPERATIONS)
                .map(op -> DynamicTest.dynamicTest(op.getName(), URI.create(op.getName()), () -> {
                    final SoftFloat fpu = new SoftFloat();
                    final Random random = new Random(0);
                    final int[] intArgs = new int[op.getArgCount()];
                    final float[] floatArgs = new float[intArgs.length];
                    for (int i = 0; i < 100000; i++) {
                        for (int j = 0; j < intArgs.length; j++) {
                            intArgs[j] = random.nextInt();
                            floatArgs[j] = Float.intBitsToFloat(intArgs[j]);
                        }

                        final OperationResult result0 = op.runSoftFloat(fpu, intArgs);
                        final OperationResult result1 = op.runJavaFloat(floatArgs, intArgs);

                        if (!Objects.equals(result0, result1)) {
                            fail(i + ": " + result0 + " != " + result1 + "\nargs=" + Arrays.toString(intArgs) + "," + Arrays.toString(floatArgs));
                        }
                    }
                })).collect(Collectors.toList());
    }

    // NB: min and max are not tested here, because for RISC-V they return the non-NaN value for a
    //     (NaN, not-NaN) argument pair, whereas Java will return NaN. And we want to be RISC-V correct.
    // NB: Java converts NaNs to zero whereas RISC-V expects them to be treated as positive infinity, so
    //     we manually catch NaNs in floatToInt, floatToUnsignedInt and floatToLong.
    private static final OperationDescriptor[] OPERATIONS = {
            new LambdaOperationDescriptor("add", 2,
                    (fpu, ints) -> OperationResult.of(fpu.add(ints[0], ints[1], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of(floats[0] + floats[1])),
            new LambdaOperationDescriptor("sub", 2,
                    (fpu, ints) -> OperationResult.of(fpu.sub(ints[0], ints[1], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of(floats[0] - floats[1])),
            new LambdaOperationDescriptor("mul", 2,
                    (fpu, ints) -> OperationResult.of(fpu.mul(ints[0], ints[1], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of(floats[0] * floats[1])),
            new LambdaOperationDescriptor("div", 2,
                    (fpu, ints) -> OperationResult.of(fpu.div(ints[0], ints[1], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of(floats[0] / floats[1])),
            new LambdaOperationDescriptor("sqrt", 1,
                    (fpu, ints) -> OperationResult.of(fpu.sqrt(ints[0], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of((float) Math.sqrt(floats[0]))),
            new LambdaOperationDescriptor("isNaN", 1,
                    (fpu, ints) -> OperationResult.of(SoftFloat.isNaN(ints[0])),
                    (floats, ints) -> OperationResult.of(Float.isNaN(floats[0]))),
            new LambdaOperationDescriptor("neg", 1,
                    (fpu, ints) -> OperationResult.of(fpu.neg(ints[0])),
                    (floats, ints) -> OperationResult.of(-floats[0])),
            new LambdaOperationDescriptor("sign", 1,
                    (fpu, ints) -> OperationResult.of(fpu.sign(ints[0])),
                    (floats, ints) -> OperationResult.of((int) Math.signum(floats[0]))),
            new LambdaOperationDescriptor("lessThan", 2,
                    (fpu, ints) -> OperationResult.of(fpu.lessThan(ints[0], ints[1])),
                    (floats, ints) -> OperationResult.of(floats[0] < floats[1])),
            new LambdaOperationDescriptor("lessOrEqual", 2,
                    (fpu, ints) -> OperationResult.of(fpu.lessOrEqual(ints[0], ints[1])),
                    (floats, ints) -> OperationResult.of(floats[0] <= floats[1])),
            new LambdaOperationDescriptor("intToFloat", 1,
                    (fpu, ints) -> OperationResult.of(fpu.intToFloat(ints[0], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of((float) ints[0])),
            new LambdaOperationDescriptor("unsignedIntToFloat", 1,
                    (fpu, ints) -> OperationResult.of(fpu.unsignedIntToFloat(ints[0], JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of((float) (ints[0] & 0xFFFFFFFFL))),
            new LambdaOperationDescriptor("floatToInt", 1,
                    (fpu, ints) -> OperationResult.of(SoftFloat.isNaN(ints[0]) ? 0 : fpu.floatToInt(ints[0], SoftFloat.RM_RTZ)),
                    (floats, ints) -> OperationResult.of((int) floats[0])),
            new LambdaOperationDescriptor("floatToUnsignedInt", 1,
                    (fpu, ints) -> OperationResult.of(SoftFloat.isNaN(ints[0]) ? 0 : fpu.floatToUnsignedInt(ints[0], SoftFloat.RM_RTZ)),
                    (floats, ints) -> OperationResult.of((int) Math.min(0xFFFFFFFFL, (long) Math.max(0, floats[0])))),
            new LambdaOperationDescriptor("longToFloat", 2,
                    (fpu, ints) -> OperationResult.of(fpu.longToFloat(((long) ints[0] << 32) | (ints[1] & 0xFFFFFFFFL), JAVA_ROUNDING_MODE)),
                    (floats, ints) -> OperationResult.of((float) (((long) ints[0] << 32) | (ints[1] & 0xFFFFFFFFL)))),
            new LambdaOperationDescriptor("unsignedLongToFloat", 2,
                    (fpu, ints) -> OperationResult.of(fpu.unsignedLongToFloat(((long) ints[0] << 32) | (ints[1] & 0xFFFFFFFFL), JAVA_ROUNDING_MODE)),
                    (floats, ints) -> {
                        final long value = ((long) ints[0] << 32) | (ints[1] & 0xFFFFFFFFL);
                        final double a = value & Long.MAX_VALUE;
                        if (value >= 0) {
                            return OperationResult.of((float) a);
                        } else {
                            return OperationResult.of((float) (a + Math.pow(2, 63)));
                        }
                    }),
            new LambdaOperationDescriptor("floatToLong", 1,
                    (fpu, ints) -> OperationResult.of(SoftFloat.isNaN(ints[0]) ? 0L : fpu.floatToLong(ints[0], SoftFloat.RM_RTZ)),
                    (floats, ints) -> OperationResult.of((long) floats[0])),
//            new LambdaOperationDescriptor("floatToUnsignedLong", 1,
//                    (fpu, ints) -> OperationResult.of(fpu.floatToUnsignedLong(ints[0], SoftFloat.RM_RTZ)),
//                    (floats, ints) -> OperationResult.of(BigDecimal.valueOf(floats[0]).toBigInteger()
//                            .max(BigInteger.ZERO)
//                            .min(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).longValue())),
    };

    private static abstract class OperationDescriptor {
        public abstract String getName();

        public abstract int getArgCount();

        public abstract OperationResult runSoftFloat(final SoftFloat fpu, final int[] ints);

        public abstract OperationResult runJavaFloat(final float[] floats, final int[] ints);
    }

    private static final class LambdaOperationDescriptor extends OperationDescriptor {
        private final String name;
        private final int argCount;
        private final SoftFloatOperation softFloatOperation;
        private final JavaFloatOperation javaFloatOperation;

        @FunctionalInterface
        public interface SoftFloatOperation {
            OperationResult run(final SoftFloat fpu, final int[] ints);
        }

        @FunctionalInterface
        public interface JavaFloatOperation {
            OperationResult run(final float[] floats, final int[] ints);
        }

        public LambdaOperationDescriptor(final String name, final int argCount, final SoftFloatOperation softFloatOperation, final JavaFloatOperation javaFloatOperation) {
            this.name = name;
            this.argCount = argCount;
            this.softFloatOperation = softFloatOperation;
            this.javaFloatOperation = javaFloatOperation;
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
        public OperationResult runSoftFloat(final SoftFloat fpu, final int[] ints) {
            return softFloatOperation.run(fpu, ints);
        }

        @Override
        public OperationResult runJavaFloat(final float[] floats, final int[] ints) {
            return javaFloatOperation.run(floats, ints);
        }
    }

    private static final class OperationResult {
        enum Type {
            BOOLEAN,
            FLOAT,
            LONG,
        }

        public boolean boolValue;
        public int floatBits;
        public long longValue;
        private Type type;

        public float asFloat() {
            return Float.intBitsToFloat(floatBits);
        }

        public static OperationResult of(final boolean value) {
            final OperationResult result = new OperationResult();
            result.boolValue = value;
            result.type = Type.BOOLEAN;
            return result;
        }

        public static OperationResult of(final float value) {
            final OperationResult result = new OperationResult();
            result.floatBits = Float.floatToIntBits(value);
            result.type = Type.FLOAT;
            return result;
        }

        public static OperationResult of(final int value) {
            final OperationResult result = new OperationResult();
            result.floatBits = value;
            result.type = Type.FLOAT;
            return result;
        }

        public static OperationResult of(final long value) {
            final OperationResult result = new OperationResult();
            result.longValue = value;
            result.type = Type.LONG;
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final OperationResult that = (OperationResult) o;
            return boolValue == that.boolValue &&
                   floatBits == that.floatBits &&
                   longValue == that.longValue;
        }

        @Override
        public int hashCode() {
            return Objects.hash(boolValue, floatBits, longValue);
        }

        @Override
        public String toString() {
            switch (type) {
                case BOOLEAN:
                    return String.valueOf(boolValue);
                case FLOAT:
                    return (asFloat() + " / " + floatBits + " / " + (floatBits & 0xFFFFFFFFL));
                case LONG:
                    return String.valueOf(longValue);
                default:
                    throw new IllegalStateException();
            }
        }
    }
}
