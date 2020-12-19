package li.cil.sedna.device.block;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.BlockDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class SparseBlockDevice implements BlockDevice {
    private static final int DEFAULT_BLOCK_SIZE = 64 * 1024;

    private final BlockDevice lower;
    private final int blockSize;
    private final boolean readonly;

    @Serialized private final SparseBlockMap blocks;

    public SparseBlockDevice(final BlockDevice lower) {
        this(lower, false, DEFAULT_BLOCK_SIZE);
    }

    public SparseBlockDevice(final BlockDevice lower, final boolean readonly) {
        this(lower, readonly, DEFAULT_BLOCK_SIZE);
    }

    public SparseBlockDevice(final BlockDevice lower, final boolean readonly, final int blockSize) {
        if (lower.getCapacity() / blockSize > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Lower BlockDevice is too large.");

        this.lower = lower;
        this.blockSize = blockSize;
        this.readonly = readonly;

        final int blockCount = Math.max(1, (int) (lower.getCapacity() / blockSize));
        blocks = new SparseBlockMap(blockCount);
    }

    public int getBlockCount() {
        return blocks.size();
    }

    @Override
    public boolean isReadonly() {
        return readonly;
    }

    @Override
    public long getCapacity() {
        return lower.getCapacity();
    }

    @Override
    public InputStream getInputStream(final long offset) {
        return new SparseInputStream(offset);
    }

    @Override
    public OutputStream getOutputStream(final long offset) {
        if (isReadonly()) {
            throw new UnsupportedOperationException();
        }

        return new SparseOutputStream(offset);
    }

    private int offsetToBlockIndex(final long offset) {
        return (int) (offset / blockSize);
    }

    private int blockIndexToOffset(final int index) {
        return index * blockSize;
    }

    public static final class SparseBlockMap extends Int2ObjectArrayMap<byte[]> {
        public SparseBlockMap(final int capacity) {
            super(capacity);
        }
    }

    private final class SparseInputStream extends InputStream {
        private final InputStream lowerStream;
        private long offset;

        public SparseInputStream(final long offset) {
            this.offset = offset;
            lowerStream = lower.getInputStream(offset);
        }

        @Override
        public int read() throws IOException {
            if (offset >= getCapacity()) {
                return -1;
            }

            final int readValue;

            final int blockIndex = offsetToBlockIndex(offset);
            final byte[] block = blocks.get(blockIndex);
            if (block != null) {
                final int startOffset = blockIndexToOffset(blockIndex);
                final int localOffset = (int) (offset - startOffset);
                if (lowerStream.skip(1) != 1) throw new IOException();
                readValue = block[localOffset];
            } else {
                readValue = lowerStream.read();
            }

            offset++;
            return readValue;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            if (offset >= getCapacity()) {
                return -1;
            }

            final int readBytes;

            final int blockIndex = offsetToBlockIndex(offset);
            final byte[] block = blocks.get(blockIndex);
            if (block != null) {
                final int startOffset = blockIndexToOffset(blockIndex);
                final int localOffset = (int) (offset - startOffset);
                final int blockCount = blockSize - localOffset;
                readBytes = (int) lowerStream.skip(Math.min(blockCount, len));
                System.arraycopy(block, localOffset, b, off, readBytes);
            } else {
                readBytes = lowerStream.read(b, off, len);
            }

            offset += readBytes;
            return readBytes;
        }

        @Override
        public long skip(final long n) throws IOException {
            return offset += n;
        }

        @Override
        public int available() throws IOException {
            return (int) (getCapacity() - offset);
        }
    }

    private final class SparseOutputStream extends OutputStream {
        private long offset;

        public SparseOutputStream(final long offset) {
            this.offset = offset;
        }

        @Override
        public void write(final int b) throws IOException {
            if (offset >= getCapacity()) {
                throw new IOException();
            }

            final int blockIndex = offsetToBlockIndex(offset);
            final byte[] block = getShadowBlock(blockIndex);

            final int startOffset = blockIndexToOffset(blockIndex);
            final int localOffset = (int) (offset - startOffset);

            block[localOffset] = (byte) b;
            offset++;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            if (offset >= getCapacity()) {
                throw new IOException();
            }

            final int blockIndex = offsetToBlockIndex(offset);
            final byte[] block = getShadowBlock(blockIndex);

            final int startOffset = blockIndexToOffset(blockIndex);
            final int localOffset = (int) (offset - startOffset);
            final int writtenBytes = Math.min(len, blockSize - localOffset);

            System.arraycopy(b, off, block, localOffset, writtenBytes);
            offset += writtenBytes;

            if (writtenBytes < len) {
                write(b, off + writtenBytes, len - writtenBytes);
            }
        }

        private byte[] getShadowBlock(final int blockIndex) throws IOException {
            byte[] block = blocks.get(blockIndex);
            if (block != null) {
                return block;
            }

            block = new byte[blockSize];

            final int startOffset = blockIndexToOffset(blockIndex);
            final InputStream stream = lower.getInputStream(startOffset);
            int initOffset = 0, initCount = blockSize;
            while (initCount > 0) {
                final int read = stream.read(block, initOffset, initCount);

                // Can hit EOF if capacity of lower block device is not evenly divisible by shadow block size.
                if (read < 0) {
                    initCount = 0;
                } else {
                    initOffset += read;
                    initCount -= read;
                }
            }

            blocks.put(blockIndex, block);

            return block;
        }
    }
}
