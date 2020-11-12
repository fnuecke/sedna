package li.cil.sedna.riscv;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDeclarationLoader;
import li.cil.sedna.instruction.InstructionDefinition;
import li.cil.sedna.instruction.InstructionDefinitionLoader;
import li.cil.sedna.instruction.decoder.DecoderTree;
import li.cil.sedna.instruction.decoder.PrintStreamDecoderTreeVisitor;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

public final class R5Instructions {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String RISCV_INSTRUCTIONS_FILE = "/riscv/instructions.txt";
    private static final ArrayList<InstructionDeclaration> DECLARATIONS = new ArrayList<>();
    private static final HashMap<InstructionDeclaration, InstructionDefinition> DEFINITIONS = new HashMap<>();
    private static final AbstractDecoderTreeNode DECODER_TREE;

    static {
        try (final InputStream stream = R5Instructions.class.getResourceAsStream(RISCV_INSTRUCTIONS_FILE)) {
            if (stream == null) {
                throw new IOException("File not found.");
            }
            DECLARATIONS.addAll(InstructionDeclarationLoader.load(stream));
        } catch (final Throwable e) {
            LOGGER.error("Failed loading RISC-V instruction declarations.", e);
        }

        try {
            DEFINITIONS.putAll(InstructionDefinitionLoader.load(R5CPUTemplate.class, DECLARATIONS));
        } catch (final Throwable e) {
            LOGGER.error("Failed loading RISC-V instruction definitions.", e);
        }

        DECODER_TREE = DecoderTree.create(DECLARATIONS);
    }

    public static ArrayList<InstructionDeclaration> getDeclarations() {
        return DECLARATIONS;
    }

    @Nullable
    public static InstructionDefinition getDefinition(final InstructionDeclaration declaration) {
        return DEFINITIONS.get(declaration);
    }

    public static AbstractDecoderTreeNode getDecoderTree() {
        return DECODER_TREE;
    }

    public static void main(final String[] args) {
        final AbstractDecoderTreeNode tree = R5Instructions.getDecoderTree();
        tree.accept(new PrintStreamDecoderTreeVisitor(tree.getMaxDepth()));
    }
}
