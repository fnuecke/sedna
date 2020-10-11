package li.cil.sedna.instruction;

import li.cil.sedna.utils.BitUtils;

import java.util.ArrayList;

public final class FieldInstructionArgument implements InstructionArgument {
    private final ArrayList<InstructionFieldMapping> mappings;
    private final FieldPostprocessor postprocessor;

    FieldInstructionArgument(final ArrayList<InstructionFieldMapping> mappings, final FieldPostprocessor postprocessor) {
        this.mappings = mappings;
        this.postprocessor = postprocessor;
    }

    @Override
    public int get(final int opcode) {
        int value = 0;
        for (final InstructionFieldMapping mapping : mappings) {
            int part = BitUtils.getField(opcode, mapping.srcLSB, mapping.srcMSB, mapping.dstLSB);
            if (mapping.signExtend) {
                part = BitUtils.extendSign(part, mapping.dstLSB + (mapping.srcMSB - mapping.srcLSB) + 1);
            }
            value |= part;
        }
        if (postprocessor != null) {
            value = postprocessor.apply(value);
        }
        return value;
    }
}
