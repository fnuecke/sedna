package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;

public abstract class AbstractDecoderTreeNode {
    AbstractDecoderTreeNode() {
    }

    public abstract int maxDepth();

    @Nullable
    public abstract InstructionDeclaration findDeclaration(final int instruction);

    public abstract void accept(final DecoderTreeVisitor visitor);
}
