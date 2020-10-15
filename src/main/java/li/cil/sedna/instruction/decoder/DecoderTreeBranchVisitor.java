package li.cil.sedna.instruction.decoder;

public interface DecoderTreeBranchVisitor {
    void visit(final int count, final DecoderTreeNodeArguments arguments);

    DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern);

    void visitEnd();
}
