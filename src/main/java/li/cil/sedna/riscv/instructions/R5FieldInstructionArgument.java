package li.cil.sedna.riscv.instructions;

import li.cil.sedna.utils.BitUtils;

import java.util.ArrayList;

public final class R5FieldInstructionArgument implements R5InstructionArgument {
    private final ArrayList<R5InstructionFieldMapping> mappings;
    private final R5FieldPostprocessor postprocessor;

    R5FieldInstructionArgument(final ArrayList<R5InstructionFieldMapping> mappings, final R5FieldPostprocessor postprocessor) {
        this.mappings = mappings;
        this.postprocessor = postprocessor;
    }

    @Override
    public int get(final int opcode) {
        int value = 0;
        for (final R5InstructionFieldMapping mapping : mappings) {
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
