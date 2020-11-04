package li.cil.sedna.riscv;

import li.cil.sedna.api.device.InterruptController;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryMap;

import javax.annotation.Nullable;

public interface R5CPU extends Steppable, Resettable, RealTimeCounter, InterruptController {
    static R5CPU create(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        return R5CPUGenerator.create(physicalMemory, rtc);
    }

    static R5CPU create(final MemoryMap physicalMemory) {
        return create(physicalMemory, null);
    }

    long getISA();

    void reset(final boolean hard, final long pc);
}
