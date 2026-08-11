package li.cil.sedna.api.device;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;

import java.nio.ByteBuffer;

/**
 * Instances marked with this interface can be treated as random-access memory.
 * <p>
 * For example, CPUs may use this to decide whether a memory region can be stored
 * in a translation look-aside buffer.
 * <p>
 * In particular, implementing this interface communicates that values written to
 * this device can be read back from the same address, and that the {@link MappedMemoryRange}
 * they occupy can be used as a continuous whole, without any inaccessible areas in
 * it.
 */
public abstract class PhysicalMemory implements MemoryMappedDevice, AutoCloseable {
    @Override
    public boolean supportsFetch() {
        return true;
    }

    /**
     * Block-copy data from this physical memory into the specified buffer.
     *
     * @param offset the offset in this memory to start copying from.
     * @param dst    the buffer to copy into.
     * @throws MemoryAccessException if the device fails copying the data.
     */
    public void load(int offset, final ByteBuffer dst) throws MemoryAccessException {
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
    public void store(int offset, final ByteBuffer src) throws MemoryAccessException {
        while (src.hasRemaining()) {
            store(offset++, src.get(), Sizes.SIZE_8_LOG2);
        }
    }

    /**
     * The native address of this memory's backing storage, or zero if direct host access is not
     * available.
     * <p>
     * When returning a non-zero address, the implementation MUST guarantee that the full data of
     * {@link #getLength()} bytes is contiguous at the returned address, native-order accesses
     * through it observe exactly what {@link #load} and {@link #store} observe, and the address
     * stays valid until {@link #close()}. Callers caching the address (e.g. in a TLB) must drop
     * it before the memory is closed or unmapped.
     */
    public long getHostAddress() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws Exception {
    }
}
