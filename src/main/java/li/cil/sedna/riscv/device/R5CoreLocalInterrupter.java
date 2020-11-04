package li.cil.sedna.riscv.device;

import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.InterruptController;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.riscv.R5;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of a shared CLINT that is aware of one or more harts.
 * <p>
 * See: https://github.com/riscv/riscv-isa-sim/blob/master/riscv/clint.cc
 */
public final class R5CoreLocalInterrupter implements Steppable, InterruptSource, MemoryMappedDevice {
    private static final int CLINT_SIP_BASE = 0x0000;
    private static final int CLINT_TIMECMP_BASE = 0x4000;
    private static final int CLINT_TIME_BASE = 0xBFF8;

    private final RealTimeCounter rtc;

    private final Int2ObjectMap<Interrupt> msips = new Int2ObjectArrayMap<>();
    private final Int2ObjectMap<Interrupt> mtips = new Int2ObjectArrayMap<>();
    @Serialized private final Int2LongArrayMap mtimecmps = new Int2LongArrayMap();

    public R5CoreLocalInterrupter(final RealTimeCounter rtc) {
        this.rtc = rtc;
    }

    public void putHart(final int id, final InterruptController interruptController) {
        final Interrupt msip = new Interrupt(R5.MSIP_SHIFT);
        msip.controller = interruptController;
        msips.put(id, msip);

        final Interrupt mtip = new Interrupt(R5.MTIP_SHIFT);
        mtip.controller = interruptController;
        mtips.put(id, mtip);

        if (!mtimecmps.containsKey(id)) {
            mtimecmps.put(id, -1);
        }
    }

    @Override
    public void step(final int cycles) {
        checkTimeComparators(); // TODO Polling sucks.
    }

    @Override
    public Iterable<Interrupt> getInterrupts() {
        return Stream.concat(msips.values().stream(), mtips.values().stream()).collect(Collectors.toList());
    }

    @Override
    public int getLength() {
        return 0x000C0000;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_32_LOG2);
    }

    public long load(final int offset, final int sizeLog2) {
        if (offset >= CLINT_SIP_BASE && offset < CLINT_TIMECMP_BASE) {
            final int hartId = (offset - CLINT_SIP_BASE) >>> 2;
            if (msips.containsKey(hartId)) {
                if ((offset & 0b11) == 0) {
                    return msips.get(hartId).isRaised() ? 1 : 0;
                }
            }

            return 0;
        } else if (offset >= CLINT_TIMECMP_BASE && offset < CLINT_TIME_BASE) {
            final int hartId = (offset - CLINT_TIMECMP_BASE) >>> 3;
            if (mtimecmps.containsKey(hartId)) {
                if ((offset & 0b111) == 0) {
                    return mtimecmps.get(hartId);
                } else if ((offset & 0b111) == 4) {
                    return (int) (mtimecmps.get(hartId) >>> 32);
                }
            }

            return 0;
        } else if (offset == CLINT_TIME_BASE) {
            return rtc.getTime();
        } else if (offset == CLINT_TIME_BASE + 4) {
            return (int) (rtc.getTime() >>> 32);
        }

        return 0;
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        if (offset >= CLINT_SIP_BASE && offset < CLINT_TIMECMP_BASE) {
            final int hartId = (offset - CLINT_SIP_BASE) >>> 2;
            if (msips.containsKey(hartId)) {
                if ((offset & 0b11) == 0) {
                    if (value == 0) {
                        msips.get(hartId).lowerInterrupt();
                    } else {
                        msips.get(hartId).raiseInterrupt();
                    }
                }
            }
        } else if (offset >= CLINT_TIMECMP_BASE && offset < CLINT_TIME_BASE) {
            final int hartId = (offset - CLINT_TIMECMP_BASE) >>> 3;
            if (mtimecmps.containsKey(hartId)) {
                if ((offset & 0b111) == 0) {
                    long mtimecmp = mtimecmps.get(hartId);
                    if (sizeLog2 == Sizes.SIZE_32_LOG2) {
                        mtimecmp = (mtimecmp & ~0xFFFFFFFFL) | (value & 0xFFFFFFFFL);
                    } else {
                        assert sizeLog2 == Sizes.SIZE_64_LOG2;
                        mtimecmp = value;
                    }
                    mtimecmps.put(hartId, mtimecmp);

                    if (Long.compareUnsigned(mtimecmp, rtc.getTime()) < 0) {
                        mtips.get(hartId).raiseInterrupt();
                    } else {
                        mtips.get(hartId).lowerInterrupt();
                    }
                } else if ((offset & 0b111) == 4) {
                    long mtimecmp = mtimecmps.get(hartId);
                    mtimecmp = (mtimecmp & 0xFFFFFFFFL) | (value << 32);
                    mtimecmps.put(hartId, mtimecmp);

                    if (Long.compareUnsigned(mtimecmp, rtc.getTime()) < 0) {
                        mtips.get(hartId).raiseInterrupt();
                    } else {
                        mtips.get(hartId).lowerInterrupt();
                    }
                }
            }
        }
    }

    private void checkTimeComparators() {
        mtimecmps.forEach((hartId, mtimecmp) -> {
            if (Long.compareUnsigned(mtimecmp, rtc.getTime()) <= 0) {
                mtips.get((int) hartId).raiseInterrupt();
            }
        });
    }
}
