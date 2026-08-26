package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class VirtIONetworkDeviceTests {
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

    private static final int VIRTQ_DESC_F_WRITE = 2;

    private static final int VIRTQ_RECEIVE = 0;
    private static final int VIRTQ_TRANSMIT = 1;

    private static final int HEADER_SIZE = 12;

    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 1024 * 1024;

    private static final int QUEUE_STRIDE = 0x10000;
    private static final long DESC = PHYSICAL_MEMORY_START + 0x1000;
    private static final long AVAIL = PHYSICAL_MEMORY_START + 0x3000;
    private static final long USED = PHYSICAL_MEMORY_START + 0x5000;
    private static final long DATA = PHYSICAL_MEMORY_START + 0x8000;

    private static final int QUEUE_SIZE = 256;

    private MemoryMap memoryMap;
    private VirtIONetworkDevice device;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));
        device = new VirtIONetworkDevice(memoryMap);
        bringUpQueues();
    }

    @Test
    public void wellFormedFrameIsTransmitted() throws Exception {
        final int payload = 64;
        writeTransmitChain(HEADER_SIZE + payload);

        final byte[] packet = device.readEthernetFrame();

        assertNotNull(packet, "a well formed frame must be transmitted");
        assertEquals(payload, packet.length);
        for (int i = 0; i < payload; i++) {
            assertEquals((byte) (HEADER_SIZE + i), packet[i]);
        }
        assertFalse(hasDeviceFailed(), "a well formed frame must not fail the device");
    }

    @Test
    public void frameShorterThanHeaderIsDropped() throws Exception {
        writeTransmitChain(HEADER_SIZE - 1);

        assertNull(assertDoesNotThrow(() -> device.readEthernetFrame()),
                "a chain too short to hold a header must be dropped, not overrun");
    }

    @Test
    public void oversizedFrameIsDropped() throws Exception {
        writeTransmitChain(HEADER_SIZE + 64 * 1024);

        assertNull(assertDoesNotThrow(() -> device.readEthernetFrame()),
                "a frame larger than the maximum frame size must be dropped");
    }

    @Test
    public void undersizedReceiveBufferIsDropped() throws Exception {
        writeReceiveChain(HEADER_SIZE);

        assertDoesNotThrow(() -> device.writeEthernetFrame(new byte[64]),
                "a receive buffer too small for the frame must be dropped, not overrun");
        assertFalse(hasDeviceFailed(), "an undersized receive buffer must not fail the device");
    }

    // --------------------------------------------------------------------- //

    private boolean hasDeviceFailed() {
        return (device.getStatus() & AbstractVirtIODevice.VIRTIO_STATUS_FAILED) != 0
                || (device.getStatus() & AbstractVirtIODevice.VIRTIO_STATUS_DEVICE_NEEDS_RESET) != 0;
    }

    private void bringUpQueues() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
                | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        for (int queue = 0; queue < 2; queue++) {
            device.store(VIRTIO_MMIO_QUEUE_SEL, queue, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_NUM, QUEUE_SIZE, Sizes.SIZE_32_LOG2);
            storeAddress(VIRTIO_MMIO_QUEUE_DESC_LOW, VIRTIO_MMIO_QUEUE_DESC_HIGH, descOf(queue));
            storeAddress(VIRTIO_MMIO_QUEUE_DRIVER_LOW, VIRTIO_MMIO_QUEUE_DRIVER_HIGH, availOf(queue));
            storeAddress(VIRTIO_MMIO_QUEUE_DEVICE_LOW, VIRTIO_MMIO_QUEUE_DEVICE_HIGH, usedOf(queue));
            device.store(VIRTIO_MMIO_QUEUE_READY, 1, Sizes.SIZE_32_LOG2);
        }

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
                | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER_OK, Sizes.SIZE_32_LOG2);
    }

    private static long descOf(final int queue) {
        return DESC + (long) queue * QUEUE_STRIDE;
    }

    private static long availOf(final int queue) {
        return AVAIL + (long) queue * QUEUE_STRIDE;
    }

    private static long usedOf(final int queue) {
        return USED + (long) queue * QUEUE_STRIDE;
    }

    private static long dataOf(final int queue) {
        return DATA + (long) queue * QUEUE_STRIDE;
    }

    private void storeAddress(final int lowRegister, final int highRegister, final long address) {
        device.store(lowRegister, (int) address, Sizes.SIZE_32_LOG2);
        device.store(highRegister, (int) (address >>> 32), Sizes.SIZE_32_LOG2);
    }

    private void writeTransmitChain(final int length) throws MemoryAccessException {
        final long buffer = dataOf(VIRTQ_TRANSMIT);
        for (int i = 0; i < length; i++) {
            memoryMap.store(buffer + i, (byte) i, Sizes.SIZE_8_LOG2);
        }
        writeSingleDescriptorChain(VIRTQ_TRANSMIT, buffer, length, 0);
    }

    private void writeReceiveChain(final int length) throws MemoryAccessException {
        writeSingleDescriptorChain(VIRTQ_RECEIVE, dataOf(VIRTQ_RECEIVE), length, VIRTQ_DESC_F_WRITE);
    }

    private void writeSingleDescriptorChain(final int queue, final long buffer, final int length, final int flags) throws MemoryAccessException {
        final long descriptor = descOf(queue);
        memoryMap.store(descriptor, buffer, Sizes.SIZE_64_LOG2);
        memoryMap.store(descriptor + 8, length, Sizes.SIZE_32_LOG2);
        memoryMap.store(descriptor + 12, flags, Sizes.SIZE_16_LOG2);
        memoryMap.store(descriptor + 14, 0, Sizes.SIZE_16_LOG2);

        final long avail = availOf(queue);
        memoryMap.store(avail, 0, Sizes.SIZE_16_LOG2);
        memoryMap.store(avail + 4, 0, Sizes.SIZE_16_LOG2); // ring[0] = descriptor 0
        memoryMap.store(avail + 2, 1, Sizes.SIZE_16_LOG2); // idx, written last
    }
}
