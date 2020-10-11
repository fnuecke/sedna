package li.cil.sedna.instruction.decoder;

public interface DecoderTreeBranchVisitor {
    void visit(final int count);

    DecoderTreeLeafVisitor visitBranchCase(final int index, final int mask, final int pattern);

    void visitEnd();
}
