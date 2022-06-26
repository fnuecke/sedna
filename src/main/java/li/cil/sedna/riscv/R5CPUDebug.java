package li.cil.sedna.riscv;

import li.cil.sedna.gdbstub.GDBStub;
import li.cil.sedna.riscv.exception.R5MemoryAccessException;

public interface R5CPUDebug {
    long getPc();

    void setPc(long pc);

    long[] getX();

    void setGDBStub(GDBStub stub);

    byte[] loadDebug(final long address, final int size) throws R5MemoryAccessException;

    int storeDebug(final long address, final byte[] data) throws R5MemoryAccessException;

    void addBreakpoint(long virtualAddress);

    void removeBreakpoint(long virtualAddress);

    void step();
}
