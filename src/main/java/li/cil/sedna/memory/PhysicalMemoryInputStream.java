package li.cil.sedna.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class PhysicalMemoryInputStream extends InputStream {
    private final PhysicalMemory memory;
    private int offset;

    public PhysicalMemoryInputStream(final PhysicalMemory memory) {
        this.memory = memory;
    }

    @Override
    public int read() throws IOException {
        if (offset >= memory.getLength()) return -1;
        return (byte) memory.load(offset++, Sizes.SIZE_8_LOG2) & 0xFF;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        final int count = Math.min(len, memory.getLength() - offset);
        if (count <= 0) {
            return -1;
        }

        memory.load(offset, ByteBuffer.wrap(b, off, count));
        offset += count;
        return count;
    }
}
