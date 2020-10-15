package li.cil.sedna.instruction.decoder;

public interface DecoderTreeVisitor {
    DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node);

    DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node);

    DecoderTreeLeafVisitor visitInstruction();

    void visitEnd();
}
