package li.cil.sedna.gdbstub;

import li.cil.sedna.riscv.exception.R5MemoryAccessException;

import java.util.function.LongConsumer;

public interface CPUDebugInterface {
    long getProgramCounter();

    void setProgramCounter(long value);

    void step();

    long[] getGeneralRegisters();

    byte[] loadDebug(final long address, final int size) throws R5MemoryAccessException;

    int storeDebug(final long address, final byte[] data) throws R5MemoryAccessException;

    void addBreakpointListener(LongConsumer listener);

    void removeBreakpointListener(LongConsumer listener);

    void addBreakpoint(long address);

    void removeBreakpoint(long address);

    void addWatchpointListener(final LongConsumer listener);

    void removeWatchpointListener(final LongConsumer listener);

    void addWatchpoint(Watchpoint watchpoint);

    void removeWatchpoint(Watchpoint watchpoint);
}
