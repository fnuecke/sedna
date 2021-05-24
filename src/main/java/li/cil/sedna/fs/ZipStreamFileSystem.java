package li.cil.sedna.fs;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipStreamFileSystem implements FileSystem {
    private final ZipNode root = new ZipNode();
    private final long totalSize;
    private final int totalFileCount;

    public ZipStreamFileSystem(final InputStream stream) throws IOException {
        final ZipInputStream zipStream = new ZipInputStream(stream);
        final HashMap<ZipNode, ZipNode> parents = new HashMap<>();
        final ArrayList<ZipNode> nodes = new ArrayList<>();

        nodes.add(root);

        long size = 0;
        int fileCount = 0;
        ZipEntry entry;
        while ((entry = zipStream.getNextEntry()) != null) {
            final String[] parts = entry.getName().split("/");
            ZipNode current = root;
            for (int i = 0; i < parts.length && current != null; i++) {
                if (".".equals(parts[i])) {
                    continue;
                }
                if ("..".equals(parts[i])) {
                    current = parents.get(current);
                } else {
                    final ZipNode child = current.children.computeIfAbsent(parts[i], name -> {
                        final ZipNode node = new ZipNode();
                        nodes.add(node);
                        return node;
                    });
                    current.children.put(parts[i], child);
                    parents.put(child, current);
                    current = child;
                }
            }

            if (current != null) {
                current.data = readEntryData(zipStream);
                current.attributes = new ZipNodeFileAttributes(entry, current.data.length);
                size += current.attributes.size();
                if (!current.isDirectory()) {
                    fileCount++;
                }
            }
        }

        totalFileCount = fileCount;
        totalSize = size;

        nodes.forEach(ZipNode::buildEntries);
    }

    private byte[] readEntryData(final ZipInputStream zipStream) throws IOException {
        final ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[512];
        while (zipStream.available() > 0) {
            final int count = zipStream.read(buffer);
            if (count > 0) {
                dataStream.write(buffer, 0, count);
            }
        }
        return dataStream.toByteArray();
    }

    @Override
    public FileSystemStats statfs() {
        final FileSystemStats result = new FileSystemStats();
        result.blockCount = totalSize / result.blockSize + 1;
        result.fileCount = totalFileCount;
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
        return node == null || node.isDirectory();
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
        if (node.attributes != null) {
            return node.attributes;
        } else {
            return new BasicFileAttributes() {
                @Override
                @Nullable
                public FileTime lastModifiedTime() {
                    return null;
                }

                @Override
                @Nullable
                public FileTime lastAccessTime() {
                    return null;
                }

                @Override
                @Nullable
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
        if (node.isDirectory()) {
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
                    return node.entries;
                }

                @Override
                public void close() {
                }
            };
        } else {
            final byte[] data = node.data;
            return new FileHandle() {
                @Override
                public int read(final long offset, final ByteBuffer buffer) throws IOException {
                    if (offset < 0 || offset > data.length) {
                        throw new IOException();
                    }
                    final int count = Math.min(buffer.remaining(), (int) Math.min(Integer.MAX_VALUE, data.length - offset));
                    buffer.put(data, (int) offset, count);
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
                public void close() {
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
    public void rename(final Path oldpath, final Path newpath) throws IOException {
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

    private static final class ZipNode {
        public ZipNodeFileAttributes attributes;
        public byte[] data;
        public LinkedHashMap<String, ZipNode> children = new LinkedHashMap<>();
        public ArrayList<DirectoryEntry> entries = new ArrayList<>();

        private void buildEntries() {
            children.forEach((name, child) -> {
                final DirectoryEntry directoryEntry = new DirectoryEntry();
                directoryEntry.name = name;
                final boolean childIsDirectory = child.attributes == null || child.attributes.isDirectory();
                directoryEntry.type = childIsDirectory ? FileType.DIRECTORY : FileType.FILE;
                entries.add(directoryEntry);
            });
        }

        public boolean isDirectory() {
            return attributes == null || attributes.isDirectory();
        }
    }

    private static final class ZipNodeFileAttributes implements BasicFileAttributes {
        private final FileTime lastModifiedTime;
        private final FileTime lastAccessTime;
        private final FileTime creationTime;
        private final boolean isDirectory;
        private final long size;

        public ZipNodeFileAttributes(final ZipEntry entry, final long size) {
            this.lastModifiedTime = entry.getLastModifiedTime();
            this.lastAccessTime = entry.getLastAccessTime();
            this.creationTime = entry.getCreationTime();
            this.isDirectory = entry.isDirectory();
            this.size = size;
        }

        @Override
        public FileTime lastModifiedTime() {
            return lastModifiedTime;
        }

        @Override
        public FileTime lastAccessTime() {
            return lastAccessTime;
        }

        @Override
        public FileTime creationTime() {
            return creationTime;
        }

        @Override
        public boolean isRegularFile() {
            return !isDirectory;
        }

        @Override
        public boolean isDirectory() {
            return isDirectory;
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
            return size;
        }

        @Override
        public Object fileKey() {
            return this;
        }
    }
}
