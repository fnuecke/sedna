package li.cil.sedna.device.block;

import li.cil.sedna.api.device.BlockDevice;

import java.nio.ByteBuffer;

public final class NullBlockDevice implements BlockDevice {
    public static final NullBlockDevice INSTANCE = new NullBlockDevice();

    private static final ByteBuffer EMPTY_BYTE_BUFFER = ByteBuffer.allocate(0);

    @Override
    public boolean isReadonly() {
        return true;
    }

    @Override
    public long getCapacity() {
        return 0;
    }

    @Override
    public ByteBuffer getView(final long offset, final int length) {
        if (offset < 0 || offset > Integer.MAX_VALUE || length > 0) {
            throw new IllegalArgumentException();
        }
        return EMPTY_BYTE_BUFFER;
    }
}
