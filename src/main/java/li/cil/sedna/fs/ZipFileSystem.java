package li.cil.sedna.fs;

import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ZipFileSystem implements FileSystem {
    private final ZipFile zipFile;
    private final ZipNode root = new ZipNode();

    private static final class ZipNode {
        public ZipEntry entry;
        public LinkedHashMap<String, ZipNode> children = new LinkedHashMap<>();
    }

    public ZipFileSystem(final ZipFile zipFile) {
        this.zipFile = zipFile;
        constructTree();
    }

    private void constructTree() {
        final HashMap<ZipNode, ZipNode> parents = new HashMap<>();

        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();

            final String[] parts = entry.getName().split("/");
            ZipNode current = root;
            for (int i = 0; i < parts.length && current != null; i++) {
                if (".".equals(parts[i])) {
                    continue;
                }
                if ("..".equals(parts[i])) {
                    current = parents.get(current);
                } else {
                    final ZipNode child = current.children.computeIfAbsent(parts[i], name -> new ZipNode());
                    current.children.put(parts[i], child);
                    parents.put(child, current);
                    current = child;
                }
            }

            if (current != null) {
                current.entry = entry;
            }
        }
    }

    @Override
    public FileSystemStats statfs() {
        final FileSystemStats result = new FileSystemStats();
        final int size = zipFile.size();
        result.blockCount = size / result.blockSize + 1;
        // result.fileCount = zipFile.stream().count(); -- Too slow, don't.
        return result;
    }

    @Override
    public long getUniqueId(final Path path) throws IOException {
        return getNodeOrThrow(path).hashCode();
    }

    @Override
    public boolean exists(final Path path) {
        return getNode(path) != null;
    }

    @Override
    public boolean isDirectory(final Path path) {
        final ZipNode node = getNode(path);
        return node == null || node.entry == null || node.entry.isDirectory();
    }

    @Override
    public boolean isWritable(final Path path) {
        return false;
    }

    @Override
    public boolean isReadable(final Path path) {
        return true;
    }

    @Override
    public boolean isExecutable(final Path path) {
        return true;
    }

    @Override
    public BasicFileAttributes getAttributes(final Path path) throws IOException {
        final ZipNode node = getNodeOrThrow(path);
        if (node.entry != null) {
            final ZipEntry entry = node.entry;
            return new BasicFileAttributes() {
                @Override
                public FileTime lastModifiedTime() {
                    return entry.getLastModifiedTime();
                }

                @Override
                public FileTime lastAccessTime() {
                    return entry.getLastAccessTime();
                }

                @Override
                public FileTime creationTime() {
                    return entry.getCreationTime();
                }

                @Override
                public boolean isRegularFile() {
                    return !entry.isDirectory();
                }

                @Override
                public boolean isDirectory() {
                    return entry.isDirectory();
                }

                @Override
                public boolean isSymbolicLink() {
                    return false;
                }

                @Override
                public boolean isOther() {
                    return false;
                }

                @Override
                public long size() {
                    return entry.getSize();
                }

                @Override
                public Object fileKey() {
                    return node;
                }
            };
        } else {
            return new BasicFileAttributes() {
                @Override
                public FileTime lastModifiedTime() {
                    return null;
                }

                @Override
                public FileTime lastAccessTime() {
                    return null;
                }

                @Override
                public FileTime creationTime() {
                    return null;
                }

                @Override
                public boolean isRegularFile() {
                    return false;
                }

                @Override
                public boolean isDirectory() {
                    return true;
                }

                @Override
                public boolean isSymbolicLink() {
                    return false;
                }

                @Override
                public boolean isOther() {
                    return false;
                }

                @Override
                public long size() {
                    return 0;
                }

                @Override
                public Object fileKey() {
                    return node;
                }
            };
        }
    }

    @Override
    public void mkdir(final Path path) throws IOException {
        throw new IOException();
    }

    @Override
    public FileHandle open(final Path path, final int flags) throws IOException {
        if ((flags & FileMode.WRITE) != 0) {
            throw new IOException();
        }

        final ZipNode node = getNodeOrThrow(path);
        if (node.entry == null || node.entry.isDirectory()) {
            final ArrayList<DirectoryEntry> entries = new ArrayList<>();
            node.children.forEach((name, child) -> {
                final DirectoryEntry directoryEntry = new DirectoryEntry();
                directoryEntry.name = name;
                final boolean childIsDirectory = child.entry == null || child.entry.isDirectory();
                directoryEntry.type = childIsDirectory ? FileType.DIRECTORY : FileType.FILE;
                entries.add(directoryEntry);
            });
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
            // Zip file InputStreams don't support seeking, so we have to copy to memory :/
            final InputStream stream = zipFile.getInputStream(node.entry);
            final ByteBuffer data = ByteBuffer.wrap(IOUtils.toByteArray(stream));
            stream.close();
            return new FileHandle() {
                @Override
                public int read(final long offset, final ByteBuffer buffer) throws IOException {
                    if (offset < 0 || offset > data.capacity()) {
                        throw new IOException();
                    }
                    data.position((int) offset);
                    final int count = Math.min(buffer.remaining(), data.capacity() - data.position());
                    data.limit(data.position() + count);
                    buffer.put(data);
                    return count;
                }

                @Override
                public int write(final long offset, final ByteBuffer buffer) throws IOException {
                    throw new IOException();
                }

                @Override
                public List<DirectoryEntry> readdir() throws IOException {
                    throw new IOException();
                }

                @Override
                public void close() throws IOException {
                    stream.close();
                }
            };
        }
    }

    @Override
    public FileHandle create(final Path path, final int flags) throws IOException {
        throw new IOException();
    }

    @Override
    public void unlink(final Path path) throws IOException {
        throw new IOException();
    }

    @Override
    public void rename(final Path oldPath, final Path newPath) throws IOException {
        throw new IOException();
    }

    @Nullable
    private ZipNode getNode(final Path path) {
        ZipNode node = root;
        for (final String part : path.getParts()) {
            final ZipNode child = node.children.get(part);
            if (child == null) {
                return null;
            }
            node = child;
        }

        return node;
    }

    private ZipNode getNodeOrThrow(final Path path) throws IOException {
        ZipNode node = root;
        for (final String part : path.getParts()) {
            final ZipNode child = node.children.get(part);
            if (child == null) {
                throw new FileNotFoundException();
            }
            node = child;
        }

        return node;
    }
}
