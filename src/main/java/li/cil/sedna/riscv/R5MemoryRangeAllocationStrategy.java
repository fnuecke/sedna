package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.api.memory.MemoryRangeAllocationStrategy;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

public final class R5MemoryRangeAllocationStrategy implements MemoryRangeAllocationStrategy {
    public static final long PHYSICAL_MEMORY_FIRST = 0x80000000L;
    public static final long PHYSICAL_MEMORY_LAST = 0xFFFFFFFFL;
    public static final long DEVICE_MEMORY_FIRST = 0x10000000L;
    public static final long DEVICE_MEMORY_LAST = 0x7FFFFFFFL;

    @Override
    public OptionalLong findMemoryRange(final MemoryMappedDevice device, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider) {
        return findMemoryRange(device, intersectProvider, 0);
    }

    @Override
    public OptionalLong findMemoryRange(final MemoryMappedDevice device, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider, final long start) {
        if (device.getLength() == 0) {
            return OptionalLong.empty();
        }

        final long clampedStart, end;
        if (device instanceof PhysicalMemory) {
            clampedStart = Math.max(PHYSICAL_MEMORY_FIRST, Math.min(PHYSICAL_MEMORY_LAST, start));
            end = PHYSICAL_MEMORY_LAST - device.getLength() + 1;
        } else {
            clampedStart = Math.max(DEVICE_MEMORY_FIRST, Math.min(DEVICE_MEMORY_LAST, start));
            end = DEVICE_MEMORY_LAST - device.getLength() + 1;
        }

        return findFreeRange(clampedStart, end, device.getLength(), intersectProvider);
    }

    private OptionalLong findFreeRange(long start, final long end, final int size, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider) {
        if (size == 0) {
            return OptionalLong.empty();
        }

        if (Long.compareUnsigned(end, start) < 0) {
            return OptionalLong.empty();
        }

        if (Long.compareUnsigned(end - start, size - 1) < 0) {
            return OptionalLong.empty();
        }

        if (Long.compareUnsigned(start, -1L - size) > 0) {
            return OptionalLong.empty();
        }

        // Always align to 64 bit. Otherwise, device I/O may require load/stores of
        // individual byte values, which most device implementations do not support.
        final int alignment = (int) (start % Sizes.SIZE_64_BYTES);
        if (alignment != 0) {
            start = start + (Sizes.SIZE_64_BYTES - alignment);
        }

        final MemoryRange candidateRange = MemoryRange.at(start, size);
        final Optional<? extends MemoryRange> intersect = intersectProvider.apply(candidateRange);
        if (intersect.isPresent()) {
            final long intersectEnd = intersect.get().end;
            if (intersectEnd != -1L) { // Avoid overflow.
                return findFreeRange(intersectEnd + 1, end, size, intersectProvider);
            } else {
                return OptionalLong.empty();
            }
        }

        return OptionalLong.of(start);
    }
}
