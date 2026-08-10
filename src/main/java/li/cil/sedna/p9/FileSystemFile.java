package li.cil.sedna.p9;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.fs.DirectoryEntry;
import li.cil.sedna.fs.FileHandle;
import li.cil.sedna.fs.FileMode;
import li.cil.sedna.fs.FileSystem;
import li.cil.sedna.fs.Path;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * A reference to some object within a {@link FileSystem}.
 * <p>
 * This class represents all data associated with a fid.
 * <p>
 * This can reference either a file or a directory; we call it "File" because that's how the specification
 * refers to it (e.g. from clunk(5): The clunk request informs the file server that the current file represented by
 * fid is no longer needed by the client).
 */
public final class FileSystemFile implements Closeable {
    @Serialized public int id;
    @Serialized public String[] pathParts;
    @Serialized public boolean isOpen;
    @Serialized public int openFlags;

    private Path path;
    private FileHandle handle;

    // For deserialization.
    public FileSystemFile() {
    }

    public FileSystemFile(final int id, final Path path) {
        this.id = id;
        this.path = path;
        this.pathParts = path.getParts();
    }

    @Override
    public void close() {
        if (handle != null) {
            try {
                handle.close();
            } catch (final IOException ignored) {
            }
        }
        handle = null;
        isOpen = false;
        openFlags = FileMode.NONE;
    }

    public FileHandle getHandle(final FileSystem fileSystem) throws IOException {
        if (isOpen && handle == null) {
            handle = fileSystem.open(getPath(), openFlags);
        }
        if (handle == null) {
            throw new IOException();
        }
        return handle;
    }

    public void setHandle(final FileHandle handle, final int flags) {
        close();
        isOpen = true;
        openFlags = flags & ~FileMode.TRUNCATE; // Don't truncate when re-opening after deserialization.
        this.handle = handle;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public Path getPath() {
        if (path == null) {
            path = new Path(Arrays.asList(pathParts));
        }
        return path;
    }

    public void setPath(final Path path) {
        close();
        this.path = path;
        this.pathParts = path.getParts();
    }

    public int read(final FileSystem fileSystem, final long offset, final ByteBuffer buffer) throws IOException {
        return getHandle(fileSystem).read(offset, buffer);
    }

    public int write(final FileSystem fileSystem, final long offset, final ByteBuffer buffer) throws IOException {
        return getHandle(fileSystem).write(offset, buffer);
    }

    public List<DirectoryEntry> readdir(final FileSystem fileSystem) throws IOException {
        return getHandle(fileSystem).readdir();
    }
}
