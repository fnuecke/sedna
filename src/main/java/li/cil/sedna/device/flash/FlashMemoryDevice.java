package li.cil.sedna.device.flash;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
        this(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN));
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
        return switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2 -> data.get(offset);
            case Sizes.SIZE_16_LOG2 -> data.getShort(offset);
            case Sizes.SIZE_32_LOG2 -> data.getInt(offset);
            case Sizes.SIZE_64_LOG2 -> data.getLong(offset);
            default -> throw new IllegalArgumentException();
        };
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
            case Sizes.SIZE_8_LOG2 -> data.put(offset, (byte) value);
            case Sizes.SIZE_16_LOG2 -> data.putShort(offset, (short) value);
            case Sizes.SIZE_32_LOG2 -> data.putInt(offset, (int) value);
            case Sizes.SIZE_64_LOG2 -> data.putLong(offset, value);
            default -> throw new IllegalArgumentException();
        }
    }
}
