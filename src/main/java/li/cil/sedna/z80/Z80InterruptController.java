package li.cil.sedna.z80;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.InterruptController;
import li.cil.sedna.api.device.bus.InterruptVectorMap;

@Serialized
public final class Z80InterruptController implements InterruptController, InterruptVectorMap {
    private final transient Z80CPU cpu;

    private int raised;

    public Z80InterruptController(final Z80CPU cpu) {
        this.cpu = cpu;
    }

    @Override
    public synchronized void raiseInterrupts(final int mask) {
        update(raised | mask);
    }

    @Override
    public synchronized void lowerInterrupts(final int mask) {
        update(raised & ~mask);
    }

    @Override
    public synchronized int getRaisedInterrupts() {
        return raised;
    }

    /**
     * Interrupt mode 2 reads a two-byte entry from {@code (I << 8) | vector}, so ids land on
     * successive even slots of the guest's table (to skip the vector byte of the address).
     */
    @Override
    public int getVector(final int interruptId) {
        return (interruptId << 1) & 0xFF;
    }

    public synchronized void reset() {
        raised = 0;
        cpu.lowerInterrupt();
    }

    private void update(final int value) {
        raised = value;
        if (raised == 0) {
            cpu.lowerInterrupt();
        } else {
            cpu.raiseInterrupt(getVector(Integer.numberOfTrailingZeros(raised)));
        }
    }
}
