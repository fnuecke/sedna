package li.cil.sedna.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;

import java.io.IOException;
import java.io.InputStream;

public final class PhysicalMemoryInputStream extends InputStream {
    private final PhysicalMemory memory;
    private int offset;

    public PhysicalMemoryInputStream(final PhysicalMemory memory) {
        this.memory = memory;
    }

    @Override
    public int read() throws IOException {
        if (offset >= memory.getLength()) return -1;
        return memory.load(offset++, Sizes.SIZE_8_LOG2) & 0xFF;
    }

    // TODO read(byte[], ...)
}
