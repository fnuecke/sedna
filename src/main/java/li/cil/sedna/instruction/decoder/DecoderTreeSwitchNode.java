package li.cil.sedna.instruction.decoder;

public final class DecoderTreeSwitchNode extends AbstractDecoderTreeInnerNode {
    DecoderTreeSwitchNode(final AbstractDecoderTreeNode[] children) {
        super(children);
    }

    @Override
    public void accept(final DecoderTreeVisitor visitor) {
        final DecoderTreeSwitchVisitor switchVisitor = visitor.visitSwitch();
        if (switchVisitor != null) {
            switchVisitor.visit(this);
            for (int i = 0; i < children.length; i++) {
                final DecoderTreeVisitor switchCaseVisitor = switchVisitor.visitSwitchCase(i, children[i].pattern() & mask());
                if (switchCaseVisitor != null) {
                    children[i].accept(switchCaseVisitor);
                }
            }

            switchVisitor.visitEnd();
        }

        visitor.visitEnd();
    }

    @Override
    public String toString() {
        return "[SWITCH]";
    }
}
