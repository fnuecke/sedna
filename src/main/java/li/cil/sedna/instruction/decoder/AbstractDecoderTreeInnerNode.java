package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

public abstract class AbstractDecoderTreeInnerNode extends AbstractDecoderTreeNode {
    public final AbstractDecoderTreeNode[] children;

    protected AbstractDecoderTreeInnerNode(final AbstractDecoderTreeNode[] children) {
        this.children = children;
    }

    @Override
    public int maxDepth() {
        int maxDepth = 0;
        for (final AbstractDecoderTreeNode child : children) {
            maxDepth = Math.max(maxDepth, child.maxDepth());
        }
        return 1 + maxDepth;
    }

    @Override
    public int mask() {
        int mask = children[0].mask();
        for (final AbstractDecoderTreeNode child : children) {
            mask &= child.mask();
        }
        return mask;
    }

    @Override
    public int pattern() {
        return children[0].pattern() & mask();
    }

    @Override
    public Collection<Map.Entry<String, InstructionArgument>> arguments() {
        final HashSet<Map.Entry<String, InstructionArgument>> arguments = new HashSet<>();
        for (final AbstractDecoderTreeNode child : children) {
            arguments.addAll(child.arguments());
        }
        return arguments;
    }

    @Nullable
    @Override
    public InstructionDeclaration query(final int instruction) {
        for (final AbstractDecoderTreeNode child : children) {
            if ((instruction & mask()) == (child.pattern() & mask())) {
                return child.query(instruction);
            }
        }
        return null;
    }
}
