package li.cil.sedna.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public final class HostFileSystemTests {
    @TempDir
    java.nio.file.Path tempDir;

    private java.nio.file.Path exported;
    private HostFileSystem fileSystem;

    @BeforeEach
    public void setUp() throws IOException {
        exported = Files.createDirectory(tempDir.resolve("exported"));
        Files.createDirectory(tempDir.resolve("secrets"));
        Files.write(tempDir.resolve("secrets").resolve("password"), "hunter2".getBytes());
        Files.write(exported.resolve("visible"), "ok".getBytes());

        fileSystem = new HostFileSystem(exported.toFile());
    }

    @Test
    public void ordinaryPathsResolveNormally() {
        assertTrue(fileSystem.exists(new Path().resolve("visible")));
        assertFalse(fileSystem.exists(new Path().resolve("absent")));
    }

    @Test
    public void traversalAssembledFromPartsIsRefused() {
        final Path escape = new Path(Arrays.asList("..", "secrets", "password"));
        assertThrows(SecurityException.class, () -> fileSystem.exists(escape));
    }

    @Test
    public void traversalIsRefusedForEveryOperation() {
        final Path escape = new Path(Arrays.asList("..", "secrets", "password"));

        assertThrows(SecurityException.class, () -> fileSystem.exists(escape));
        assertThrows(SecurityException.class, () -> fileSystem.isDirectory(escape));
        assertThrows(SecurityException.class, () -> fileSystem.isReadable(escape));
        assertThrows(SecurityException.class, () -> fileSystem.isWritable(escape));
        assertThrows(SecurityException.class, () -> fileSystem.isExecutable(escape));
        assertThrows(SecurityException.class, () -> fileSystem.getUniqueId(escape));
        assertThrows(SecurityException.class, () -> fileSystem.getAttributes(escape));
        assertThrows(SecurityException.class, () -> fileSystem.mkdir(escape));
        assertThrows(SecurityException.class, () -> fileSystem.unlink(escape));
        assertThrows(SecurityException.class, () -> fileSystem.open(escape, FileMode.READ));
        assertThrows(SecurityException.class, () -> fileSystem.create(escape, FileMode.WRITE));
    }

    @Test
    public void traversalIsRefusedForBothEndsOfARename() {
        final Path inside = new Path().resolve("visible");
        final Path escape = new Path(Arrays.asList("..", "secrets", "stolen"));

        assertThrows(SecurityException.class, () -> fileSystem.rename(inside, escape));
        assertThrows(SecurityException.class, () -> fileSystem.rename(escape, inside));
    }

    @Test
    public void traversalWithinTheExportedDirectoryIsAllowed() throws IOException {
        Files.createDirectory(exported.resolve("sub"));

        final Path path = new Path(Arrays.asList("sub", "..", "visible"));
        assertTrue(fileSystem.exists(path));
    }

    @Test
    public void siblingDirectoryWithSharedPrefixIsRefused() throws IOException {
        Files.createDirectory(tempDir.resolve("exported-elsewhere"));
        Files.write(tempDir.resolve("exported-elsewhere").resolve("password"), "hunter2".getBytes());

        final Path escape = new Path(Arrays.asList("..", "exported-elsewhere", "password"));
        assertThrows(SecurityException.class, () -> fileSystem.exists(escape));
    }
}
