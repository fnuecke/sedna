package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class VirtIOBlockMediumChangeTests {
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_QUEUE_SEL = 0x030;
    private static final int VIRTIO_MMIO_QUEUE_NUM = 0x038;
    private static final int VIRTIO_MMIO_QUEUE_READY = 0x044;
    private static final int VIRTIO_MMIO_QUEUE_NOTIFY = 0x050;
    private static final int VIRTIO_MMIO_INTERRUPT_STATUS = 0x060;
    private static final int VIRTIO_MMIO_INTERRUPT_ACK = 0x064;
    private static final int VIRTIO_MMIO_STATUS = 0x070;
    private static final int VIRTIO_MMIO_QUEUE_DESC_LOW = 0x080;
    private static final int VIRTIO_MMIO_QUEUE_DESC_HIGH = 0x084;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_LOW = 0x090;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_HIGH = 0x094;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_LOW = 0x0A0;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_HIGH = 0x0A4;

    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1;
    private static final int CONFIG_CHANGE = 2;

    private static final int VIRTQ_DESC_F_NEXT = 1;
    private static final int VIRTQ_DESC_F_WRITE = 2;
    private static final int VIRTIO_BLK_T_IN = 0;
    private static final int VIRTIO_BLK_S_OK = 0;
    private static final int VIRTIO_BLK_S_IOERR = 1;

    private static final int QUEUE_SIZE = 16;
    private static final int SECTOR_SIZE = 512;
    private static final int CAPACITY = 64 * 1024;

    private static final long MEMORY_START = 0x80000000L;
    private static final long DESC = MEMORY_START;
    private static final long AVAIL = MEMORY_START + 0x1000;
    private static final long USED = MEMORY_START + 0x2000;
    private static final long HEADER = MEMORY_START + 0x3000;
    private static final long DATA = MEMORY_START + 0x4000;
    private static final long STATUS = MEMORY_START + 0x5000;

    private MemoryMap memoryMap;
    private VirtIOBlockDevice device;
    private int availIndex;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(MEMORY_START, Memory.create(64 * 1024));
        device = new VirtIOBlockDevice(memoryMap, medium((byte) 'a'));
        availIndex = 0;
        bringUp();
    }

    // --------------------------------------------------------------------- //

    @Test
    public void requestFailsWhileMediumChangeIsPending() throws Exception {
        device.setBlock(medium((byte) 'b'));

        assertEquals(VIRTIO_BLK_S_IOERR, readSector(), "a request during a pending change must fail");
        assertEquals(0, memoryMap.load(DATA, Sizes.SIZE_8_LOG2), "and must not read the new medium");

        device.store(VIRTIO_MMIO_INTERRUPT_ACK, CONFIG_CHANGE, Sizes.SIZE_32_LOG2);

        assertEquals(VIRTIO_BLK_S_OK, readSector(), "once acknowledged, requests run again");
        assertEquals('b', memoryMap.load(DATA, Sizes.SIZE_8_LOG2), "on the new medium");
    }

    @Test
    public void mediumAttachedWithoutNotifySignalsNoChange() throws Exception {
        device.setBlock(medium((byte) 'b'), false);

        assertEquals(0, device.load(VIRTIO_MMIO_INTERRUPT_STATUS, Sizes.SIZE_32_LOG2) & CONFIG_CHANGE,
            "the driver must not see a medium change");
        assertEquals(VIRTIO_BLK_S_OK, readSector(), "requests keep running");
        assertEquals('b', memoryMap.load(DATA, Sizes.SIZE_8_LOG2));
    }

    // --------------------------------------------------------------------- //

    private static ByteBufferBlockDevice medium(final byte fill) {
        final ByteBufferBlockDevice block = ByteBufferBlockDevice.create(CAPACITY, false);
        try (final var output = block.getOutputStream(0)) {
            output.write(new byte[]{fill});
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
        return block;
    }

    private void bringUp() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
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

    /** Reads sector 0 into DATA and returns the status byte the device wrote. */
    private int readSector() throws MemoryAccessException {
        memoryMap.store(HEADER, VIRTIO_BLK_T_IN, Sizes.SIZE_32_LOG2);
        memoryMap.store(HEADER + 4, 0, Sizes.SIZE_32_LOG2);
        memoryMap.store(HEADER + 8, 0, Sizes.SIZE_64_LOG2);
        memoryMap.store(DATA, 0, Sizes.SIZE_64_LOG2);
        memoryMap.store(STATUS, 0xFF, Sizes.SIZE_8_LOG2);
        descriptor(0, HEADER, 16, VIRTQ_DESC_F_NEXT, 1);
        descriptor(1, DATA, SECTOR_SIZE, VIRTQ_DESC_F_NEXT | VIRTQ_DESC_F_WRITE, 2);
        descriptor(2, STATUS, 1, VIRTQ_DESC_F_WRITE, 0);
        memoryMap.store(AVAIL + 4 + (long) (availIndex % QUEUE_SIZE) * 2, 0, Sizes.SIZE_16_LOG2);
        memoryMap.store(AVAIL + 2, ++availIndex, Sizes.SIZE_16_LOG2);

        device.store(VIRTIO_MMIO_QUEUE_NOTIFY, 0, Sizes.SIZE_32_LOG2);
        device.step(1_000_000);

        assertEquals(availIndex, memoryMap.load(USED + 2, Sizes.SIZE_16_LOG2) & 0xFFFF, "request was not completed");
        return (int) (memoryMap.load(STATUS, Sizes.SIZE_8_LOG2) & 0xFF);
    }

    private void descriptor(final int index, final long address, final int length, final int flags, final int next)
        throws MemoryAccessException {
        final long descriptor = DESC + (long) index * 16;
        memoryMap.store(descriptor, address, Sizes.SIZE_64_LOG2);
        memoryMap.store(descriptor + 8, length, Sizes.SIZE_32_LOG2);
        memoryMap.store(descriptor + 12, flags, Sizes.SIZE_16_LOG2);
        memoryMap.store(descriptor + 14, next, Sizes.SIZE_16_LOG2);
    }

    private void storeAddress(final int lowRegister, final int highRegister, final long address) {
        device.store(lowRegister, (int) address, Sizes.SIZE_32_LOG2);
        device.store(highRegister, (int) (address >>> 32), Sizes.SIZE_32_LOG2);
    }
}
