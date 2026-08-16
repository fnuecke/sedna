package li.cil.sedna.serialization.serializers;

import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.utils.FixedSizeByteBuffer;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Restores a {@link FixedSizeByteBuffer}'s contents into the buffer the device already
 * built, and rejects a save that describes a different size.
 *
 * @see FixedSizeByteBuffer
 */
public final class FixedSizeByteBufferSerializer implements Serializer<FixedSizeByteBuffer> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<FixedSizeByteBuffer> type, final Object value) throws SerializationException {
        final FixedSizeByteBuffer wrapper = (FixedSizeByteBuffer) value;

        final ByteBuffer view = wrapper.buffer().duplicate();
        view.clear();

        final byte[] values = new byte[view.capacity()];
        view.get(values);

        visitor.putInt("capacity", values.length);
        visitor.putObject("value", byte[].class, values);
    }

    @Nullable
    @Override
    public FixedSizeByteBuffer deserialize(final DeserializationVisitor visitor, final Class<FixedSizeByteBuffer> type, @Nullable final Object value) throws SerializationException {
        final FixedSizeByteBuffer wrapper = (FixedSizeByteBuffer) value;
        if (!visitor.exists("capacity") || !visitor.exists("value")) {
            return wrapper;
        }

        final int capacity = visitor.getInt("capacity");
        if (wrapper == null) {
            throw new SerializationException("Cannot restore a fixed size buffer without the buffer to restore into.");
        }
        if (capacity != wrapper.capacity()) {
            throw new SerializationException("Buffer size in save state (" + capacity
                    + ") does not match the size this device uses (" + wrapper.capacity()
                    + "); the saved state describes a differently shaped device.");
        }

        final byte[] values = (byte[]) visitor.getObject("value", byte[].class, null);
        if (values == null) {
            return wrapper;
        }

        // Absolute write, cursor left cleared: nothing here restores a position or limit,
        // which is the entire point of the type.
        final ByteBuffer view = wrapper.buffer().duplicate();
        view.clear();
        view.put(values, 0, Math.min(values.length, view.capacity()));

        return wrapper;
    }
}
