package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;

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
                final DecoderTreeVisitor switchCaseVisitor = switchVisitor.visitSwitchCase(i, children[i].getPattern() & getMask());
                if (switchCaseVisitor != null) {
                    children[i].accept(switchCaseVisitor);
                }
            }

            switchVisitor.visitEnd();
        }

        visitor.visitEnd();
    }

    @Nullable
    @Override
    public InstructionDeclaration query(final int instruction) {
        for (final AbstractDecoderTreeNode child : children) {
            if ((instruction & getMask()) == (child.getPattern() & getMask())) {
                return child.query(instruction);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "[SWITCH]";
    }
}