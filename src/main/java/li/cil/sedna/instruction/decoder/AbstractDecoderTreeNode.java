package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;

public abstract class AbstractDecoderTreeNode {
    AbstractDecoderTreeNode() {
    }

    public abstract int maxDepth();

    public abstract int mask();

    public abstract int pattern();

    public abstract DecoderTreeNodeFieldInstructionArguments arguments();

    @Nullable
    public abstract InstructionDeclaration query(final int instruction);

    public abstract void accept(final DecoderTreeVisitor visitor);
}
