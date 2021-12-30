package li.cil.sedna.instruction;

import java.util.function.IntUnaryOperator;

public enum FieldPostprocessor {
    NONE((x) -> x),
    ADD_8((x) -> x + 8),
    ;

    private final IntUnaryOperator callback;

    FieldPostprocessor(final IntUnaryOperator callback) {
        this.callback = callback;
    }

    public int apply(final int value) {
        return callback.applyAsInt(value);
    }
}
