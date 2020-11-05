package li.cil.sedna.device.rtc;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.rtc.RealTimeCounter;

import java.util.Collections;

/**
 * Goldfish RTC.
 * <p>
 * See: https://android.googlesource.com/platform/external/qemu/+/master/docs/GOLDFISH-VIRTUAL-HARDWARE.TXT
 */
public final class GoldfishRTC implements InterruptSource, MemoryMappedDevice {
    private static final int TIME_LOW = 0x00;        // R: Get current time, then return low-order 32-bits.
    private static final int TIME_HIGH = 0x04;       // R: Return high 32-bits, from previous TIME_LOW read.
    private static final int ALARM_LOW = 0x08;       // W: Set low 32-bit value or alarm, then arm it.
    private static final int ALARM_HIGH = 0x0c;      // W: Set high 32-bit value of alarm.
    private static final int CLEAR_INTERRUPT = 0x10; // W: Lower device's irq level.

    private final RealTimeCounter rtc;
    private final Interrupt interrupt = new Interrupt();

    @Serialized private long time;

    public GoldfishRTC(final RealTimeCounter rtc) {
        this.rtc = rtc;
    }

    public Interrupt getInterrupt() {
        return interrupt;
    }

    @Override
    public Iterable<Interrupt> getInterrupts() {
        return Collections.singleton(interrupt);
    }

    @Override
    public int getLength() {
        return 0x20;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_32_LOG2) |
               (1 << Sizes.SIZE_64_LOG2);
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        switch (offset) {
            case TIME_LOW: {
                time = rtc.getTime() / rtc.getFrequency() * 1_000_000_000;
                return time;
            }
            case TIME_HIGH: {
                return (int) (time >>> 32);
            }
        }

        return 0;
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        // all no-ops
    }
}
