package li.cil.sedna.instruction;

import li.cil.sedna.instruction.argument.InstructionArgument;

import java.util.LinkedHashMap;

public final class InstructionDeclaration {
    public final InstructionType type;
    public final int size;
    public final String name;
    public final String displayName;
    public final int lineNumber;
    public final int pattern;
    public final int patternMask;
    public final int unusedBits;
    public final LinkedHashMap<String, InstructionArgument> arguments;

    InstructionDeclaration(final InstructionType type,
                           final int size,
                           final String name,
                           final String displayName,
                           final int lineNumber,
                           final int pattern,
                           final int patternMask,
                           final int unusedBits,
                           final LinkedHashMap<String, InstructionArgument> arguments) {
        this.type = type;
        this.size = size;
        this.name = name;
        this.displayName = displayName;
        this.lineNumber = lineNumber;
        this.pattern = pattern;
        this.patternMask = patternMask;
        this.unusedBits = unusedBits;
        this.arguments = arguments;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
