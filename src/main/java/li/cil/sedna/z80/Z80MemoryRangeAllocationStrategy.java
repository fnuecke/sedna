package li.cil.sedna.z80;

import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.api.memory.MemoryRangeAllocationStrategy;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

public final class Z80MemoryRangeAllocationStrategy implements MemoryRangeAllocationStrategy {
    @Override
    public OptionalLong findMemoryRange(final MemoryMappedDevice device, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider) {
        return findMemoryRange(device, intersectProvider, 0);
    }

    @Override
    public OptionalLong findMemoryRange(final MemoryMappedDevice device, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider, final long start) {
        final int length = visibleLength(device);
        if (length <= 0) {
            return OptionalLong.empty();
        }

        if (device instanceof PhysicalMemory) {
            return findFreeRange(Math.max(0, start), length, intersectProvider);
        }

        for (long address = Z80Board.ADDRESS_SPACE_SIZE - length; address >= start; address--) {
            if (intersectProvider.apply(MemoryRange.at(address, length)).isEmpty()) {
                return OptionalLong.of(address);
            }
        }
        return OptionalLong.empty();
    }

    static int visibleLength(final MemoryMappedDevice device) {
        // Yay, special cases. But I can't really think of anything but memory where
        // just mapping a sub-range would be valid. So I don't think this is a blocker.
        return device instanceof PhysicalMemory
            ? Math.min(device.getLength(), Z80Board.ADDRESS_SPACE_SIZE)
            : device.getLength();
    }

    private OptionalLong findFreeRange(final long start, final int length, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider) {
        for (long address = start; address + length <= Z80Board.ADDRESS_SPACE_SIZE; address++) {
            final Optional<? extends MemoryRange> intersect = intersectProvider.apply(MemoryRange.at(address, length));
            if (intersect.isEmpty()) {
                return OptionalLong.of(address);
            }
            address = intersect.get().end;
        }
        return OptionalLong.empty();
    }
}
