package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

public interface DecoderTreeLeafVisitor {
    void visitInstruction(final InstructionDeclaration declaration);

    void visitEnd();
}
