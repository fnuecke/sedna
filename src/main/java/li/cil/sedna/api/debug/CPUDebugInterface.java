package li.cil.sedna.api.debug;

import li.cil.sedna.api.memory.MemoryAccessException;

import javax.annotation.Nullable;
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
     * Describes the registers reachable via {@link #getRegister} and {@link #setRegister}, in the
     * format a debugger expects, or {@code null} if the CPU cannot describe itself. Debuggers that
     * get no description are limited to {@link #getGeneralRegisters} and the program counter.
     * <p>
     * The array may be shared between CPUs, and must not be modified.
     */
    @Nullable
    byte[] getTargetDescription();

    /**
     * The size of register {@code id} in bytes, or zero if there is no such register.
     * <p>
     * Register numbering is the debugger's, as laid out by {@link #getTargetDescription}.
     */
    int getRegisterSize(int id);

    /**
     * Reads a register. Only meaningful when {@link #getRegisterSize} reported a non-zero size for
     * {@code id}; the value is zero-extended into the returned long.
     */
    long getRegister(int id);

    /**
     * Writes a register.
     *
     * @return {@code false} if there is no such register, or it cannot be written.
     */
    boolean setRegister(int id, long value);

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
