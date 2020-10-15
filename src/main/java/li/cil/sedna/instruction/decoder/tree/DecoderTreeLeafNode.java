package li.cil.sedna.instruction.decoder.tree;

import li.cil.sedna.instruction.argument.FieldInstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.decoder.DecoderTreeLeafVisitor;
import li.cil.sedna.instruction.decoder.DecoderTreeNodeArguments;
import li.cil.sedna.instruction.decoder.DecoderTreeVisitor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Stream;

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
    public DecoderTreeNodeArguments getArguments() {
        final HashMap<FieldInstructionArgument, ArrayList<String>> argumentNames = new HashMap<>();
        declaration.arguments.forEach((key, argument) -> {
            if (argument instanceof FieldInstructionArgument) {
                argumentNames.computeIfAbsent((FieldInstructionArgument) argument, arg -> new ArrayList<>()).add(key);
            }
        });

        final HashMap<FieldInstructionArgument, DecoderTreeNodeArguments.Entry> arguments = new HashMap<>();
        argumentNames.forEach((key, value) -> arguments.put(key, new DecoderTreeNodeArguments.Entry(1, value)));

        return new DecoderTreeNodeArguments(1, arguments);
    }

    @Override
    public Stream<InstructionDeclaration> getInstructions() {
        return Stream.of(declaration);
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
