package li.cil.sedna.z80;

import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryMap;

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

    void raiseInterrupt(int data);

    void lowerInterrupt();

    void raiseNMI();
}
