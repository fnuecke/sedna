package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDeclarationLoader;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class DecoderTreeTests {
    @Test
    public void mixedWidthDeclarationsPreferTheLongerMatch() throws IOException {
        // The Z80 prefix shape: a 1-byte instruction whose pattern is also the first byte of a
        // 2-byte instruction. The tree must order the wider (more specific) match first and fall
        // back to the 1-byte declaration when the second byte does not match.
        final ArrayList<InstructionDeclaration> declarations = load("""
                inst HALT | 01110110 |
                inst SHORT | 11011101 |
                inst LONG | 00100001 11011101 |
                """);
        final AbstractDecoderTreeNode tree = DecoderTree.create(declarations);

        assertEquals("HALT", tree.query(0x76).name);
        assertEquals("LONG", tree.query(0x21DD).name);
        assertEquals("SHORT", tree.query(0x00DD).name);
    }

    private static ArrayList<InstructionDeclaration> load(final String declarations) throws IOException {
        return InstructionDeclarationLoader.load(new ByteArrayInputStream(declarations.getBytes(StandardCharsets.UTF_8)));
    }
}
