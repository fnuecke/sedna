package li.cil.sedna.vm.riscv.exception;

import li.cil.sedna.vm.riscv.R5;

public final class R5IllegalInstructionException extends R5Exception {
    private final int instruction;

    public R5IllegalInstructionException(final int instruction) {
        super(R5.EXCEPTION_ILLEGAL_INSTRUCTION);
        this.instruction = instruction;
    }

    @Override
    public int getExceptionValue() {
        return instruction;
    }
}
