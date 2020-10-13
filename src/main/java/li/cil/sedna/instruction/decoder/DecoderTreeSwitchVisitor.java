package li.cil.sedna.instruction.decoder;

public interface DecoderTreeSwitchVisitor {
    void visit(final DecoderTreeSwitchNode node);

    DecoderTreeVisitor visitSwitchCase(final DecoderTreeSwitchNode node, final int index);

    void visitEnd();
}
