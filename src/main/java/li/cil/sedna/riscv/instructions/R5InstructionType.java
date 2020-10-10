package li.cil.sedna.riscv.instructions;

import java.util.HashMap;

public enum R5InstructionType {
    HINT("hint"),
    ILLEGAL("illegal"),
    REGULAR("inst"),

    ;

    public static final HashMap<String, R5InstructionType> BY_KEYWORD = initializeMap();

    private final String keyword;

    R5InstructionType(final String branch) {
        this.keyword = branch;
    }

    private static HashMap<String, R5InstructionType> initializeMap() {
        final HashMap<String, R5InstructionType> result = new HashMap<>();
        for (final R5InstructionType value : values()) {
            result.put(value.keyword, value);
        }
        return result;
    }
}
