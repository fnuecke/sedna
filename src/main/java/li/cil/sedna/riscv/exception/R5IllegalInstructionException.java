package li.cil.sedna.riscv.exception;

public final class R5IllegalInstructionException extends Exception {
    private final int instruction;

    public R5IllegalInstructionException() {
        this(0);
    }

    public R5IllegalInstructionException(final int instruction) {
        // This exception is for control-flow, so it's fired a lot; skip stacktrace.
        super(null, null, false, false);
        this.instruction = instruction;
    }

    public int getInstruction() {
        return instruction;
    }
}
