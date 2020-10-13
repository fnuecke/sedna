package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

public abstract class AbstractDecoderTreeNode {
    AbstractDecoderTreeNode() {
    }

    public abstract int maxDepth();

    public abstract int mask();

    public abstract int pattern();

    public abstract Collection<Map.Entry<String, InstructionArgument>> arguments();

    @Nullable
    public abstract InstructionDeclaration query(final int instruction);

    public abstract void accept(final DecoderTreeVisitor visitor);
}
