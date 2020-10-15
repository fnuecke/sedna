package li.cil.sedna.instruction.argument;

import java.util.Objects;

public final class ConstantInstructionArgument implements InstructionArgument {
    public final int value;

    ConstantInstructionArgument(final int value) {
        this.value = value;
    }

    @Override
    public int get(final int instruction) {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ConstantInstructionArgument that = (ConstantInstructionArgument) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
