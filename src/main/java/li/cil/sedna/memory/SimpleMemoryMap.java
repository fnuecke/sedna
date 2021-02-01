package li.cil.sedna.memory;

import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class SimpleMemoryMap implements MemoryMap {
    private final Map<MemoryMappedDevice, MappedMemoryRange> devices = new HashMap<>();

    // For device IO we often get sequential access to the same range/device, so we remember the last one as a cache.
    private MappedMemoryRange cache;

    @Override
    public boolean addDevice(final long address, final MemoryMappedDevice device) {
        if (devices.containsKey(device)) {
            return false;
        }

        final MappedMemoryRange deviceRange = new MappedMemoryRange(device, address);
        if (devices.values().stream().anyMatch(range -> range.intersects(deviceRange))) {
            return false;
        }

        devices.put(device, deviceRange);
        return true;
    }

    @Override
    public void removeDevice(final MemoryMappedDevice device) {
        devices.remove(device);
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
        for (final MappedMemoryRange existingRange : devices.values()) {
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

        for (final MappedMemoryRange range : devices.values()) {
            if (range.contains(address)) {
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
        }
        return 0;
    }

    @Override
    public void store(final long address, final long value, final int sizeLog2) throws MemoryAccessException {
        final MappedMemoryRange range = getMemoryRange(address);
        if (range != null && (range.device.getSupportedSizes() & (1 << sizeLog2)) != 0) {
            range.device.store((int) (address - range.start), value, sizeLog2);
        }
    }
}
