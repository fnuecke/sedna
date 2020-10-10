package li.cil.sedna.riscv.instructions;

final class R5InstructionFieldMapping {
    public final int srcMSB;
    public final int srcLSB;
    public final int dstLSB;
    public final boolean signExtend;

    R5InstructionFieldMapping(final int srcMSB, final int srcLSB, final int dstLSB, final boolean signExtend) {
        this.srcMSB = srcMSB;
        this.srcLSB = srcLSB;
        this.dstLSB = dstLSB;
        this.signExtend = signExtend;
    }

    @Override
    public String toString() {
        return (signExtend ? "s" : "") + srcMSB + ":" + srcLSB + "@" + dstLSB;
    }
}
