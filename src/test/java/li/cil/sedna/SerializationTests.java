package li.cil.sedna;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import li.cil.ceres.BinarySerialization;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOConsoleDevice;
import li.cil.sedna.memory.SimpleMemoryMap;
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

    @Test
    public void testUART16550ASerialization() {
        final UART16550A value = new UART16550A();

        value.store(2 /* fcr */, 1 /* enable fifo */, Sizes.SIZE_8_LOG2);
        value.store(0 /* thr */, 5, Sizes.SIZE_8_LOG2);
        value.store(0 /* thr */, 52, Sizes.SIZE_8_LOG2);
        value.store(0 /* thr */, 32, Sizes.SIZE_8_LOG2);
        value.store(0 /* thr */, 72, Sizes.SIZE_8_LOG2);
        value.store(0 /* thr */, 16, Sizes.SIZE_8_LOG2);

        final ByteBuffer serialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.serialize(value));
        final UART16550A deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, UART16550A.class));

        Assertions.assertNotEquals(0, deserialized.load(2 /*fcr */, Sizes.SIZE_8_LOG2) & 1 /* fifo enabled */);
        Assertions.assertEquals(5, deserialized.read());
        Assertions.assertEquals(52, deserialized.read());
        Assertions.assertEquals(32, deserialized.read());
        Assertions.assertEquals(72, deserialized.read());
        Assertions.assertEquals(16, deserialized.read());
    }

    @Test
    public void testVirtIOConsoleDeviceSerialization() throws MemoryAccessException {
        final MemoryMap memoryMap = new SimpleMemoryMap();
        final PhysicalMemory memory = Memory.create(0x600);
        memoryMap.addDevice(0, memory);

        final int descsAddr = 0x000;
        final int descDataAddr = 0x100;
        final int driverAddr = 0x200;
        final int deviceAddr = 0x400;

        final VirtIOConsoleDevice value = new VirtIOConsoleDevice(memoryMap);

        // Initialize device.
        value.store(0x070 /*status*/, 0 /* reset */, Sizes.SIZE_32_LOG2);
        value.store(0x070 /*status*/, 1 /* acknowledge */, Sizes.SIZE_32_LOG2);
        value.store(0x070 /*status*/, 1 | 2 /* driver */, Sizes.SIZE_32_LOG2);
        value.store(0x024 /*driverfeature_sel*/, 1, Sizes.SIZE_32_LOG2);
        value.store(0x020 /*driverfeature*/, 1/*version 1*/, Sizes.SIZE_32_LOG2);
        value.store(0x070 /*status*/, 1 | 2 | 8 /* features_ok */, Sizes.SIZE_32_LOG2);
        value.store(0x070 /*status*/, 1 | 2 | 8 | 4 /* driver_ok */, Sizes.SIZE_32_LOG2);

        // Setup transmit queue.
        value.store(0x030 /*queue_sel*/, 1 /* transmitq */, Sizes.SIZE_32_LOG2);

        // Populate descriptor array.
        for (int i = 0; i < 4; i++) {
            final int addr = descsAddr + i * 16;
            final int dataAddr = descDataAddr + i * 32;
            memory.store(addr /* .addr */, dataAddr, Sizes.SIZE_32_LOG2);
            memory.store(addr + 4 /* .addrh */, 0, Sizes.SIZE_32_LOG2);
            memory.store(addr + 8 /* .len */, 0, Sizes.SIZE_32_LOG2);
            memory.store(addr + 12 /* .flags */, 0, Sizes.SIZE_16_LOG2);
            memory.store(addr + 14 /* .next */, 0, Sizes.SIZE_16_LOG2);
        }

        // Setup driver space.
        memory.store(driverAddr /* .flags */, 0, Sizes.SIZE_16_LOG2);
        memory.store(driverAddr + 2 /* .idx */, 0, Sizes.SIZE_16_LOG2);

        value.store(0x080 /*queue_desc*/, descsAddr, Sizes.SIZE_32_LOG2);
        value.store(0x080 + 4 /*queue_desch*/, 0, Sizes.SIZE_32_LOG2);
        value.store(0x090 /*queue_drv*/, driverAddr, Sizes.SIZE_32_LOG2);
        value.store(0x090 + 4 /*queue_drvh*/, 0, Sizes.SIZE_32_LOG2);
        value.store(0x0A0 /*queue_dev*/, deviceAddr, Sizes.SIZE_32_LOG2);
        value.store(0x0A0 + 4 /*queue_devh*/, 0, Sizes.SIZE_32_LOG2);

        value.store(0x038 /*queue_num*/, 4 /* 4 descriptors */, Sizes.SIZE_32_LOG2);
        value.store(0x044 /*queue_ready*/, 1 /* true */, Sizes.SIZE_32_LOG2);

        // Make one transmit descriptor available with value "test" in it.
        {
            memory.store(descDataAddr, 't', Sizes.SIZE_8_LOG2);
            memory.store(descDataAddr + 1, 'e', Sizes.SIZE_8_LOG2);
            memory.store(descDataAddr + 2, 's', Sizes.SIZE_8_LOG2);
            memory.store(descDataAddr + 3, 't', Sizes.SIZE_8_LOG2);
            memory.store(descsAddr + 8 /* .len */, 4, Sizes.SIZE_32_LOG2);
            memory.store(driverAddr + 4 /* .ring[0] */, 0, Sizes.SIZE_16_LOG2);
            memory.store(driverAddr + 2 /* .idx */, 1, Sizes.SIZE_16_LOG2);
            value.store(0x050 /* notify */, 1 /* queueidx */, Sizes.SIZE_32_LOG2);
        }

        final ByteBuffer serialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.serialize(value));
        value.reset();
        final VirtIOConsoleDevice deserialized = Assertions.assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, value));

        Assertions.assertEquals('t', deserialized.read());
        Assertions.assertEquals('e', deserialized.read());
        Assertions.assertEquals('s', deserialized.read());
        Assertions.assertEquals('t', deserialized.read());
    }
}
