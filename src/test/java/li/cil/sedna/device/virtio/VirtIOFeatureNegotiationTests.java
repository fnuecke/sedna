package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class VirtIOFeatureNegotiationTests {
    private static final int VIRTIO_MMIO_DEVICE_FEATURES = 0x010;
    private static final int VIRTIO_MMIO_DEVICE_FEATURES_SEL = 0x014;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_STATUS = 0x070;

    /**
     * Feature bits 32 and up live in the high word, selected by writing 1 to the features select
     * register. VIRTIO_F_VERSION_1 is bit 32 and VIRTIO_F_RING_PACKED is bit 34.
     */
    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1 << 0;
    private static final int RING_PACKED_HIGH = 1 << 2;

    private static final class PackedRingDevice extends AbstractVirtIODevice {
        PackedRingDevice(final MemoryMap memoryMap) {
            super(memoryMap, VirtIODeviceSpec
                    .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_CONSOLE)
                    .features(VIRTIO_F_RING_PACKED)
                    .queueCount(1)
                    .configSpaceSize(0)
                    .build());
        }
    }

    @Test
    public void theTestDeviceOffersPackedRings() {
        final PackedRingDevice device = new PackedRingDevice(new SimpleMemoryMap());

        device.store(VIRTIO_MMIO_DEVICE_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        final long offered = device.load(VIRTIO_MMIO_DEVICE_FEATURES, Sizes.SIZE_32_LOG2);

        assertNotEquals(0, offered & RING_PACKED_HIGH);
    }

    @Test
    public void negotiatingPackedRingsClearsFeaturesOK() {
        final PackedRingDevice device = new PackedRingDevice(new SimpleMemoryMap());

        negotiate(device, VERSION_1_HIGH | RING_PACKED_HIGH);

        assertEquals(0, device.load(VIRTIO_MMIO_STATUS, Sizes.SIZE_32_LOG2) & AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK,
                "device must refuse a feature set including packed rings");
    }

    @Test
    public void negotiatingOnlyImplementedFeaturesKeepsFeaturesOK() {
        final PackedRingDevice device = new PackedRingDevice(new SimpleMemoryMap());

        negotiate(device, VERSION_1_HIGH);

        assertNotEquals(0, device.load(VIRTIO_MMIO_STATUS, Sizes.SIZE_32_LOG2) & AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK,
                "device must accept a feature set it can support");
    }

    @Test
    public void negotiatingWithoutVersion1ClearsFeaturesOK() {
        final PackedRingDevice device = new PackedRingDevice(new SimpleMemoryMap());

        negotiate(device, 0);

        assertEquals(0, device.load(VIRTIO_MMIO_STATUS, Sizes.SIZE_32_LOG2) & AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK,
                "device must refuse a driver that does not accept VIRTIO_F_VERSION_1");
    }

    private static void negotiate(final AbstractVirtIODevice device, final int featuresHigh) {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, featuresHigh, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS,
                AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);
    }
}
