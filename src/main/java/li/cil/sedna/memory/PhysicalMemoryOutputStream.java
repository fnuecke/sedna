package li.cil.sedna.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;

import java.io.IOException;
import java.io.OutputStream;

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

    // TODO write(byte[], ...)
}
