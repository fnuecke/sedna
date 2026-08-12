package li.cil.sedna.device.virtio;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class VirtIOSerializationTests {
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_QUEUE_SEL = 0x030;
    private static final int VIRTIO_MMIO_QUEUE_NUM = 0x038;
    private static final int VIRTIO_MMIO_QUEUE_READY = 0x044;
    private static final int VIRTIO_MMIO_STATUS = 0x070;
    private static final int VIRTIO_MMIO_QUEUE_DESC_LOW = 0x080;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_LOW = 0x090;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_LOW = 0x0A0;

    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1 << 0;

    private static final long PHYSICAL_MEMORY_START = 0x80000000L;

    @BeforeAll
    public static void setUpAll() {
        Sedna.initialize();
    }

    private static final class TestDevice extends AbstractVirtIODevice {
        TestDevice(final MemoryMap memoryMap) {
            super(memoryMap, VirtIODeviceSpec
                    .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_CONSOLE)
                    .queueCount(2)
                    .configSpaceSize(8)
                    .build());
        }
    }

    @Test
    public void deviceStateRoundTrips() {
        final TestDevice original = configuredDevice();
        final ByteBuffer data = BinarySerialization.serialize(original);

        final TestDevice restored = new TestDevice(new SimpleMemoryMap());
        BinarySerialization.deserialize(data, restored);

        assertEquals(original.getStatus(), restored.getStatus());
        assertEquals(original.getNegotiatedFeatures(), restored.getNegotiatedFeatures());
        assertEquals(original.load(VIRTIO_MMIO_QUEUE_READY, Sizes.SIZE_32_LOG2),
                restored.load(VIRTIO_MMIO_QUEUE_READY, Sizes.SIZE_32_LOG2),
                "queue state must survive the round trip");
    }

    @Test
    public void serializedLayoutIsUnchanged() throws Exception {
        final ByteBuffer data = BinarySerialization.serialize(configuredDevice());

        assertEquals(EXPECTED_SERIALIZED_SIZE, data.remaining(),
                "the serialized size of a VirtIO device changed, which means the savestate format changed");

        final byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        assertEquals(EXPECTED_SERIALIZED_DIGEST, toHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "the serialized bytes of a VirtIO device changed, which means the savestate format changed");
    }

    private static final int EXPECTED_SERIALIZED_SIZE = 151;
    private static final String EXPECTED_SERIALIZED_DIGEST =
            "ba66b4ba19df667d7f8ecca47e9b99559d9b877ddeeca7ae51e65f4c5001c52d";

    private static String toHex(final byte[] value) {
        final StringBuilder sb = new StringBuilder(value.length * 2);
        for (final byte b : value) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static TestDevice configuredDevice() {
        final MemoryMap memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(1024 * 1024));
        final TestDevice device = new TestDevice(memoryMap);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
                | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        // Give both queues distinct, non-default state so a layout change is likely to move bytes.
        for (int queue = 0; queue < 2; queue++) {
            device.store(VIRTIO_MMIO_QUEUE_SEL, queue, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_NUM, 64 << queue, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_DESC_LOW, 0x1000 + queue * 0x100, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_DRIVER_LOW, 0x2000 + queue * 0x100, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_DEVICE_LOW, 0x3000 + queue * 0x100, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_READY, 1, Sizes.SIZE_32_LOG2);
        }

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
                | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER_OK, Sizes.SIZE_32_LOG2);

        return device;
    }
}
