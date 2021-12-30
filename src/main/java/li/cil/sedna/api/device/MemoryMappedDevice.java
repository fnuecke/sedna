package li.cil.sedna.api.device;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;

/**
 * {@link MemoryMappedDevice}s can be registered with a {@link MemoryMap}
 * so that they can be accessed via a memory range using the same mechanisms used for accessing RAM.
 */
public interface MemoryMappedDevice extends Device {
    /**
     * The number of bytes this device occupies in memory.
     * <p>
     * This is used by a {@link MemoryMap} to compute the {@link MappedMemoryRange} the
     * device will occupy.
     *
     * @return the size of the device in bytes.
     */
    int getLength();

    /**
     * Returns a bitmask indicating the value sizes supported by this device.
     * <p>
     * Code accessing a memory mapped device, e.g. {@link MemoryMap}s, may use this to verify
     * load and store requests before calling {@link #load(int, int)} and {@link #store(int, long, int)}
     * on a device.
     *
     * @return a bit mask indicating the supported value sizes.
     */
    default int getSupportedSizes() {
        return (1 << Sizes.SIZE_8_LOG2) |
            (1 << Sizes.SIZE_16_LOG2) |
            (1 << Sizes.SIZE_32_LOG2) |
            (1 << Sizes.SIZE_64_LOG2);
    }

    /**
     * Declares whether this device supports fetch operations.
     * <p>
     * In other words, whether this device can hold executable code. This is typically only
     * true for memory-like devices, such as physical memory or flash devices.
     *
     * @return {@code true} if instructions can be fetched from this device; {@code false} otherwise.
     */
    default boolean supportsFetch() {
        return false;
    }

    /**
     * Reads a value from this device.
     * <p>
     * Most devices that are not {@link PhysicalMemory} will cause side-effects from
     * having certain areas of their memory written to.
     *
     * @param offset   the offset local to the device to read from.
     * @param sizeLog2 the size of the value to read, log2. See {@link Sizes}.
     * @return the value read from the device.
     * @throws MemoryAccessException if there was an error accessing the data in this device.
     * @see Sizes
     */
    long load(final int offset, final int sizeLog2) throws MemoryAccessException;

    /**
     * Writes a value to this device.
     * <p>
     * Most devices that are not {@link PhysicalMemory} may cause side-effects from
     * having certain areas of their memory written to.
     *
     * @param offset   the offset local to the device to write to.
     * @param value    the value to write to the device.
     * @param sizeLog2 the size of the value to write, log2. See {@link Sizes}.
     * @throws MemoryAccessException if there was an error accessing the data in this device.
     * @see Sizes
     */
    void store(final int offset, final long value, final int sizeLog2) throws MemoryAccessException;
}
