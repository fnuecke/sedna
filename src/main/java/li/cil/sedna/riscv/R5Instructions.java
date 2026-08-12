package li.cil.sedna.riscv;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDeclarationLoader;
import li.cil.sedna.instruction.decoder.DecoderTree;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public final class R5Instructions {
    private static final Logger LOGGER = LogManager.getLogger(R5Instructions.class);

    public static final Spec RV32 = new Spec("/riscv/instructions32.txt");
    public static final Spec RV64 = new Spec("/riscv/instructions64.txt");

    public static AbstractDecoderTreeNode getDecoderTree() {
        return RV64.getDecoderTree();
    }

    public static final class Spec {
        private final ArrayList<InstructionDeclaration> DECLARATIONS = new ArrayList<>();
        private final AbstractDecoderTreeNode DECODER_TREE;

        public Spec(final String instructionsFile) {
            try (final InputStream stream = R5Instructions.class.getResourceAsStream(instructionsFile)) {
                if (stream == null) {
                    throw new IOException("File not found.");
                }
                DECLARATIONS.addAll(InstructionDeclarationLoader.load(stream));
            } catch (final Throwable e) {
                LOGGER.error("Failed loading RISC-V instruction declarations.", e);
            }

            DECODER_TREE = DecoderTree.create(DECLARATIONS);
        }

        public ArrayList<InstructionDeclaration> getDeclarations() {
            return DECLARATIONS;
        }

        public AbstractDecoderTreeNode getDecoderTree() {
            return DECODER_TREE;
        }
    }
}
