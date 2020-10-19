package li.cil.sedna;

import li.cil.sedna.utils.SoftFloat;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

public final class SoftFloatTest {
    private static final byte JAVA_ROUNDING_MODE = SoftFloat.RM_RNE;

    @TestFactory
    public Collection<DynamicTest> testSoftFloat() {
        return Arrays.stream(OPERATIONS)
                .map(op -> DynamicTest.dynamicTest(op.getName(), URI.create(op.getName()), () -> {
                    final SoftFloat fpu = new SoftFloat();
                    final Random random = new Random(0);
                    final int[] args = new int[op.getArgCount()];
                    final float[] floatArgs = new float[args.length];
                    for (int i = 0; i < 100000; i++) {
                        for (int j = 0; j < args.length; j++) {
                            args[j] = random.nextInt();
                            floatArgs[j] = Float.intBitsToFloat(args[j]);
                        }

                        final OperationResult result0 = op.runSoftFloat(fpu, args);
                        final OperationResult result1 = op.runJavaFloat(floatArgs);

                        if (!Objects.equals(result0, result1)) {
                            Assertions.fail(result0 + " != " + result1 + "\nargs=" + Arrays.toString(args) + "," + Arrays.toString(floatArgs));
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
                    (args) -> OperationResult.of((float) Math.sqrt(args[0]))),
            new LambdaOperationDescriptor("isNaN", 1,
                    (fpu, args) -> OperationResult.of(SoftFloat.isNaN(args[0])),
                    (args) -> OperationResult.of(Float.isNaN(args[0]))),
            new LambdaOperationDescriptor("neg", 1,
                    (fpu, args) -> OperationResult.of(fpu.neg(args[0])),
                    (args) -> OperationResult.of(-args[0])),
            new LambdaOperationDescriptor("sign", 1,
                    (fpu, args) -> OperationResult.of(fpu.sign(args[0])),
                    (args) -> OperationResult.of((int) Math.signum(args[0]))),
            new LambdaOperationDescriptor("lessThan", 2,
                    (fpu, args) -> OperationResult.of(fpu.lessThan(args[0], args[1])),
                    (args) -> OperationResult.of(args[0] < args[1])),
            new LambdaOperationDescriptor("lessOrEqual", 2,
                    (fpu, args) -> OperationResult.of(fpu.lessOrEqual(args[0], args[1])),
                    (args) -> OperationResult.of(args[0] <= args[1])),
            new LambdaOperationDescriptor("intToFloat", 1,
                    (fpu, args) -> OperationResult.of(fpu.intToFloat(args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of((float) Float.floatToRawIntBits(args[0]))),
            new LambdaOperationDescriptor("unsignedIntToFloat", 1,
                    (fpu, args) -> OperationResult.of(fpu.unsignedIntToFloat(args[0], JAVA_ROUNDING_MODE)),
                    (args) -> OperationResult.of((float) (Float.floatToRawIntBits(args[0]) & 0xFFFFFFFFL))),
    };

    private static abstract class OperationDescriptor {
        public abstract String getName();

        public abstract int getArgCount();

        public abstract OperationResult runSoftFloat(final SoftFloat fpu, final int[] args);

        public abstract OperationResult runJavaFloat(final float[] args);
    }

    private static final class LambdaOperationDescriptor extends OperationDescriptor {
        private final String name;
        private final int argCount;
        private final SoftFloatOperation softFloatOperation;
        private final JavaFloatOperation javaFloatOperation;

        @FunctionalInterface
        public interface SoftFloatOperation {
            OperationResult run(final SoftFloat fpu, final int[] args);
        }

        @FunctionalInterface
        public interface JavaFloatOperation {
            OperationResult run(final float[] args);
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
        public OperationResult runSoftFloat(final SoftFloat fpu, final int[] args) {
            return softFloatOperation.run(fpu, args);
        }

        @Override
        public OperationResult runJavaFloat(final float[] args) {
            return javaFloatOperation.run(args);
        }
    }

    private static final class OperationResult {
        public boolean boolValue;
        public int floatBits;
        private boolean isBoolean;

        public float asFloat() {
            return Float.intBitsToFloat(floatBits);
        }

        public static OperationResult of(final boolean value) {
            final OperationResult result = new OperationResult();
            result.boolValue = value;
            result.isBoolean = true;
            return result;
        }

        public static OperationResult of(final float value) {
            final OperationResult result = new OperationResult();
            result.floatBits = Float.floatToIntBits(value);
            return result;
        }

        public static OperationResult of(final int value) {
            final OperationResult result = new OperationResult();
            result.floatBits = value;
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final OperationResult that = (OperationResult) o;
            return boolValue == that.boolValue &&
                   floatBits == that.floatBits;
        }

        @Override
        public int hashCode() {
            return Objects.hash(boolValue, floatBits);
        }

        @Override
        public String toString() {
            return isBoolean ? String.valueOf(boolValue) : (asFloat() + " / " + floatBits + " / " + (floatBits & 0xFFFFFFFFL));
        }
    }
}
