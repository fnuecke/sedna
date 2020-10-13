package li.cil.sedna.instruction.decoder;

public final class DecoderTreeBranchNode extends AbstractDecoderTreeInnerNode {
    DecoderTreeBranchNode(final AbstractDecoderTreeNode[] children) {
        super(children);
    }

    @Override
    public void accept(final DecoderTreeVisitor visitor) {
        final DecoderTreeBranchVisitor branchVisitor = visitor.visitBranch();
        if (branchVisitor != null) {
            branchVisitor.visit(children.length);
            for (int i = 0; i < children.length; i++) {
                final DecoderTreeVisitor branchCaseVisitor = branchVisitor.visitBranchCase(i, children[i].mask(), children[i].pattern());
                if (branchCaseVisitor != null) {
                    children[i].accept(branchCaseVisitor);
                }
            }

            branchVisitor.visitEnd();
        }

        visitor.visitEnd();
    }

    @Override
    public String toString() {
        return "[BRANCH]";
    }
}
