package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DescriptorChainTests {
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_QUEUE_SEL = 0x030;
    private static final int VIRTIO_MMIO_QUEUE_NUM = 0x038;
    private static final int VIRTIO_MMIO_QUEUE_READY = 0x044;
    private static final int VIRTIO_MMIO_STATUS = 0x070;
    private static final int VIRTIO_MMIO_QUEUE_DESC_LOW = 0x080;
    private static final int VIRTIO_MMIO_QUEUE_DESC_HIGH = 0x084;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_LOW = 0x090;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_HIGH = 0x094;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_LOW = 0x0A0;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_HIGH = 0x0A4;

    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1 << 0; // Feature bit 32, in the high word.

    private static final int VIRTQ_DESC_F_NEXT = 1;

    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 1024 * 1024;

    private static final long DESC = PHYSICAL_MEMORY_START + 0x1000;
    private static final long AVAIL = PHYSICAL_MEMORY_START + 0x3000;
    private static final long USED = PHYSICAL_MEMORY_START + 0x5000;
    private static final long DATA = PHYSICAL_MEMORY_START + 0x8000;

    private static final int QUEUE_SIZE = 256;
    private static final int BYTES_PER_DESCRIPTOR = 4;

    private static final int MAX_CHAIN_LENGTH = 128;

    private MemoryMap memoryMap;
    private TestDevice device;

    private static final class TestDevice extends AbstractVirtIODevice {
        TestDevice(final MemoryMap memoryMap) {
            super(memoryMap, VirtIODeviceSpec
                .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_CONSOLE)
                .queueCount(1)
                .configSpaceSize(0)
                .build());
        }

        @Nullable
        VirtqueueIterator queue() {
            return getQueueIterator(0);
        }
    }

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));
        device = new TestDevice(memoryMap);
    }

    @Test
    public void chainLongerThanFiftyTwoDescriptorsIsFullyReadable() throws Exception {
        final int chainLength = 64;
        bringUpQueue();
        writeReadOnlyChain(chainLength);

        final VirtqueueIterator queue = device.queue();
        assertNotNull(queue, "queue must be available after feature negotiation");
        assertTrue(queue.hasNext(), "the driver made a descriptor chain available");

        final DescriptorChain chain = queue.next();
        assertEquals(chainLength * BYTES_PER_DESCRIPTOR, chain.readableBytes());

        for (int i = 0; i < chainLength * BYTES_PER_DESCRIPTOR; i++) {
            assertEquals((byte) i, chain.get(),
                String.format("byte %d of a %d-descriptor chain must be readable", i, chainLength));
        }

        assertEquals(0, chain.readableBytes());
        assertEquals(0, device.getStatus() & AbstractVirtIODevice.VIRTIO_STATUS_DEVICE_NEEDS_RESET,
            "walking a legal chain must not put the device into an error state");
    }

    @Test
    public void excessivelyLongChainIsRefused() throws Exception {
        bringUpQueue();
        writeReadOnlyChain(MAX_CHAIN_LENGTH + 32);

        final VirtqueueIterator queue = device.queue();
        assertNotNull(queue);
        assertTrue(queue.hasNext());

        assertThrows(VirtIODeviceException.class, queue::next,
            "a chain at or beyond the maximum length must be refused");
        assertTrue((device.getStatus() & AbstractVirtIODevice.VIRTIO_STATUS_DEVICE_NEEDS_RESET) != 0,
            "refusing a chain must put the device into an error state");
    }

    @Test
    public void shortChainStillWorks() throws Exception {
        bringUpQueue();
        writeReadOnlyChain(3);

        final VirtqueueIterator queue = device.queue();
        assertNotNull(queue);
        assertTrue(queue.hasNext());

        final DescriptorChain chain = queue.next();
        assertEquals(3 * BYTES_PER_DESCRIPTOR, chain.readableBytes());
        for (int i = 0; i < 3 * BYTES_PER_DESCRIPTOR; i++) {
            assertEquals((byte) i, chain.get());
        }
        assertFalse(queue.hasNext(), "only one chain was made available");
    }

    private void bringUpQueue() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_QUEUE_SEL, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_QUEUE_NUM, QUEUE_SIZE, Sizes.SIZE_32_LOG2);
        storeAddress(VIRTIO_MMIO_QUEUE_DESC_LOW, VIRTIO_MMIO_QUEUE_DESC_HIGH, DESC);
        storeAddress(VIRTIO_MMIO_QUEUE_DRIVER_LOW, VIRTIO_MMIO_QUEUE_DRIVER_HIGH, AVAIL);
        storeAddress(VIRTIO_MMIO_QUEUE_DEVICE_LOW, VIRTIO_MMIO_QUEUE_DEVICE_HIGH, USED);
        device.store(VIRTIO_MMIO_QUEUE_READY, 1, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER_OK, Sizes.SIZE_32_LOG2);
    }

    private void storeAddress(final int lowRegister, final int highRegister, final long address) {
        device.store(lowRegister, (int) address, Sizes.SIZE_32_LOG2);
        device.store(highRegister, (int) (address >>> 32), Sizes.SIZE_32_LOG2);
    }

    private void writeReadOnlyChain(final int length) throws MemoryAccessException {
        for (int i = 0; i < length; i++) {
            final long descriptor = DESC + (long) i * 16;
            final long buffer = DATA + (long) i * BYTES_PER_DESCRIPTOR;

            memoryMap.store(descriptor, buffer, Sizes.SIZE_64_LOG2);
            memoryMap.store(descriptor + 8, BYTES_PER_DESCRIPTOR, Sizes.SIZE_32_LOG2);
            memoryMap.store(descriptor + 12, i < length - 1 ? VIRTQ_DESC_F_NEXT : 0, Sizes.SIZE_16_LOG2);
            memoryMap.store(descriptor + 14, i + 1, Sizes.SIZE_16_LOG2);

            for (int j = 0; j < BYTES_PER_DESCRIPTOR; j++) {
                memoryMap.store(buffer + j, (byte) (i * BYTES_PER_DESCRIPTOR + j), Sizes.SIZE_8_LOG2);
            }
        }

        // virtq_avail: flags, idx, ring[]. Publish descriptor 0 as the head of the chain.
        memoryMap.store(AVAIL, 0, Sizes.SIZE_16_LOG2);
        memoryMap.store(AVAIL + 4, 0, Sizes.SIZE_16_LOG2); // ring[0] = descriptor 0
        memoryMap.store(AVAIL + 2, 1, Sizes.SIZE_16_LOG2); // idx, written last
    }
}
