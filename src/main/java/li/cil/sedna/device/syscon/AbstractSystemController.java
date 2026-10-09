package li.cil.sedna.device.syscon;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singleton;

public abstract class AbstractSystemController implements MemoryMappedDevice, InterruptSource, Resettable, Steppable {
    public static final int SYSCON_RESET = 0x1000;
    public static final int SYSCON_POWEROFF = 0x2000;

    public static final int ID = 'S' << 24 | 'S' << 16 | 'C' << 8 | '1';

    public static final int COMMAND_REGISTER = 0x00; // W: SYSCON_RESET or SYSCON_POWEROFF.
    public static final int ID_REGISTER = 0x04;      // R: ID.
    public static final int CONTROL_REGISTER = 0x08; // RW: CONTROL_* bits.
    public static final int STATUS_REGISTER = 0x0C;  // R: STATUS_* bits of pending requests. W: 1 clears.

    public static final int CONTROL_ENABLE = 1 << 1;

    public static final int STATUS_POWEROFF = 1 << 0;
    public static final int STATUS_RESET = 1 << 1;

    private final Interrupt interrupt = new Interrupt();

    @Serialized
    private final AtomicInteger requested = new AtomicInteger();
    @Serialized
    private volatile int control;
    @Serialized
    private int status;

    public Interrupt getInterrupt() {
        return interrupt;
    }

    public boolean requestPowerOff() {
        return request(STATUS_POWEROFF);
    }

    public boolean requestReset() {
        return request(STATUS_RESET);
    }

    @Override
    public Iterable<Interrupt> getInterrupts() {
        return singleton(interrupt);
    }

    @Override
    public void reset() {
        requested.set(0);
        control = 0;
        status = 0;
        interrupt.lowerInterrupt();
    }

    @Override
    public void step(final int cycles) {
        final int value = requested.getAndSet(0);
        if (value != 0) {
            status |= value;
            interrupt.raiseInterrupt();
        }
    }

    @Override
    public int getLength() {
        return 0x10;
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        return switch (offset) {
            case ID_REGISTER -> ID;
            case CONTROL_REGISTER -> control;
            case STATUS_REGISTER -> status;
            default -> 0;
        };
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        switch (offset) {
            case COMMAND_REGISTER -> {
                switch ((int) (value & 0xFFFF)) {
                    case SYSCON_RESET -> handleReset();
                    case SYSCON_POWEROFF -> handlePowerOff();
                }
            }
            case CONTROL_REGISTER -> control = (int) value & CONTROL_ENABLE;
            case STATUS_REGISTER -> {
                status &= ~(int) value;
                if (status == 0) {
                    interrupt.lowerInterrupt();
                }
            }
        }
    }

    protected abstract void handleReset();

    protected abstract void handlePowerOff();

    private boolean request(final int mask) {
        if ((control & CONTROL_ENABLE) == 0) {
            return false;
        }

        requested.getAndUpdate(value -> value | mask);
        return true;
    }
}
