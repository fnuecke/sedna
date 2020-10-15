package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;
import java.util.stream.Stream;

public abstract class AbstractDecoderTreeNode {
    AbstractDecoderTreeNode() {
    }

    public abstract int getMaxDepth();

    public abstract int getMask();

    public abstract int getPattern();

    public abstract DecoderTreeNodeFieldInstructionArguments getArguments();

    public abstract Stream<InstructionDeclaration> getInstructions();

    @Nullable
    public abstract InstructionDeclaration query(final int instruction);

    public abstract void accept(final DecoderTreeVisitor visitor);
}
