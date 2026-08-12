package li.cil.sedna.riscv;

import li.cil.sedna.instruction.decoder.PrintStreamDecoderTreeVisitor;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;

public final class R5DecoderTreePrinter {
    private R5DecoderTreePrinter() {
    }

    public static void main(final String[] args) {
        final AbstractDecoderTreeNode tree = R5Instructions.getDecoderTree();
        tree.accept(new PrintStreamDecoderTreeVisitor(tree.getMaxDepth()));
    }
}
