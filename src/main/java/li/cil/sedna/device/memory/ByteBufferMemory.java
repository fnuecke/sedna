package li.cil.sedna.device.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.memory.exception.LoadFaultException;
import li.cil.sedna.memory.exception.StoreFaultException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Simple physical memory implementation backed by a {@link ByteBuffer}.
 */
public class ByteBufferMemory implements PhysicalMemory {
    private final ByteBuffer data;

    public ByteBufferMemory(final int size) {
        if ((size & 0b11) != 0)
            throw new IllegalArgumentException("size must be a multiple of four");
        this.data = ByteBuffer.allocateDirect(size);
        data.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public int getLength() {
        return data.capacity();
    }

    @Override
    public int load(final int offset, final int sizeLog2) throws MemoryAccessException {
        if (offset < 0 || offset > data.limit() - (1 << sizeLog2)) {
            throw new LoadFaultException(offset);
        }
        switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2:
                return data.get(offset);
            case Sizes.SIZE_16_LOG2:
                return data.getShort(offset);
            case Sizes.SIZE_32_LOG2:
                return data.getInt(offset);
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public void store(final int offset, final int value, final int sizeLog2) throws MemoryAccessException {
        if (offset < 0 || offset > data.limit() - (1 << sizeLog2)) {
            throw new StoreFaultException(offset);
        }
        switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2:
                data.put(offset, (byte) value);
                break;
            case Sizes.SIZE_16_LOG2:
                data.putShort(offset, (short) value);
                break;
            case Sizes.SIZE_32_LOG2:
                data.putInt(offset, value);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public void load(final int offset, final ByteBuffer dst) {
        final ByteBuffer slice = data.slice();
        slice.position(offset);
        slice.limit(offset + dst.remaining());
        dst.put(slice);
    }

    @Override
    public void store(final int offset, final ByteBuffer src) {
        final ByteBuffer slice = data.slice();
        slice.position(offset);
        slice.limit(offset + src.remaining());
        slice.put(src);
    }
}
