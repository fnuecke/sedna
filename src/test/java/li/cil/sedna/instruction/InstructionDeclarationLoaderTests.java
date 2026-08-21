package li.cil.sedna.instruction;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public final class InstructionDeclarationLoaderTests {
    @Test
    public void patternWidthDefinesInstructionSize() throws IOException {
        final ArrayList<InstructionDeclaration> declarations = load("""
                field imm8 15:8
                field imm16 23:8
                inst ONE | 00000001 |
                inst TWO | ........ 00111110 | imm=imm8
                inst THREE | ........ ........ 00100001 | imm=imm16
                inst FOUR | ******** ........ ........ 00110111 | imm=imm16
                """);

        assertEquals(4, declarations.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, declarations.get(i).size);
        }
    }

    @Test
    public void patternsAreRightAligned() throws IOException {
        final ArrayList<InstructionDeclaration> declarations = load("""
                field imm16 23:8
                inst LD_HL_NN | ........ ........ 00100001 | imm=imm16
                """);

        final InstructionDeclaration declaration = declarations.get(0);
        assertEquals(0x21, declaration.pattern);
        assertEquals(0xFF, declaration.patternMask);
    }

    @Test
    public void partialByteWidthIsRejected() {
        final IOException e = assertThrows(IOException.class, () -> load("""
                inst BAD | 0000 |
                """));
        assertTrue(e.getCause().getMessage().contains("multiple of 8"));
    }

    @Test
    public void overlongPatternIsRejected() {
        final IOException e = assertThrows(IOException.class, () -> load("""
                inst BAD | 000000000 ........ ........ ........ |
                """));
        assertTrue(e.getCause().getMessage().contains("too long"));
    }

    @Test
    public void unaccountedBitsAreRejected() {
        assertThrows(IOException.class, () -> load("""
                inst BAD | ........ 00111110 |
                """));
    }

    @Test
    public void fieldBeyondInstructionWidthIsRejected() {
        final IOException e = assertThrows(IOException.class, () -> load("""
                field imm16 23:8
                inst BAD | 00111110 | imm=imm16
                """));
        assertTrue(e.getCause().getMessage().contains("beyond the instruction width"));
    }

    private static ArrayList<InstructionDeclaration> load(final String declarations) throws IOException {
        return InstructionDeclarationLoader.load(new ByteArrayInputStream(declarations.getBytes(StandardCharsets.UTF_8)));
    }
}
