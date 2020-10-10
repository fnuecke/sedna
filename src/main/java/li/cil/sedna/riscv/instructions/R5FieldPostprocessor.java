package li.cil.sedna.riscv.instructions;

import java.util.function.IntUnaryOperator;

public enum R5FieldPostprocessor {
    ADD_8((x) -> x + 8),

    ;

    private final IntUnaryOperator callback;

    R5FieldPostprocessor(final IntUnaryOperator callback) {
        this.callback = callback;
    }

    public int apply(final int value) {
        return callback.applyAsInt(value);
    }
}
