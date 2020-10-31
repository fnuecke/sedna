package li.cil.sedna;

import li.cil.sedna.utils.SoftDouble;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

public final class SoftDoubleTests {
    private static final byte JAVA_ROUNDING_MODE = SoftDouble.RM_RNE;

    @TestFactory
    public Collection<DynamicTest> testSoftDouble() {
        return Arrays.stream(OPERATIONS)
                .map(op -> DynamicTest.dynamicTest(op.getName(), URI.create(op.getName()), () -> {
                    final SoftDouble fpu = new SoftDouble();
                    final Random random = new Random(0);
                    final long[] args = new long[op.getArgCount()];
                    final double[] doubleArgs = new double[args.length];
                    for (int i = 0; i < 100000; i++) {
                        for (int j = 0; j < args.length; j++) {
                            args[j] = random.nextLong();
                            doubleArgs[j] = Double.longBitsToDouble(args[j]);
                        }

                        final OperationResult result0 = op.runSoftDouble(fpu, args);
                        final OperationResult result1 = op.runJavaDouble(doubleArgs);

                        if (!Objects.equals(result0, result1)) {
                            Assertions.fail(i + ": " + result0 + " != " + result1 + "\nargs=" + Arrays.toString(args) + "," + Arrays.toString(doubleArgs));
                        }
                    }
                })).collect(Collectors.toList());
    }

    // NB: min and max are not tested here, because for RISC-V they return the non-NaN value for a
    //     (NaN, not-NaN) argument pair, whereas Java will return NaN. And we want to be RISC-V correct.
    private static final OperationDescriptor[] OPERATIONS = {
            new LambdaOperationDescriptor("add", 2,
                    (fpu, args) -> OperationResult.of(fpu.add(args[0], args[1], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(args[0] + args[1])),
            new LambdaOperationDescriptor("sub", 2,
                    (fpu, args) -> OperationResult.of(fpu.sub(args[0], args[1], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(args[0] - args[1])),
            new LambdaOperationDescriptor("mul", 2,
                    (fpu, args) -> OperationResult.of(fpu.mul(args[0], args[1], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(args[0] * args[1])),
            new LambdaOperationDescriptor("div", 2,
                    (fpu, args) -> OperationResult.of(fpu.div(args[0], args[1], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(args[0] / args[1])),
            new LambdaOperationDescriptor("sqrt", 1,
                    (fpu, args) -> OperationResult.of(fpu.sqrt(args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(Math.sqrt(args[0]))),
            new LambdaOperationDescriptor("isNaN", 1,
                    (fpu, args) -> OperationResult.of(SoftDouble.isNaN(args[0])),
                    (args) -> OperationResult.of(Double.isNaN(args[0]))),
            new LambdaOperationDescriptor("neg", 1,
                    (fpu, args) -> OperationResult.of(fpu.neg(args[0])),
                    (args) -> OperationResult.of(-args[0])),
            new LambdaOperationDescriptor("sign", 1,
                    (fpu, args) -> OperationResult.of(fpu.sign(args[0])),
                    (args) -> OperationResult.of((long) Math.signum(args[0]))),
            new LambdaOperationDescriptor("lessThan", 2,
                    (fpu, args) -> OperationResult.of(fpu.lessThan(args[0], args[1])),
                    (args) -> OperationResult.of(args[0] < args[1])),
            new LambdaOperationDescriptor("lessOrEqual", 2,
                    (fpu, args) -> OperationResult.of(fpu.lessOrEqual(args[0], args[1])),
                    (args) -> OperationResult.of(args[0] <= args[1])),
            new LambdaOperationDescriptor("intToDouble", 1,
                    (fpu, args) -> OperationResult.of(fpu.intToDouble((int) args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of((double) (int) Double.doubleToRawLongBits(args[0]))),
            new LambdaOperationDescriptor("unsignedIntToDouble", 1,
                    (fpu, args) -> OperationResult.of(fpu.unsignedIntToDouble((int) args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of((double) (Double.doubleToRawLongBits(args[0]) & 0xFFFFFFFFL))),
            new LambdaOperationDescriptor("floatToDouble", 1,
                    (fpu, args) -> OperationResult.of(fpu.floatToDouble((int) args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(Float.intBitsToFloat((int) Double.doubleToRawLongBits(args[0])))),
            new LambdaOperationDescriptor("doubleToFloat", 1,
                    (fpu, args) -> OperationResult.of(fpu.doubleToFloat(args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of(Float.floatToIntBits((float) args[0]))),
    };

    private static abstract class OperationDescriptor {
        public abstract String getName();

        public abstract int getArgCount();

        public abstract OperationResult runSoftDouble(final SoftDouble fpu, final long[] args);

        public abstract OperationResult runJavaDouble(final double[] args);
    }

    private static final class LambdaOperationDescriptor extends OperationDescriptor {
        private final String name;
        private final int argCount;
        private final SoftDoubleOperation softDoubleOperation;
        private final JavaDoubleOperation javaDoubleOperation;

        @FunctionalInterface
        public interface SoftDoubleOperation {
            OperationResult run(final SoftDouble fpu, final long[] args);
        }

        @FunctionalInterface
        public interface JavaDoubleOperation {
            OperationResult run(final double[] args);
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
        public OperationResult runSoftDouble(final SoftDouble fpu, final long[] args) {
            return softDoubleOperation.run(fpu, args);
        }

        @Override
        public OperationResult runJavaDouble(final double[] args) {
            return javaDoubleOperation.run(args);
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
