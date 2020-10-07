package li.cil.sedna.fs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public interface FileSystem {
    FileSystemStats statfs() throws IOException;

    Path getRoot();

    long getUniqueId(final Path path) throws IOException;

    boolean exists(final Path path);

    boolean isDirectory(final Path path);

    BasicFileAttributes getAttributes(final Path path) throws IOException;

    void mkdir(final Path path) throws IOException;

    FileHandle open(final Path path, final int flags) throws IOException;

    FileHandle create(final Path path, final int flags) throws IOException;

    void unlink(final Path path) throws IOException;

    void rename(final Path oldpath, final Path newpath) throws IOException;
}
