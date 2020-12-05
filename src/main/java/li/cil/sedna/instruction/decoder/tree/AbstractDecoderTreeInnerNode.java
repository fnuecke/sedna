package li.cil.sedna.instruction.decoder.tree;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.argument.FieldInstructionArgument;
import li.cil.sedna.instruction.decoder.DecoderTreeNodeArguments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Stream;

public abstract class AbstractDecoderTreeInnerNode extends AbstractDecoderTreeNode {
    public final AbstractDecoderTreeNode[] children;
    private final int maxDepth;
    private final int mask;
    private final int pattern;

    AbstractDecoderTreeInnerNode(final AbstractDecoderTreeNode[] children) {
        this.children = children;

        int maxDepth = 0;
        for (final AbstractDecoderTreeNode child : children) {
            maxDepth = Math.max(maxDepth, child.getMaxDepth());
        }
        this.maxDepth = 1 + maxDepth;

        int mask = children[0].getMask();
        for (int i = 1; i < children.length; i++) {
            final AbstractDecoderTreeNode child = children[i];
            mask &= child.getMask();
        }
        this.mask = mask;

        this.pattern = children[0].getPattern() & mask;
    }

    @Override
    public int getMaxDepth() {
        return maxDepth;
    }

    @Override
    public int getMask() {
        return mask;
    }

    @Override
    public int getPattern() {
        return pattern;
    }

    @Override
    public DecoderTreeNodeArguments getArguments() {
        int totalLeafCount = 0;
        final HashMap<FieldInstructionArgument, ArrayList<DecoderTreeNodeArguments.Entry>> childEntries = new HashMap<>();
        for (final AbstractDecoderTreeNode child : children) {
            final DecoderTreeNodeArguments childArguments = child.getArguments();
            totalLeafCount += childArguments.totalLeafCount;
            childArguments.arguments.forEach((argument, entry) ->
                    childEntries.computeIfAbsent(argument, arg -> new ArrayList<>()).add(entry));
        }

        final HashMap<FieldInstructionArgument, DecoderTreeNodeArguments.Entry> entries = new HashMap<>();
        childEntries.forEach((argument, childEntriesForArgument) -> {
            int count = 0;
            final HashSet<String> names = new HashSet<>();
            for (final DecoderTreeNodeArguments.Entry entry : childEntriesForArgument) {
                count += entry.count;
                names.addAll(entry.names);
            }
            entries.put(argument, new DecoderTreeNodeArguments.Entry(count, new ArrayList<>(names)));
        });

        return new DecoderTreeNodeArguments(totalLeafCount, entries);
    }

    @Override
    public Stream<InstructionDeclaration> getInstructions() {
        return Arrays.stream(children).flatMap(AbstractDecoderTreeNode::getInstructions).distinct();
    }
}
