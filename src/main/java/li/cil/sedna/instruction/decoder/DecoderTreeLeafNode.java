package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;

public final class DecoderTreeLeafNode extends AbstractDecoderTreeNode {
    public final InstructionDeclaration declaration;

    DecoderTreeLeafNode(final InstructionDeclaration declaration) {
        this.declaration = declaration;
    }

    @Override
    public int maxDepth() {
        return 0;
    }

    @Nullable
    @Override
    public InstructionDeclaration findDeclaration(final int instruction) {
        if ((instruction & declaration.patternMask) == declaration.pattern) {
            return declaration;
        } else {
            return null;
        }
    }

    @Override
    public void accept(final DecoderTreeVisitor visitor) {
        final DecoderTreeLeafVisitor instructionVisitor = visitor.visitInstruction();
        if (instructionVisitor != null) {
            instructionVisitor.visitInstruction(declaration);
            instructionVisitor.visitEnd();
        }

        visitor.visitEnd();
    }

    @Override
    public String toString() {
        return declaration.toString();
    }
}
