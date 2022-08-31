package li.cil.sedna.gdbstub;

import li.cil.sedna.riscv.exception.R5MemoryAccessException;

import java.util.function.LongConsumer;

public interface CPUDebugInterface {
    long[] getGeneralRegisters();
    long getProgramCounter();
    void setProgramCounter(long value);
    long[] getFloatingRegisters();
    byte getFflags();
    void setFflags(byte value);
    byte getFrm();
    void setFrm(byte value);
    int getFcsr();
    void setFcsr(int value);
    byte getPriv();
    void setPriv(byte value);

    void step();
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
