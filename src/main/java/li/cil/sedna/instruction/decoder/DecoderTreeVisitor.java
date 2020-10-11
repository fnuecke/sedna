package li.cil.sedna.instruction.decoder;

public interface DecoderTreeVisitor {
    DecoderTreeSwitchVisitor visitSwitch(final int mask);

    DecoderTreeBranchVisitor visitBranch();

    DecoderTreeLeafVisitor visitInstruction();

    void visitEnd();
}
