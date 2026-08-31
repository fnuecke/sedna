package li.cil.sedna.serialization;

import li.cil.ceres.BinarySerialization;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.sedna.Sedna;
import li.cil.sedna.serialization.serializers.BoundedByteArrayQueueSerializer;
import li.cil.sedna.utils.BoundedByteArrayQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public final class BoundedByteArrayQueueSerializerTests {
    @BeforeAll
    public static void setup() {
        Sedna.initialize();
    }

    @Test
    public void roundTripIntoNewInstancePreservesCapacityAndContent() {
        final BoundedByteArrayQueue queue = new BoundedByteArrayQueue(1024);
        queue.offer(new byte[]{1, 2, 3});
        queue.offer(new byte[]{4, 5});

        final ByteBuffer serialized = BinarySerialization.serialize(queue);
        final BoundedByteArrayQueue restored = BinarySerialization.deserialize(serialized, BoundedByteArrayQueue.class);

        assertEquals(1024, restored.capacity(), "a restored queue must have the serialized capacity");
        assertArrayEquals(new byte[]{1, 2, 3}, restored.poll());
        assertArrayEquals(new byte[]{4, 5}, restored.poll());
        assertNull(restored.poll());
    }

    @Test
    public void roundTripIntoExistingInstanceReplacesContent() {
        final BoundedByteArrayQueue queue = new BoundedByteArrayQueue(1024);
        queue.offer(new byte[]{1, 2, 3});

        final ByteBuffer serialized = BinarySerialization.serialize(queue);
        final BoundedByteArrayQueue target = new BoundedByteArrayQueue(1024);
        target.offer(new byte[]{9});
        BinarySerialization.deserialize(serialized, target);

        assertArrayEquals(new byte[]{1, 2, 3}, target.poll());
        assertNull(target.poll());
    }

    @Test
    public void deserializingIntoSmallerCapacityQueueFails() {
        final BoundedByteArrayQueue queue = new BoundedByteArrayQueue(1024);
        queue.offer(new byte[]{1, 2, 3});

        final ByteBuffer serialized = BinarySerialization.serialize(queue);
        final BoundedByteArrayQueue target = new BoundedByteArrayQueue(16);

        assertThrows(SerializationException.class, () -> BinarySerialization.deserialize(serialized, target),
            "deserializing into a queue with smaller capacity must be rejected");
    }

    @Test
    public void entryLengthExceedingCapacityIsRejectedWithoutAllocating() {
        final ByteBuffer values = ByteBuffer.allocate(Integer.BYTES);
        values.putInt(Integer.MAX_VALUE - Integer.BYTES);

        assertThrows(SerializationException.class, () -> deserializeCorrupt(16, values.array()));
    }

    @Test
    public void negativeEntryLengthIsRejected() {
        final ByteBuffer values = ByteBuffer.allocate(Integer.BYTES);
        values.putInt(-1);

        assertThrows(SerializationException.class, () -> deserializeCorrupt(16, values.array()));
    }

    @Test
    public void truncatedEntryDataIsRejected() {
        final ByteBuffer values = ByteBuffer.allocate(Integer.BYTES + 2);
        values.putInt(8);

        assertThrows(SerializationException.class, () -> deserializeCorrupt(1024, values.array()));
    }

    // --------------------------------------------------------------------- //

    private static void deserializeCorrupt(final int capacity, final byte[] values) {
        new BoundedByteArrayQueueSerializer().deserialize(new CorruptDataVisitor(capacity, values),
            BoundedByteArrayQueue.class, null);
    }

    private record CorruptDataVisitor(int capacity, byte[] values) implements DeserializationVisitor {
        @Override
        public int getInt(final String name) {
            return capacity;
        }

        @Override
        public Object getObject(final String name, final Class<?> type, final Object into) {
            return values;
        }

        @Override
        public boolean getBoolean(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte getByte(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public char getChar(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public short getShort(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getDouble(final String name) {
            throw new UnsupportedOperationException();
        }
    }
}
