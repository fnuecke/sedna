package li.cil.sedna;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import li.cil.ceres.BinarySerialization;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public final class SerializationTests {
    @Test
    public void testAtomicIntegerSerializer() {
        final AtomicInteger value = new AtomicInteger(123);

        final ByteBuffer serialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.serialize(value));
        AtomicInteger deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, AtomicInteger.class));

        Assertions.assertEquals(value.get(), deserialized.get());

        value.set(321);
        deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, value));

        Assertions.assertSame(value, deserialized);
        Assertions.assertEquals(123, value.get());
    }

    @Test
    public void testByteArrayFIFOQueueSerializer() {
        final ByteArrayFIFOQueue value = new ByteArrayFIFOQueue();
        value.enqueue((byte) 4);
        value.enqueue((byte) 3);
        value.enqueue((byte) 2);

        final ByteBuffer serialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.serialize(value));
        ByteArrayFIFOQueue deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, ByteArrayFIFOQueue.class));

        Assertions.assertEquals(3, value.size());
        Assertions.assertEquals(value.size(), deserialized.size());
        Assertions.assertEquals(value.dequeueByte(), deserialized.dequeueByte());
        Assertions.assertEquals(value.dequeueByte(), deserialized.dequeueByte());
        Assertions.assertEquals(value.dequeueByte(), deserialized.dequeueByte());

        deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, value));

        Assertions.assertSame(value, deserialized);
        Assertions.assertEquals(3, value.size());
        Assertions.assertEquals(4, value.dequeueByte());
        Assertions.assertEquals(3, value.dequeueByte());
        Assertions.assertEquals(2, value.dequeueByte());
    }

    @Test
    public void testInt2LongArrayMapSerializer() {
        final Int2LongArrayMap value = new Int2LongArrayMap();
        value.put(3, 5123L);
        value.put(23, 74251L);
        value.put(75, 9023408235L);

        final ByteBuffer serialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.serialize(value));
        Int2LongArrayMap deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, Int2LongArrayMap.class));

        Assertions.assertEquals(3, value.size());
        Assertions.assertEquals(value.size(), deserialized.size());
        Assertions.assertEquals(value.get(3), deserialized.get(3));
        Assertions.assertEquals(value.get(23), deserialized.get(23));
        Assertions.assertEquals(value.get(75), deserialized.get(75));

        deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, value));

        Assertions.assertSame(value, deserialized);
        Assertions.assertEquals(3, value.size());
        Assertions.assertEquals(5123L, value.get(3));
        Assertions.assertEquals(74251L, value.get(23));
        Assertions.assertEquals(9023408235L, value.get(75));
    }
}
