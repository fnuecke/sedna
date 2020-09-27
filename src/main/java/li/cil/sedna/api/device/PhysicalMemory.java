package li.cil.sedna.api.device;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryRange;

import java.nio.ByteBuffer;

/**
 * Instances marked with this interface can be treated as random-access memory.
 * <p>
 * For example, CPUs may use this to decide whether a memory region can be stored
 * in a translation look-aside buffer.
 * <p>
 * In particular, implementing this interface communicates that values written to
 * this device can be read back from the same address, and that the {@link MemoryRange}
 * they occupy can be used as a continuous whole, without any inaccessible areas in
 * it.
 */
public interface PhysicalMemory extends MemoryMappedDevice {
    /**
     * Block-copy data from this physical memory into the specified buffer.
     *
     * @param offset the offset in this memory to start copying from.
     * @param dst    the buffer to copy into.
     * @throws MemoryAccessException if the device fails copying the data.
     */
    default void load(int offset, final ByteBuffer dst) throws MemoryAccessException {
        while (dst.hasRemaining()) {
            dst.put((byte) load(offset++, Sizes.SIZE_8_LOG2));
        }
    }

    /**
     * Block-copy data to this physical memory from the specified buffer.
     *
     * @param offset the offset in this memory to start copying to.
     * @param src    the buffer to copy from.
     * @throws MemoryAccessException if the device fails copying the data.
     */
    default void store(int offset, final ByteBuffer src) throws MemoryAccessException {
        while (src.hasRemaining()) {
            store(offset++, src.get(), Sizes.SIZE_8_LOG2);
        }
    }
}
