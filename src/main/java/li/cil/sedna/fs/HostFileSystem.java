package li.cil.sedna.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class HostFileSystem implements FileSystem {
    private final File root;

    public HostFileSystem(final File root) {
        this.root = root;
    }

    @Override
    public FileSystemStats statfs() {
        final FileSystemStats result = new FileSystemStats();
        result.blockCount = root.getTotalSpace() / result.blockSize;
        result.freeBlockCount = root.getFreeSpace() / result.blockSize;
        result.availableBlockCount = root.getUsableSpace() / result.blockSize;
        return result;
    }

    @Override
    public Path getRoot() {
        return root.toPath().relativize(root.toPath());
    }

    @Override
    public long getUniqueId(final Path path) {
        return toFile(path).hashCode();
    }

    @Override
    public boolean exists(final Path path) {
        return toFile(path).exists();
    }

    @Override
    public boolean isDirectory(final Path path) {
        return toFile(path).isDirectory();
    }

    @Override
    public BasicFileAttributes getAttributes(final Path path) throws IOException {
        return Files.readAttributes(toFile(path).toPath(), BasicFileAttributes.class);
    }

    @Override
    public void mkdir(final Path path) throws IOException {
        if (!toFile(path).mkdir()) {
            throw new IOException();
        }
    }

    @Override
    public FileHandle open(final Path path, final int flags) throws IOException {
        final File file = toFile(path);
        String mode = "";
        if ((flags & FileMode.READ) != 0) {
            mode += "r";
        }
        if ((flags & FileMode.WRITE) != 0) {
            mode += "w";
        }

        if (file.isDirectory()) {
            final List<DirectoryEntry> entries = Arrays.stream(file.listFiles()).map(DirectoryEntry::create).collect(Collectors.toList());
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
            final RandomAccessFile openedFile = new RandomAccessFile(file, mode);
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
        final File file = toFile(path);
        if (!file.createNewFile()) {
            throw new IOException();
        }
        return open(path, flags);
    }

    @Override
    public void unlink(final Path path) throws IOException {
        if (!toFile(path).delete()) {
            throw new IOException();
        }
    }

    @Override
    public void rename(final Path oldpath, final Path newpath) throws IOException {
        if (!toFile(oldpath).renameTo(toFile(newpath))) {
            throw new IOException();
        }
    }

    private File toFile(final Path path) {
        return root.toPath().resolve(root.toPath().getRoot().relativize(path)).toFile();
    }
}
