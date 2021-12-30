package li.cil.sedna.instruction.argument;

import li.cil.sedna.instruction.FieldPostprocessor;
import li.cil.sedna.instruction.InstructionFieldMapping;
import li.cil.sedna.utils.BitUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

public final class FieldInstructionArgument implements InstructionArgument {
    public final ArrayList<InstructionFieldMapping> mappings;
    public final FieldPostprocessor postprocessor;

    public FieldInstructionArgument(final ArrayList<InstructionFieldMapping> mappings, final FieldPostprocessor postprocessor) {
        this.mappings = mappings;
        this.postprocessor = postprocessor;
        Collections.sort(mappings);
    }

    @Override
    public int get(final int instruction) {
        int value = 0;
        for (final InstructionFieldMapping mapping : mappings) {
            int part = BitUtils.getField(instruction, mapping.srcLSB, mapping.srcMSB, mapping.dstLSB);
            if (mapping.signExtend) {
                part = BitUtils.extendSign(part, mapping.dstLSB + (mapping.srcMSB - mapping.srcLSB) + 1);
            }
            value |= part;
        }
        value = postprocessor.apply(value);
        return value;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final FieldInstructionArgument that = (FieldInstructionArgument) o;
        return mappings.equals(that.mappings) &&
            postprocessor == that.postprocessor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mappings, postprocessor);
    }
}
