package li.cil.sedna.benchmark;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;

/**
 * A device that does nothing, so measurements capture the access path rather than the device.
 */
final class NullDevice implements MemoryMappedDevice {
    @Override
    public int getLength() {
        return Vm.PAGE_SIZE;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_32_LOG2) | (1 << Sizes.SIZE_64_LOG2);
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        return 0;
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
    }
}
