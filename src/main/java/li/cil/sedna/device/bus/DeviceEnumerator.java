package li.cil.sedna.device.bus;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.bus.DeviceClass;
import li.cil.sedna.api.device.bus.DeviceDescription;
import li.cil.sedna.api.device.bus.InterruptVectorMap;
import li.cil.sedna.api.memory.MemoryMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Device discovery mechanism when a {@link li.cil.sedna.api.devicetree.DeviceTree} isn't an option.
 * <p>
 * Primary use-case are low-level architectures, such as Z80, where we have a very limited device
 * bus on the board. Discovery then works by enumerating devices through this well-known device.
 * <p>
 * The layout is guest-facing. {@link #REG_VERSION} exists for potential future extensions.
 */
@Serialized
public final class DeviceEnumerator implements MemoryMappedDevice {
    public static final int LENGTH = 16;
    public static final int VERSION = 1;

    private static final int REG_VERSION = 0;
    private static final int REG_COUNT = 1;
    private static final int REG_SELECT = 2;
    private static final int REG_CLASS = 3;
    private static final int REG_ATTRIBUTES = 4;
    private static final int REG_PORT = 5;
    private static final int REG_INTERRUPT = 6;
    private static final int REG_NAME = 7;
    private static final int REG_ID = 8;
    // 9..15 are reserved and read zero, which unmapped ports (reading FF) cannot be mistaken for.

    private static final int NONE = 0xFF;
    private static final int MAX_DEVICES = 0xFE;

    private final transient MemoryMap portMap;
    private final transient Collection<MemoryMappedDevice> devices;
    private final transient InterruptVectorMap vectors;

    private int selected;
    private int nameIndex;
    private int idIndex;

    public DeviceEnumerator(final MemoryMap portMap, final Collection<MemoryMappedDevice> devices,
                            final InterruptVectorMap vectors) {
        this.portMap = portMap;
        this.devices = devices;
        this.vectors = vectors;
    }

    @Override
    public int getLength() {
        return LENGTH;
    }

    @Override
    public int getSupportedSizes() {
        return 1 << Sizes.SIZE_8_LOG2;
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        return switch (offset) {
            case REG_VERSION -> VERSION;
            case REG_SELECT -> selected;
            case REG_COUNT -> Math.min(entries().size(), MAX_DEVICES);
            case REG_CLASS -> describe().map(d -> d.deviceClass().value()).orElse(DeviceClass.UNKNOWN.value());
            case REG_ATTRIBUTES -> describe().map(DeviceDescription::attributes).orElse(0);
            case REG_PORT -> port();
            case REG_INTERRUPT -> interrupt();
            case REG_NAME -> {
                final String name = describe().map(DeviceDescription::name).orElse("");
                yield nameIndex < name.length() ? name.charAt(nameIndex++) & 0xFF : 0;
            }
            case REG_ID -> {
                final String id = describe().map(DeviceDescription::id).orElse("");
                yield idIndex < id.length() ? id.charAt(idIndex++) & 0xFF : 0;
            }
            default -> 0;
        };
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        switch (offset) {
            case REG_SELECT -> {
                selected = (int) (value & 0xFF);
                nameIndex = 0;
                idIndex = 0;
            }
            case REG_NAME -> nameIndex = 0;
            case REG_ID -> idIndex = 0;
            default -> {
            }
        }
    }

    private record Entry(MemoryMappedDevice device, DeviceDescription description) {
    }

    private List<Entry> entries() {
        final List<Entry> entries = new ArrayList<>();
        for (final DeviceDescription description : DeviceDescriptionRegistry.getDescriptions(this)) {
            entries.add(new Entry(this, description));
        }
        for (final MemoryMappedDevice device : devices) {
            if (device == this || portMap.getMemoryRange(device).isEmpty()) {
                continue;
            }
            for (final DeviceDescription description : DeviceDescriptionRegistry.getDescriptions(device)) {
                entries.add(new Entry(device, description));
            }
        }
        return entries;
    }

    @Nullable
    private Entry selectedEntry() {
        final List<Entry> entries = entries();
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    private Optional<DeviceDescription> describe() {
        final Entry entry = selectedEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.description());
    }

    private int interrupt() {
        final Entry entry = selectedEntry();
        if (entry == null || !(entry.device() instanceof final InterruptSource source)) {
            return InterruptVectorMap.NO_VECTOR;
        }
        for (final Interrupt interrupt : source.getInterrupts()) {
            if (interrupt.controller != null) {
                return vectors.getVector(interrupt.id);
            }
        }
        return InterruptVectorMap.NO_VECTOR;
    }

    private int port() {
        final Entry entry = selectedEntry();
        if (entry == null) {
            return NONE;
        }
        return portMap.getMemoryRange(entry.device())
            .map(range -> (int) (range.start & 0xFF))
            .orElse(NONE);
    }
}
