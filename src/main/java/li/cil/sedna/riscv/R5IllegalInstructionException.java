package li.cil.sedna.riscv;

public final class R5IllegalInstructionException extends Exception {
    private final int instruction;

    public R5IllegalInstructionException() {
        this(0);
    }

    public R5IllegalInstructionException(final int instruction) {
        super();
        this.instruction = instruction;
    }

    public int getInstruction() {
        return instruction;
    }
}
