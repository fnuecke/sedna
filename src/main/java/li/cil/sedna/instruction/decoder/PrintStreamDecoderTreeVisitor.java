package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeBranchNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeSwitchNode;
import li.cil.sedna.utils.BitUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.PrintStream;

public final class PrintStreamDecoderTreeVisitor implements DecoderTreeVisitor {
    private final PrintStream stream;
    private final int maxDepth;

    public PrintStreamDecoderTreeVisitor() {
        this(System.out);
    }

    public PrintStreamDecoderTreeVisitor(final int alignment) {
        this(System.out, alignment);
    }

    public PrintStreamDecoderTreeVisitor(final PrintStream stream) {
        this(stream, 0);
    }

    public PrintStreamDecoderTreeVisitor(final PrintStream stream, final int alignment) {
        this.stream = stream;
        this.maxDepth = alignment;
    }

    @Override
    public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
        return new SwitchVisitor(0, 0, 0);
    }

    @Override
    public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
        return new BranchVisitor(0, 0, 0);
    }

    @Override
    public DecoderTreeLeafVisitor visitInstruction() {
        return new LeafVisitor(0, 0, 0, ~0, true);
    }

    @Override
    public void visitEnd() {
    }

    private void printNodeHeader(final int depth, final int branchMask, final boolean hasChildren, final boolean isLastChild) {
        for (int branch = (1 << depth); branch != 0; branch = branch >>> 1) {
            if (branch == 1) {
                if (isLastChild) {
                    stream.print("└");
                } else {
                    stream.print("├");
                }
                if (hasChildren) {
                    stream.print("─┬─");
                    for (int j = depth + 1; j < maxDepth; j++) { // +1 because we already printed 2 more chars.
                        stream.print("──");
                    }
                } else {
                    stream.print("─");
                    for (int j = depth; j < maxDepth; j++) {
                        stream.print("──");
                    }
                }
                stream.print("╴ ");
            } else if ((branch & branchMask) != 0) {
                stream.print("│ ");
            } else {
                stream.print("  ");
            }
        }
    }

    private static char[] formatMasked(final int value, final int mask, final int consumedMask) {
        final char[] chars = StringUtils.leftPad(Integer.toBinaryString(value), 32, '0').toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if ((mask & (1 << i)) == 0) {
                chars[chars.length - 1 - i] = '.';
            }
            if ((consumedMask & (1 << i)) != 0) {
                chars[chars.length - 1 - i] = ' ';
            }
        }
        return chars;
    }

    private static int instructionSizeToMask(final int size) {
        return (int) BitUtils.maskFromRange(0, size * 8 - 1);
    }

    private final class InnerNodeVisitor implements DecoderTreeVisitor {
        private final int depth;
        private final int processedMask;
        private final int branchMask;
        private final int parentMask;
        private final int pattern;
        private final boolean isLastChild;

        public InnerNodeVisitor(final int pattern, final int parentMask, final int processedMask, final int depth, final int branchMask, final boolean isLastChild) {
            this.depth = depth;
            this.processedMask = processedMask;
            this.branchMask = branchMask;
            this.parentMask = parentMask;
            this.pattern = pattern;
            this.isLastChild = isLastChild;
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
            printNodeHeader(depth, branchMask, true, isLastChild);
            stream.print(formatMasked(pattern, parentMask, processedMask));
            stream.print("    ");
            stream.print(StringUtils.rightPad("[SWITCH]", 12));

            return new SwitchVisitor(depth, processedMask | parentMask, branchMask);
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
            printNodeHeader(depth, branchMask, true, isLastChild);
            stream.print(formatMasked(pattern, parentMask, processedMask));
            stream.print("    ");
            stream.print(StringUtils.rightPad("[BRANCH]", 12));

            return new BranchVisitor(depth, processedMask | parentMask, branchMask);
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(depth, processedMask, branchMask, parentMask, isLastChild);
        }

        @Override
        public void visitEnd() {
        }
    }

    private final class SwitchVisitor implements DecoderTreeSwitchVisitor {
        private final int depth;
        private final int processedMask;
        private final int branchMask;
        private int switchMask;
        private int count;

        public SwitchVisitor(final int depth, final int processedMask, final int branchMask) {
            this.depth = depth;
            this.processedMask = processedMask;
            this.branchMask = branchMask;
        }

        @Override
        public void visit(final int mask, final int[] patterns, final DecoderTreeNodeArguments arguments) {
            this.count = patterns.length;
            this.switchMask = mask & ~processedMask;

            if (depth > 0) {
                for (final DecoderTreeNodeArguments.Entry entry : arguments.arguments.values()) {
                    stream.print(String.join("=", entry.names));
                    stream.printf(" (%d/%d) ", entry.count, arguments.totalLeafCount);
                }
                stream.println();
            }
        }

        @Override
        public DecoderTreeVisitor visitSwitchCase(final int index, final int pattern) {
            final boolean isLastChild = index == count - 1;
            return new InnerNodeVisitor(pattern, switchMask, processedMask, depth + 1, (branchMask << 1) | (isLastChild ? 0 : 1), isLastChild);
        }

        @Override
        public void visitEnd() {
        }
    }

    private final class BranchVisitor implements DecoderTreeBranchVisitor {
        private final int depth;
        private final int processedMask;
        private final int branchMask;
        private int count;

        public BranchVisitor(final int depth, final int processedMask, final int branchMask) {
            this.depth = depth;
            this.processedMask = processedMask;
            this.branchMask = branchMask;
        }

        @Override
        public void visit(final int count, final DecoderTreeNodeArguments arguments) {
            this.count = count;

            if (depth > 0) {
                for (final DecoderTreeNodeArguments.Entry entry : arguments.arguments.values()) {
                    stream.print(String.join("=", entry.names));
                    stream.printf(" (%d/%d) ", entry.count, arguments.totalLeafCount);
                }
                stream.println();
            }
        }

        @Override
        public DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern) {
            final boolean isLastChild = index == count - 1;
            return new InnerNodeVisitor(pattern, mask, processedMask, depth + 1, (branchMask << 1) | (isLastChild ? 0 : 1), isLastChild);
        }

        @Override
        public void visitEnd() {
        }
    }

    private final class LeafVisitor implements DecoderTreeLeafVisitor {
        private final int depth;
        private final int consumedMask;
        private final int branchMask;
        private final int parentMask;
        private final boolean isLastChild;

        public LeafVisitor(final int depth, final int consumedMask, final int branchMask, final int parentMask, final boolean isLastChild) {
            this.depth = depth;
            this.consumedMask = consumedMask;
            this.branchMask = branchMask;
            this.parentMask = parentMask;
            this.isLastChild = isLastChild;
        }

        @Override
        public void visitInstruction(final InstructionDeclaration declaration) {
            printNodeHeader(depth, branchMask, false, isLastChild);
            stream.print(formatMasked(declaration.pattern, parentMask & declaration.patternMask, consumedMask | ~instructionSizeToMask(declaration.size)));
            stream.print("    ");
            stream.print(StringUtils.rightPad(declaration.displayName, 12));
            stream.println(String.join(" ", declaration.arguments.keySet()));
        }

        @Override
        public void visitEnd() {
        }
    }
}
