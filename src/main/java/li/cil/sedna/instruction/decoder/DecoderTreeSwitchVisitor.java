package li.cil.sedna.instruction.decoder;

public interface DecoderTreeSwitchVisitor {
    void visit(final DecoderTreeSwitchNode node);

    DecoderTreeVisitor visitSwitchCase(final int index, final int pattern);

    void visitEnd();
}
