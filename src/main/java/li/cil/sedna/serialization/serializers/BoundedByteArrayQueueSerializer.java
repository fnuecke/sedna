package li.cil.sedna.serialization.serializers;

import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.utils.BoundedByteArrayQueue;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

public final class BoundedByteArrayQueueSerializer implements Serializer<BoundedByteArrayQueue> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<BoundedByteArrayQueue> type, final Object value) throws SerializationException {
        final BoundedByteArrayQueue queue = (BoundedByteArrayQueue) value;
        final byte[][] values = queue.toArray();

        int length = 0; // Same as queue.byteSize() + values.length * Integer.BYTES, but explicit.
        for (final byte[] entry : values) {
            length += Integer.BYTES + entry.length;
        }

        final ByteBuffer buffer = ByteBuffer.allocate(length);
        for (final byte[] entry : values) {
            buffer.putInt(entry.length);
            buffer.put(entry);
        }

        visitor.putInt("capacity", queue.capacity());
        visitor.putObject("values", byte[].class, buffer.array());
    }

    @Override
    public BoundedByteArrayQueue deserialize(final DeserializationVisitor visitor, final Class<BoundedByteArrayQueue> type, @Nullable final Object value) throws SerializationException {
        BoundedByteArrayQueue queue = (BoundedByteArrayQueue) value;
        final int capacity = visitor.getInt("capacity");
        if (!visitor.exists("values")) {
            return queue;
        }

        final byte[] values = (byte[]) visitor.getObject("values", byte[].class, null);
        if (values == null) {
            return null;
        }

        if (queue == null) {
            queue = new BoundedByteArrayQueue(capacity);
        } else if (queue.capacity() < capacity) {
            throw new SerializationException("Cannot deserialize a queue with capacity [" + capacity +
                "] into a queue with smaller capacity [" + queue.capacity() + "].");
        }

        queue.clear();
        final ByteBuffer buffer = ByteBuffer.wrap(values);
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < Integer.BYTES) {
                throw new SerializationException("Truncated queue data.");
            }

            final int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining() || queue.byteSize() + length > capacity) {
                throw new SerializationException("Invalid queue entry of length [" + length + "].");
            }

            final byte[] entry = new byte[length];
            buffer.get(entry);
            queue.offer(entry);
        }

        return queue;
    }
}
