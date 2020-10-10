package li.cil.sedna.riscv.instructions;

public final class R5ConstantInstructionArgument implements R5InstructionArgument {
    private final int value;

    R5ConstantInstructionArgument(final int value) {
        this.value = value;
    }

    @Override
    public int get(final int opcode) {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
