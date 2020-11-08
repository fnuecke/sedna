package li.cil.sedna.instruction;

import java.util.HashMap;

public enum InstructionType {
    NOP("nop"),
    ILLEGAL("illegal"),
    REGULAR("inst"),

    ;

    public static final HashMap<String, InstructionType> BY_KEYWORD = initializeMap();

    private final String keyword;

    InstructionType(final String branch) {
        this.keyword = branch;
    }

    private static HashMap<String, InstructionType> initializeMap() {
        final HashMap<String, InstructionType> result = new HashMap<>();
        for (final InstructionType value : values()) {
            result.put(value.keyword, value);
        }
        return result;
    }
}
