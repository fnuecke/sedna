package li.cil.sedna.riscv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class R5CPUImplGeneratedCodeTests {
    @Test
    public void generatedImplementationIsUpToDate() throws IOException {
        final Path path = Path.of("src/main/java/li/cil/sedna/riscv/R5CPUImpl.java");
        assertEquals(R5CPUImplGenerator.generateSource(), Files.readString(path),
                "R5CPUImpl.java does not match what the current instruction declarations and " +
                        "definitions generate. Run `./gradlew generateDecoder` and commit the result.");
    }
}
