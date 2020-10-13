package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;

public final class DecoderTreeBranchNode extends AbstractDecoderTreeNode {
    public final InstructionDeclaration[] declarations;

    DecoderTreeBranchNode(final InstructionDeclaration[] declarations) {
        this.declarations = declarations;
    }

    @Override
    public int maxDepth() {
        return 1;
    }

    @Nullable
    @Override
    public InstructionDeclaration findDeclaration(final int instruction) {
        for (final InstructionDeclaration declaration : declarations) {
            if ((instruction & declaration.patternMask) == declaration.pattern) {
                return declaration;
            }
        }
        return null;
    }

    @Override
    public void accept(final DecoderTreeVisitor visitor) {
        final DecoderTreeBranchVisitor branchVisitor = visitor.visitBranch();
        if (branchVisitor != null) {
            branchVisitor.visit(declarations.length);
            for (int i = 0, declarationsLength = declarations.length; i < declarationsLength; i++) {
                final InstructionDeclaration declaration = declarations[i];
                final DecoderTreeLeafVisitor branchCaseVisitor = branchVisitor.visitBranchCase(i, declaration.patternMask, declaration.pattern);
                if (branchCaseVisitor != null) {
                    branchCaseVisitor.visitInstruction(declaration);
                    branchCaseVisitor.visitEnd();
                }
            }

            branchVisitor.visitEnd();
        }

        visitor.visitEnd();
    }

    @Override
    public String toString() {
        return "[BRANCH]";
    }
}
