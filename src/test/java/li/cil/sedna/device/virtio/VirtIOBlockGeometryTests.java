package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without {@code VIRTIO_BLK_F_GEOMETRY} the Linux driver falls back to reporting the capacity in
 * mebibytes as the cylinder count, which is zero for anything smaller, such as a floppy.
 */
public final class VirtIOBlockGeometryTests {
    private static final int VIRTIO_MMIO_DEVICE_FEATURES = 0x010;
    private static final int VIRTIO_MMIO_DEVICE_FEATURES_SEL = 0x014;
    private static final int VIRTIO_MMIO_CONFIG = 0x100;

    private static final int VIRTIO_BLK_F_GEOMETRY = 1 << 4;

    // The driver reads each geometry field on its own, at its own width.
    private static final int CYLINDERS_OFFSET = VIRTIO_MMIO_CONFIG + 16;
    private static final int HEADS_OFFSET = VIRTIO_MMIO_CONFIG + 18;
    private static final int SECTORS_OFFSET = VIRTIO_MMIO_CONFIG + 19;

    private static final int FLOPPY_CAPACITY = 512 * 1024;
    private static final int HARD_DRIVE_CAPACITY = 8 * 1024 * 1024;

    private static final int DEVICE_ADDRESS = 0x10000000;

    // --------------------------------------------------------------------- //

    @Test
    public void deviceOffersGeometry() {
        final VirtIOBlockDevice device = device(FLOPPY_CAPACITY);

        device.store(VIRTIO_MMIO_DEVICE_FEATURES_SEL, 0, Sizes.SIZE_32_LOG2);
        final long offered = device.load(VIRTIO_MMIO_DEVICE_FEATURES, Sizes.SIZE_32_LOG2);

        assertNotEquals(0, offered & VIRTIO_BLK_F_GEOMETRY,
            "device must offer geometry, or the driver makes up its own");
    }

    @Test
    public void floppySizedDeviceReportsAtLeastOneCylinder() {
        final VirtIOBlockDevice device = device(FLOPPY_CAPACITY);

        assertTrue(cylinders(device) > 0,
            "a floppy reporting zero cylinders cannot be partitioned");
    }

    @Test
    public void geometryFieldsAreReadableAtTheirOwnWidths() {
        final VirtIOBlockDevice device = device(HARD_DRIVE_CAPACITY);

        assertEquals(16, device.load(HEADS_OFFSET, Sizes.SIZE_8_LOG2));
        assertEquals(63, device.load(SECTORS_OFFSET, Sizes.SIZE_8_LOG2));
        // 8 MiB is 16384 sectors, so 16384 / (16 * 63) cylinders.
        assertEquals(16, cylinders(device));
    }

    @Test
    public void geometryDescribesNoMoreSectorsThanThereAre() {
        for (final int capacity : new int[]{0, FLOPPY_CAPACITY, 2 * 1024 * 1024, 4 * 1024 * 1024, HARD_DRIVE_CAPACITY}) {
            final VirtIOBlockDevice device = device(capacity);
            final long described = (long) cylinders(device) * 16 * 63;

            assertTrue(described <= capacity / 512,
                "geometry claims " + described + " sectors, device only has " + (capacity / 512));
        }
    }

    @Test
    public void deviceWithoutMediumHasNoGeometry() {
        assertEquals(0, cylinders(device(0)),
            "a device with no medium must not describe cylinders it does not have");
    }

    /**
     * The guest reaches config space through the memory map, which refuses widths the device does
     * not advertise. Reading the device directly would not catch that.
     */
    @Test
    public void geometryIsReadableThroughTheMemoryMap() throws MemoryAccessException {
        final SimpleMemoryMap memoryMap = new SimpleMemoryMap();
        final VirtIOBlockDevice device = new VirtIOBlockDevice(memoryMap,
            ByteBufferBlockDevice.create(HARD_DRIVE_CAPACITY, false));
        assertTrue(memoryMap.addDevice(DEVICE_ADDRESS, device));

        assertEquals(16, memoryMap.load(DEVICE_ADDRESS + CYLINDERS_OFFSET, Sizes.SIZE_16_LOG2) & 0xFFFF);
        assertEquals(16, memoryMap.load(DEVICE_ADDRESS + HEADS_OFFSET, Sizes.SIZE_8_LOG2) & 0xFF);
        assertEquals(63, memoryMap.load(DEVICE_ADDRESS + SECTORS_OFFSET, Sizes.SIZE_8_LOG2) & 0xFF);
    }

    /**
     * Legacy virtio-mmio drivers read config space one byte at a time.
     */
    @Test
    public void byteWiseReadReconstructsCylinders() {
        // 600 MiB is 1228800 sectors, so 1228800 / (16 * 63) = 1219 cylinders, which needs both bytes.
        final VirtIOBlockDevice device = new VirtIOBlockDevice(new SimpleMemoryMap(),
            new CapacityOnlyBlockDevice(600L * 1024 * 1024));
        assertEquals(1219, cylinders(device));

        final int low = (int) (device.load(CYLINDERS_OFFSET, Sizes.SIZE_8_LOG2) & 0xFF);
        final int high = (int) (device.load(CYLINDERS_OFFSET + 1, Sizes.SIZE_8_LOG2) & 0xFF);

        assertEquals(1219, low | (high << 8),
            "a driver reading the field byte by byte must see the same cylinder count");
    }

    @Test
    public void wholeWordReadCarriesEveryField() {
        final VirtIOBlockDevice device = device(HARD_DRIVE_CAPACITY);

        final long word = device.load(CYLINDERS_OFFSET, Sizes.SIZE_32_LOG2);

        assertEquals(cylinders(device), (int) (word & 0xFFFF));
        assertEquals(16, (int) ((word >>> 16) & 0xFF));
        assertEquals(63, (int) ((word >>> 24) & 0xFF));
    }

    // --------------------------------------------------------------------- //

    private static int cylinders(final VirtIOBlockDevice device) {
        return (int) (device.load(CYLINDERS_OFFSET, Sizes.SIZE_16_LOG2) & 0xFFFF);
    }

    private static VirtIOBlockDevice device(final int capacity) {
        return new VirtIOBlockDevice(new SimpleMemoryMap(), ByteBufferBlockDevice.create(capacity, false));
    }

    /**
     * Reports a capacity without backing it, so tests can cover drives too large to allocate.
     */
    private record CapacityOnlyBlockDevice(long capacity) implements BlockDevice {
        @Override
        public boolean isReadonly() {
            return true;
        }

        @Override
        public long getCapacity() {
            return capacity;
        }

        @Override
        public InputStream getInputStream(final long offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getOutputStream(final long offset) {
            throw new UnsupportedOperationException();
        }
    }
}
