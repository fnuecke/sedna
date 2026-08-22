package li.cil.sedna.z80;

import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryMap;

/**
 * A Zilog Z80 with a 64 KiB memory space and a separate 64 KiB port I/O space.
 * <p>
 * {@link Steppable#step(int)} cycles are T-states.
 */
public interface Z80CPU extends Steppable, Resettable {
    static Z80CPU create(final MemoryMap memoryMap, final MemoryMap ioMap) {
        return new Z80CPUImpl(memoryMap, ioMap);
    }

    void reset(boolean hard, int pc);

    void invalidateCaches();

    int getFrequency();

    void setFrequency(int value);

    long getCycles();

    boolean isHalted();

    /**
     * Asserts the INT line. The line stays asserted until {@link #lowerInterrupt()}; {@code data}
     * is the byte the interrupting device places on the bus during the acknowledge cycle (the
     * IM 2 vector, or the opcode in IM 0).
     * <p>
     * The data byte is latched at raise time and the line is a single shared level: boards with
     * more than one interrupting device need an aggregator that arbitrates the vector; the
     * daisy-chain IEI/IEO protocol of real Z80 peripherals is not modeled. May be called from
     * other threads.
     */
    void raiseInterrupt(int data);

    void lowerInterrupt();

    void raiseNMI();
}
