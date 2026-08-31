package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.evdev.EvdevEvents;
import li.cil.sedna.evdev.EvdevKeys;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public final class VirtIOKeyboardDeviceTests {
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

    private static final int VIRTQ_EVENT = 0;

    private static final int EVENT_SIZE = 8;

    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 1024 * 1024;

    private static final long DESC = PHYSICAL_MEMORY_START + 0x1000;
    private static final long AVAIL = PHYSICAL_MEMORY_START + 0x3000;
    private static final long USED = PHYSICAL_MEMORY_START + 0x5000;
    private static final long DATA = PHYSICAL_MEMORY_START + 0x8000;

    private static final int QUEUE_SIZE = 256;

    private MemoryMap memoryMap;
    private VirtIOKeyboardDevice device;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));
        device = new VirtIOKeyboardDevice(memoryMap);
        bringUpEventQueue();
    }

    @Test
    public void keyEventIsDeliveredAsKeyAndSynPair() throws Exception {
        writeEventChains(2);

        device.sendKeyEvent(EvdevKeys.KEY_A, true);
        device.step(1);

        assertEquals(2, usedIdx(), "a key event must deliver the key and its syn");
        assertEventAt(0, EvdevEvents.EV_KEY, EvdevKeys.KEY_A, 1);
        assertEventAt(1, EvdevEvents.EV_SYN, 0, 0);
    }

    @Test
    public void eventValueSurvivesPackingForFullIntRange() throws Exception {
        writeEventChains(2);

        device.putEvent(EvdevEvents.EV_KEY, 0xFFFF, -1);
        device.putEvent(EvdevEvents.EV_KEY, 1, Integer.MIN_VALUE);
        device.step(1);

        assertEquals(2, usedIdx());
        assertEventAt(0, EvdevEvents.EV_KEY, 0xFFFF, -1);
        assertEventAt(1, EvdevEvents.EV_KEY, 1, Integer.MIN_VALUE);
    }

    @Test
    public void eventsBeyondBoundAreDropped() throws Exception {
        writeEventChains(QUEUE_SIZE);

        // 33 key events at two entries each exceed the 64 pending event bound by one pair.
        for (int i = 0; i < 33; i++) {
            device.sendKeyEvent(EvdevKeys.KEY_A, true);
        }
        device.step(1);

        assertEquals(64, usedIdx(), "events beyond the pending bound must be dropped");
    }

    @Test
    public void droppedEventsFreeTheirBudget() throws Exception {
        writeEventChains(QUEUE_SIZE);

        for (int i = 0; i < 40; i++) {
            device.sendKeyEvent(EvdevKeys.KEY_A, true);
        }
        device.step(1);
        device.sendKeyEvent(EvdevKeys.KEY_B, false);
        device.step(1);

        assertEquals(66, usedIdx(), "draining must free the pending event budget");
        assertEventAt(64, EvdevEvents.EV_KEY, EvdevKeys.KEY_B, 0);
    }

    @Test
    public void pendingEventsSurviveSerialization() throws Exception {
        device.sendKeyEvent(EvdevKeys.KEY_A, true);

        final java.nio.ByteBuffer serialized = li.cil.ceres.BinarySerialization.serialize(device);
        final VirtIOKeyboardDevice restored = new VirtIOKeyboardDevice(memoryMap);
        li.cil.ceres.BinarySerialization.deserialize(serialized, restored);

        writeEventChains(2);
        restored.step(1);

        assertEquals(2, usedIdx(), "pending events must survive serialization");
        assertEventAt(0, EvdevEvents.EV_KEY, EvdevKeys.KEY_A, 1);
        assertEventAt(1, EvdevEvents.EV_SYN, 0, 0);
    }

    @Test
    public void keyEventFromOtherThreadIsDelivered() throws Exception {
        writeEventChains(2);

        CompletableFuture.runAsync(() -> device.sendKeyEvent(EvdevKeys.KEY_ENTER, true)).get();
        device.step(1);

        assertEquals(2, usedIdx(), "events enqueued from another thread must be delivered");
        assertEventAt(0, EvdevEvents.EV_KEY, EvdevKeys.KEY_ENTER, 1);
    }

    // --------------------------------------------------------------------- //

    private int usedIdx() throws MemoryAccessException {
        return (int) memoryMap.load(USED + 2, Sizes.SIZE_16_LOG2) & 0xFFFF;
    }

    private void assertEventAt(final int index, final int type, final int code, final int value) throws MemoryAccessException {
        final long buffer = DATA + (long) index * EVENT_SIZE;
        assertEquals(type, (int) memoryMap.load(buffer, Sizes.SIZE_16_LOG2) & 0xFFFF);
        assertEquals(code, (int) memoryMap.load(buffer + 2, Sizes.SIZE_16_LOG2) & 0xFFFF);
        assertEquals(value, (int) memoryMap.load(buffer + 4, Sizes.SIZE_32_LOG2));
    }

    private void bringUpEventQueue() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_QUEUE_SEL, VIRTQ_EVENT, Sizes.SIZE_32_LOG2);
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

    private void writeEventChains(final int count) throws MemoryAccessException {
        for (int i = 0; i < count; i++) {
            final long descriptor = DESC + (long) i * 16;
            memoryMap.store(descriptor, DATA + (long) i * EVENT_SIZE, Sizes.SIZE_64_LOG2);
            memoryMap.store(descriptor + 8, EVENT_SIZE, Sizes.SIZE_32_LOG2);
            memoryMap.store(descriptor + 12, VIRTQ_DESC_F_WRITE, Sizes.SIZE_16_LOG2);
            memoryMap.store(descriptor + 14, 0, Sizes.SIZE_16_LOG2);

            memoryMap.store(AVAIL + 4 + (long) i * 2, i, Sizes.SIZE_16_LOG2);
        }

        memoryMap.store(AVAIL, 0, Sizes.SIZE_16_LOG2);
        memoryMap.store(AVAIL + 2, count, Sizes.SIZE_16_LOG2); // idx, written last
    }
}
