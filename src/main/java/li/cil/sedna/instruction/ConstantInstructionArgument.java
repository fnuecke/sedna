package li.cil.sedna.instruction;

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
}
