package li.cil.sedna.memory;

import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class SimpleMemoryMap implements MemoryMap {
    private final Map<MemoryMappedDevice, MappedMemoryRange> devices = new HashMap<>();
    private MappedMemoryRange[] sortedRanges = new MappedMemoryRange[0];

    // For device IO we often get sequential access to the same range/device, so we remember the last one as a cache.
    private MappedMemoryRange cache;

    @Override
    public boolean addDevice(final long address, final MemoryMappedDevice device) {
        if (devices.containsKey(device)) {
            return false;
        }

        final MappedMemoryRange deviceRange = new MappedMemoryRange(device, address);
        for (final MappedMemoryRange range : sortedRanges) {
            if (range.intersects(deviceRange)) {
                return false;
            }
        }

        devices.put(device, deviceRange);
        rebuildSortedRanges();
        return true;
    }

    @Override
    public void removeDevice(final MemoryMappedDevice device) {
        devices.remove(device);
        rebuildSortedRanges();
        if (cache != null && cache.device == device) {
            cache = null;
        }
    }

    @Override
    public Optional<MappedMemoryRange> getMemoryRange(final MemoryMappedDevice device) {
        return Optional.ofNullable(devices.get(device));
    }

    @Override
    public Optional<MappedMemoryRange> getMemoryRange(final MemoryRange range) {
        for (final MappedMemoryRange existingRange : sortedRanges) {
            if (existingRange.intersects(range)) {
                return Optional.of(existingRange);
            }
        }

        return Optional.empty();
    }

    @Nullable
    @Override
    public MappedMemoryRange getMemoryRange(final long address) {
        final MappedMemoryRange cachedValue = cache; // Copy to local to avoid threading issues.
        if (cachedValue != null && cachedValue.contains(address)) {
            return cachedValue;
        }

        final MappedMemoryRange[] ranges = sortedRanges;
        int low = 0, high = ranges.length - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final MappedMemoryRange range = ranges[mid];
            if (Long.compareUnsigned(address, range.start) < 0) {
                high = mid - 1;
            } else if (Long.compareUnsigned(address, range.end) > 0) {
                low = mid + 1;
            } else {
                cache = range;
                return range;
            }
        }

        return null;
    }

    @Override
    public void setDirty(final MemoryRange range, final int offset) {
        // todo implement tracking dirty bits; really need this if we want to add a frame buffer.
    }

    @Override
    public long load(final long address, final int sizeLog2) throws MemoryAccessException {
        final MappedMemoryRange range = getMemoryRange(address);
        if (range != null && (range.device.getSupportedSizes() & (1 << sizeLog2)) != 0) {
            return range.device.load((int) (address - range.start), sizeLog2);
        } else {
            throw new MemoryAccessException();
        }
    }

    @Override
    public void store(final long address, final long value, final int sizeLog2) throws MemoryAccessException {
        final MappedMemoryRange range = getMemoryRange(address);
        if (range != null && (range.device.getSupportedSizes() & (1 << sizeLog2)) != 0) {
            range.device.store((int) (address - range.start), value, sizeLog2);
        } else {
            throw new MemoryAccessException();
        }
    }

    private void rebuildSortedRanges() {
        final MappedMemoryRange[] ranges = devices.values().toArray(new MappedMemoryRange[0]);
        Arrays.sort(ranges, (a, b) -> Long.compareUnsigned(a.start, b.start));
        sortedRanges = ranges;
    }
}
