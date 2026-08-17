package li.cil.sedna.api.device.serial;

import java.nio.ByteBuffer;

public interface SerialDevice {
    int read();

    default int read(final ByteBuffer dst) {
        int count = 0;
        while (dst.hasRemaining()) {
            final int value = read();
            if (value < 0) {
                break;
            }
            dst.put((byte) value);
            count++;
        }
        return count;
    }

    default int read(final byte[] dst, final int offset, final int length) {
        return read(ByteBuffer.wrap(dst, offset, length));
    }

    boolean canPutByte();

    void putByte(byte value);

    default void flush() {
    }
}
