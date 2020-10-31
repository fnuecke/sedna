package li.cil.sedna.device.flash;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;

import java.nio.ByteBuffer;

public final class FlashMemoryDevice implements MemoryMappedDevice {
    private final ByteBuffer data;
    private final boolean readonly;

    public FlashMemoryDevice(final ByteBuffer data, final boolean readonly) {
        this.data = data;
        this.readonly = readonly;
    }

    public FlashMemoryDevice(final ByteBuffer data) {
        this(data, true);
    }

    public FlashMemoryDevice(final int size) {
        this(ByteBuffer.allocate(size));
    }

    public ByteBuffer getData() {
        return data;
    }

    @Override
    public boolean supportsFetch() {
        return true;
    }

    @Override
    public int getLength() {
        return data.capacity();
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        if (offset < 0 || offset > data.limit() - (1 << sizeLog2)) {
            return 0;
        }
        switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2:
                return data.get(offset);
            case Sizes.SIZE_16_LOG2:
                return data.getShort(offset);
            case Sizes.SIZE_32_LOG2:
                return data.getInt(offset);
            case Sizes.SIZE_64_LOG2:
                return data.getLong(offset);
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        if (readonly) {
            return;
        }
        if (offset < 0 || offset > data.limit() - (1 << sizeLog2)) {
            return;
        }
        switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2:
                data.put(offset, (byte) value);
                break;
            case Sizes.SIZE_16_LOG2:
                data.putShort(offset, (short) value);
                break;
            case Sizes.SIZE_32_LOG2:
                data.putInt(offset, (int) value);
                break;
            case Sizes.SIZE_64_LOG2:
                data.putLong(offset, value);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }
}
