package li.cil.sedna.z80;

import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.api.memory.MemoryRangeAllocationStrategy;
import li.cil.sedna.device.bus.DevicePortRegistry;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;

public final class Z80PortAllocationStrategy implements MemoryRangeAllocationStrategy {
    @Override
    public OptionalLong findMemoryRange(final MemoryMappedDevice device, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider) {
        return findMemoryRange(device, intersectProvider, 0);
    }

    @Override
    public OptionalLong findMemoryRange(final MemoryMappedDevice device, final Function<MemoryRange, Optional<? extends MemoryRange>> intersectProvider, final long start) {
        final OptionalInt maybeWidth = DevicePortRegistry.getWidth(device);
        if (maybeWidth.isEmpty()) {
            return OptionalLong.empty();
        }

        final int width = maybeWidth.getAsInt();
        for (long port = Math.max(0, start); port + width <= Z80Board.RESERVED_PORT; port++) {
            if (intersectProvider.apply(MemoryRange.at(port, width)).isEmpty()) {
                return OptionalLong.of(port);
            }
        }
        return OptionalLong.empty();
    }
}
