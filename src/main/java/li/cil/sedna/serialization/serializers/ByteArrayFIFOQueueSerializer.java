package li.cil.sedna.serialization.serializers;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import li.cil.ceres.api.*;

import javax.annotation.Nullable;

@RegisterSerializer
public final class ByteArrayFIFOQueueSerializer implements Serializer<ByteArrayFIFOQueue> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<ByteArrayFIFOQueue> type, final Object value) throws SerializationException {
        final ByteArrayFIFOQueue queue = (ByteArrayFIFOQueue) value;
        final byte[] values = new byte[queue.size()];
        // Can't iterate ByteArrayFIFOQueue, so we have to read all and then put it back :/
        for (int i = 0; i < values.length; i++) {
            values[i] = queue.dequeueByte();
        }
        for (int i = 0; i < values.length; i++) {
            queue.enqueue(values[i]);
        }
        visitor.putObject("values", byte[].class, values);
    }

    @Override
    public ByteArrayFIFOQueue deserialize(final DeserializationVisitor visitor, final Class<ByteArrayFIFOQueue> type, @Nullable final Object value) throws SerializationException {
        ByteArrayFIFOQueue queue = (ByteArrayFIFOQueue) value;
        if (!visitor.exists("values")) {
            return queue;
        }

        final byte[] values = (byte[]) visitor.getObject("values", byte[].class, null);
        if (values == null) {
            return null;
        }

        if (queue == null) {
            queue = new ByteArrayFIFOQueue();
        }

        queue.clear();
        for (int i = 0; i < values.length; i++) {
            queue.enqueue(values[i]);
        }

        return queue;
    }
}
