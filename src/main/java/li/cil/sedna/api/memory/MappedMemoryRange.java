package li.cil.sedna.api.memory;

import li.cil.sedna.api.device.MemoryMappedDevice;

import java.util.Objects;

/**
 * Represents a segment of memory mapped by a {@link MemoryMap}.
 */
public final class MappedMemoryRange extends MemoryRange {
    /**
     * The device assigned to this memory range.
     */
    public final MemoryMappedDevice device;

    public MappedMemoryRange(final MemoryMappedDevice device, final long start, final long end) {
        super(start, end);
        this.device = device;
    }

    public MappedMemoryRange(final MemoryMappedDevice device, final long address) {
        this(device, address, address + device.getLength() - 1);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final MappedMemoryRange that = (MappedMemoryRange) o;
        return device.equals(that.device);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), device);
    }

    @Override
    public String toString() {
        return String.format("%s@%s", device, super.toString());
    }
}
