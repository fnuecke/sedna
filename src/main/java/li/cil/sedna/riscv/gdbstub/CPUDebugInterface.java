package li.cil.sedna.riscv.gdbstub;

import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import li.cil.sedna.riscv.exception.R5MemoryAccessException;

import java.util.function.LongConsumer;

public interface CPUDebugInterface {
    long[] getGeneralRegisters();
    long getProgramCounter();
    void setProgramCounter(long value);
    long[] getFloatingRegisters();
    byte getPriv();
    void setPriv(byte value);
    long getCSR(short csr) throws R5IllegalInstructionException;
    void setCSR(short csr, long value) throws R5IllegalInstructionException;

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
