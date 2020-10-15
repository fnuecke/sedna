package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.decoder.tree.DecoderTreeBranchNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeSwitchNode;

public interface DecoderTreeVisitor {
    DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node);

    DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node);

    DecoderTreeLeafVisitor visitInstruction();

    void visitEnd();
}
