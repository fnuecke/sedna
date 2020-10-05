package li.cil.sedna.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class PhysicalMemoryOutputStream extends OutputStream {
    private final PhysicalMemory memory;
    private int offset;

    public PhysicalMemoryOutputStream(final PhysicalMemory memory) {
        this.memory = memory;
    }

    @Override
    public void write(final int b) throws IOException {
        memory.store(offset++, b, Sizes.SIZE_8_LOG2);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                   ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }


        final int count = Math.min(len, memory.getLength() - offset);
        if (count <= 0) {
            return;
        }

        memory.store(offset, ByteBuffer.wrap(b, off, count));
        offset += count;
    }
}
