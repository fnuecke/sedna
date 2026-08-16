package li.cil.sedna.utils;

import li.cil.sedna.serialization.serializers.FixedSizeByteBufferSerializer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility wrapper for serialization - buffers that may disagree with configuration about
 * the length have to be checked during load, or we can run into index out of bounds errors
 * at runtime, later. If a drift is detected, de-serialization will fail.
 *
 * @see FixedSizeByteBufferSerializer
 */
public final class FixedSizeByteBuffer {
    private final ByteBuffer buffer;

    public FixedSizeByteBuffer(final int capacity, final ByteOrder order) {
        this.buffer = ByteBuffer.allocate(capacity);
        this.buffer.order(order);
    }

    public ByteBuffer buffer() {
        return buffer;
    }

    public int capacity() {
        return buffer.capacity();
    }
}
