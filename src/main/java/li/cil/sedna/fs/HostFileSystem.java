package li.cil.sedna.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Collectors;

public final class HostFileSystem implements FileSystem {
    private final File root;

    public HostFileSystem() {
        this(new File("."));
    }

    public HostFileSystem(final File root) {
        this.root = root.getAbsoluteFile();
    }

    @Override
    public FileSystemStats statfs() {
        final FileSystemStats result = new FileSystemStats();
        result.blockCount = root.getTotalSpace() / result.blockSize + 1;
        result.freeBlockCount = root.getFreeSpace() / result.blockSize;
        result.availableBlockCount = root.getUsableSpace() / result.blockSize;
        return result;
    }

    @Override
    public long getUniqueId(final Path path) {
        return toHost(path).hashCode();
    }

    @Override
    public boolean exists(final Path path) {
        return Files.exists(toHost(path));
    }

    @Override
    public boolean isDirectory(final Path path) {
        return Files.isDirectory(toHost(path));
    }

    @Override
    public boolean isWritable(final Path path) {
        return Files.isWritable(toHost(path));
    }

    @Override
    public boolean isReadable(final Path path) {
        return Files.isReadable(toHost(path));
    }

    @Override
    public boolean isExecutable(final Path path) {
        return Files.isExecutable(toHost(path));
    }

    @Override
    public BasicFileAttributes getAttributes(final Path path) throws IOException {
        return Files.readAttributes(toHost(path), BasicFileAttributes.class);
    }

    @Override
    public void mkdir(final Path path) throws IOException {
        Files.createDirectory(toHost(path));
    }

    @Override
    public FileHandle open(final Path path, final int flags) throws IOException {
        final String mode;
        if ((flags & FileMode.WRITE) != 0) {
            mode = "rw";
        } else if ((flags & FileMode.READ) != 0) {
            mode = "r";
        } else {
            throw new IOException();
        }

        final java.nio.file.Path hostPath = toHost(path);
        if (Files.isDirectory(hostPath)) {
            final List<DirectoryEntry> entries = Files.list(hostPath)
                    .map(java.nio.file.Path::toFile)
                    .map(DirectoryEntry::create)
                    .collect(Collectors.toList());
            return new FileHandle() {
                @Override
                public int read(final long offset, final ByteBuffer buffer) throws IOException {
                    throw new IOException();
                }

                @Override
                public int write(final long offset, final ByteBuffer buffer) throws IOException {
                    throw new IOException();
                }

                @Override
                public List<DirectoryEntry> readdir() {
                    return entries;
                }

                @Override
                public void close() {
                }
            };
        } else {
            final RandomAccessFile openedFile = new RandomAccessFile(hostPath.toFile(), mode);
            if ((flags & FileMode.TRUNCATE) != 0) {
                openedFile.setLength(0);
            }

            return new FileHandle() {
                @Override
                public int read(final long offset, final ByteBuffer buffer) throws IOException {
                    openedFile.seek(offset);
                    return openedFile.getChannel().read(buffer, offset);
                }

                @Override
                public int write(final long offset, final ByteBuffer buffer) throws IOException {
                    openedFile.seek(offset);
                    return openedFile.getChannel().write(buffer, offset);
                }

                @Override
                public List<DirectoryEntry> readdir() throws IOException {
                    throw new IOException();
                }

                @Override
                public void close() throws IOException {
                    openedFile.close();
                }
            };
        }
    }

    @Override
    public FileHandle create(final Path path, final int flags) throws IOException {
        Files.createFile(toHost(path));
        return open(path, flags);
    }

    @Override
    public void unlink(final Path path) throws IOException {
        Files.delete(toHost(path));
    }

    @Override
    public void rename(final Path oldpath, final Path newpath) throws IOException {
        Files.move(toHost(oldpath), toHost(newpath));
    }

    private java.nio.file.Path toHost(final Path path) {
        java.nio.file.Path result = root.toPath();
        for (final String part : path.getParts()) {
            result = result.resolve(part);
        }
        return result;
    }
}
