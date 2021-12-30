package li.cil.sedna.instruction;

import java.util.Objects;

public final class InstructionFieldMapping implements Comparable<InstructionFieldMapping> {
    public final int srcMSB;
    public final int srcLSB;
    public final int dstLSB;
    public final boolean signExtend;

    InstructionFieldMapping(final int srcMSB, final int srcLSB, final int dstLSB, final boolean signExtend) {
        this.srcMSB = srcMSB;
        this.srcLSB = srcLSB;
        this.dstLSB = dstLSB;
        this.signExtend = signExtend;
    }

    @Override
    public int compareTo(final InstructionFieldMapping o) {
        int result = Integer.compare(srcLSB, o.srcLSB);
        if (result != 0) return result;
        result = Integer.compare(srcMSB, o.srcMSB);
        if (result != 0) return result;
        result = Integer.compare(dstLSB, o.dstLSB);
        if (result != 0) return result;
        return Boolean.compare(signExtend, o.signExtend);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final InstructionFieldMapping that = (InstructionFieldMapping) o;
        return srcMSB == that.srcMSB &&
            srcLSB == that.srcLSB &&
            dstLSB == that.dstLSB &&
            signExtend == that.signExtend;
    }

    @Override
    public int hashCode() {
        return Objects.hash(srcMSB, srcLSB, dstLSB, signExtend);
    }

    @Override
    public String toString() {
        return (signExtend ? "s" : "") + srcMSB + ":" + srcLSB + "@" + dstLSB;
    }
}
