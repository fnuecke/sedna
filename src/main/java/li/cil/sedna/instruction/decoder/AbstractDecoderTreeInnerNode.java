package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.FieldInstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

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
    public DecoderTreeNodeFieldInstructionArguments arguments() {
        int totalLeafCount = 0;
        final HashMap<FieldInstructionArgument, ArrayList<DecoderTreeNodeFieldInstructionArguments.Entry>> childEntries = new HashMap<>();
        for (final AbstractDecoderTreeNode child : children) {
            final DecoderTreeNodeFieldInstructionArguments childArguments = child.arguments();
            totalLeafCount += childArguments.totalLeafCount;
            childArguments.arguments.forEach((argument, entry) -> {
                childEntries.computeIfAbsent(argument, arg -> new ArrayList<>()).add(entry);
            });
        }

        final HashMap<FieldInstructionArgument, DecoderTreeNodeFieldInstructionArguments.Entry> entries = new HashMap<>();
        childEntries.forEach((argument, childEntriesForArgument) -> {
            int count = 0;
            final HashSet<String> names = new HashSet<>();
            for (final DecoderTreeNodeFieldInstructionArguments.Entry entry : childEntriesForArgument) {
                count += entry.count;
                names.addAll(entry.names);
            }
            entries.put(argument, new DecoderTreeNodeFieldInstructionArguments.Entry(count, new ArrayList<>(names)));
        });

        return new DecoderTreeNodeFieldInstructionArguments(totalLeafCount, entries);
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
