package li.cil.sedna.api.debug;

import li.cil.sedna.api.memory.MemoryAccessException;

import java.util.function.LongConsumer;

/**
 * Inspection and control of a running CPU, for debuggers.
 * <p>
 * Memory access through this interface deliberately bypasses access protection and avoids
 * disturbing CPU state such as the TLB, so that observing a machine does not change how it runs.
 */
public interface CPUDebugInterface {
    long getProgramCounter();

    void setProgramCounter(long value);

    void step();

    /**
     * The integer registers, as a live array: writes to it are writes to the CPU.
     */
    long[] getGeneralRegisters();

    /**
     * Reads up to {@code size} bytes. A short result means the read ran into memory that could not
     * be read; that is not an error.
     *
     * @throws MemoryAccessException if the address cannot be translated at all.
     */
    byte[] loadDebug(final long address, final int size) throws MemoryAccessException;

    /**
     * Writes as much of {@code data} as it can, returning the number of bytes written.
     *
     * @throws MemoryAccessException if the address cannot be translated at all.
     */
    int storeDebug(final long address, final byte[] data) throws MemoryAccessException;

    void addBreakpointListener(LongConsumer listener);

    void removeBreakpointListener(LongConsumer listener);

    void addBreakpoint(long address);

    void removeBreakpoint(long address);
}
