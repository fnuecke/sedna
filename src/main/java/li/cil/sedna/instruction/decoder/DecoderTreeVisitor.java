package li.cil.sedna.instruction.decoder;

public interface DecoderTreeVisitor {
    DecoderTreeSwitchVisitor visitSwitch();

    DecoderTreeBranchVisitor visitBranch();

    DecoderTreeLeafVisitor visitInstruction();

    void visitEnd();
}
