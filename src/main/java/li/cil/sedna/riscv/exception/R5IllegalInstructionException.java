package li.cil.sedna.riscv.exception;

import li.cil.sedna.riscv.R5;

public final class R5IllegalInstructionException extends R5Exception {
    private final int instruction;

    public R5IllegalInstructionException() {
        this(null, 0);
    }

    public R5IllegalInstructionException(final R5IllegalInstructionException cause, final int instruction) {
        super(R5.EXCEPTION_ILLEGAL_INSTRUCTION, cause);
        this.instruction = instruction;
    }

    @Override
    public int getExceptionValue() {
        return instruction;
    }
}
