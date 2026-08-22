package li.cil.sedna.z80;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class Z80CPUImplGeneratedCodeTests {
    @Test
    public void generatedImplementationIsUpToDate() throws IOException {
        final Path path = Path.of("src/main/java/li/cil/sedna/z80/Z80CPUImpl.java");
        assertEquals(Z80CPUImplGenerator.generateSource(), Files.readString(path),
                "Z80CPUImpl.java does not match what the current instruction declarations and " +
                        "definitions generate. Run `./gradlew generateZ80Decoder` and commit the result.");
    }
}
