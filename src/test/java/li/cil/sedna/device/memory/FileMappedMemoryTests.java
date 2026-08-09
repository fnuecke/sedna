package li.cil.sedna.device.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class FileMappedMemoryTests {
    private static final int SIZE = 4096;

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    public void lengthIsTheRequestedSize() throws Exception {
        try (final FileMappedMemory memory = create("length")) {
            assertEquals(SIZE, memory.getLength());
        }
    }

    @Test
    public void storeAndLoadRoundTripAtEverySize() throws Exception {
        try (final FileMappedMemory memory = create("roundtrip")) {
            memory.store(0, 0x12, Sizes.SIZE_8_LOG2);
            memory.store(8, 0x1234, Sizes.SIZE_16_LOG2);
            memory.store(16, 0x12345678, Sizes.SIZE_32_LOG2);
            memory.store(24, 0x123456789ABCDEF0L, Sizes.SIZE_64_LOG2);

            assertEquals(0x12, memory.load(0, Sizes.SIZE_8_LOG2));
            assertEquals(0x1234, memory.load(8, Sizes.SIZE_16_LOG2));
            assertEquals(0x12345678, memory.load(16, Sizes.SIZE_32_LOG2));
            assertEquals(0x123456789ABCDEF0L, memory.load(24, Sizes.SIZE_64_LOG2));
        }
    }

    @Test
    public void theLastWordIsAddressable() throws Exception {
        try (final FileMappedMemory memory = create("last-word")) {
            memory.store(SIZE - 8, 0x0BADC0DECAFEF00DL, Sizes.SIZE_64_LOG2);
            assertEquals(0x0BADC0DECAFEF00DL, memory.load(SIZE - 8, Sizes.SIZE_64_LOG2));
        }
    }

    @Test
    public void accessesOutsideTheMappingAreRejected() throws Exception {
        try (final FileMappedMemory memory = create("bounds")) {
            assertThrows(MemoryAccessException.class, () -> memory.load(SIZE, Sizes.SIZE_8_LOG2));
            assertThrows(MemoryAccessException.class, () -> memory.load(SIZE - 7, Sizes.SIZE_64_LOG2));
            assertThrows(MemoryAccessException.class, () -> memory.load(-1, Sizes.SIZE_8_LOG2));
            assertThrows(MemoryAccessException.class, () -> memory.store(SIZE, 0, Sizes.SIZE_8_LOG2));
            assertThrows(MemoryAccessException.class, () -> memory.store(-1, 0, Sizes.SIZE_8_LOG2));
        }
    }

    @Test
    public void accessesAfterCloseAreRejected() throws Exception {
        final FileMappedMemory memory = create("closed");
        memory.close();

        assertEquals(0, memory.getLength());
        assertThrows(MemoryAccessException.class, () -> memory.load(0, Sizes.SIZE_8_LOG2));
        assertThrows(MemoryAccessException.class, () -> memory.store(0, 0, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void contentsPersistInTheBackingFile() throws Exception {
        final File file = tempDir.resolve("persisted").toFile();

        try (final FileMappedMemory memory = new FileMappedMemory(SIZE, file)) {
            memory.store(32, 0x0123456789ABCDEFL, Sizes.SIZE_64_LOG2);
        }

        assertEquals(SIZE, Files.size(file.toPath()));

        try (final FileMappedMemory reopened = new FileMappedMemory(SIZE, file)) {
            assertEquals(0x0123456789ABCDEFL, reopened.load(32, Sizes.SIZE_64_LOG2));
        }
    }

    private FileMappedMemory create(final String name) throws IOException {
        return new FileMappedMemory(SIZE, tempDir.resolve(name).toFile());
    }
}
