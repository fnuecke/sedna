package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.FieldInstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;

public final class DecoderTreeLeafNode extends AbstractDecoderTreeNode {
    public final InstructionDeclaration declaration;

    DecoderTreeLeafNode(final InstructionDeclaration declaration) {
        this.declaration = declaration;
    }

    @Override
    public int getMaxDepth() {
        return 0;
    }

    @Override
    public int getMask() {
        return declaration.patternMask;
    }

    @Override
    public int getPattern() {
        return declaration.pattern;
    }

    @Override
    public DecoderTreeNodeFieldInstructionArguments getArguments() {
        final HashMap<FieldInstructionArgument, ArrayList<String>> argumentNames = new HashMap<>();
        declaration.arguments.forEach((key, argument) -> {
            if (argument instanceof FieldInstructionArgument) {
                argumentNames.computeIfAbsent((FieldInstructionArgument) argument, arg -> new ArrayList<>()).add(key);
            }
        });

        final HashMap<FieldInstructionArgument, DecoderTreeNodeFieldInstructionArguments.Entry> arguments = new HashMap<>();
        argumentNames.forEach((key, value) -> arguments.put(key, new DecoderTreeNodeFieldInstructionArguments.Entry(1, value)));

        return new DecoderTreeNodeFieldInstructionArguments(1, arguments);
    }

    @Nullable
    @Override
    public InstructionDeclaration query(final int instruction) {
        if ((instruction & declaration.patternMask) == declaration.pattern) {
            return declaration;
        } else {
            return null;
        }
    }

    @Override
    public void accept(final DecoderTreeVisitor visitor) {
        final DecoderTreeLeafVisitor instructionVisitor = visitor.visitInstruction();
        if (instructionVisitor != null) {
            instructionVisitor.visitInstruction(declaration);
            instructionVisitor.visitEnd();
        }

        visitor.visitEnd();
    }

    @Override
    public String toString() {
        return declaration.toString();
    }
}
