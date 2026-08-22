package li.cil.sedna.z80;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDeclarationLoader;
import li.cil.sedna.instruction.decoder.DecoderTree;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public final class Z80Instructions {
    private static final Logger LOGGER = LogManager.getLogger(Z80Instructions.class);

    private static final ArrayList<InstructionDeclaration> DECLARATIONS = new ArrayList<>();
    private static final AbstractDecoderTreeNode DECODER_TREE;

    static {
        try (final InputStream stream = Z80Instructions.class.getResourceAsStream("/z80/instructions.txt")) {
            if (stream == null) {
                throw new IOException("File not found.");
            }
            DECLARATIONS.addAll(InstructionDeclarationLoader.load(stream));
        } catch (final Throwable e) {
            LOGGER.error("Failed loading Z80 instruction declarations.", e);
        }

        DECODER_TREE = DecoderTree.create(DECLARATIONS);
    }

    public static ArrayList<InstructionDeclaration> getDeclarations() {
        return DECLARATIONS;
    }

    public static AbstractDecoderTreeNode getDecoderTree() {
        return DECODER_TREE;
    }
}
