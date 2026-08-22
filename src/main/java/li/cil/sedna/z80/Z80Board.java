package li.cil.sedna.z80;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.memory.SimpleMemoryMap;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Serialized
public final class Z80Board implements Steppable, Resettable {
    private static final int ADDRESS_SPACE_SIZE = 0x10000;
    private static final int PORT_SPACE_SIZE = 0x100;

    /**
     * Port {@code FF} is never handed out: a device enumerator reports it as "not port-mapped", so
     * a device sitting there would be indistinguishable from an absent one.
     */
    private static final int RESERVED_PORT = 0xFF;

    // ------------------------------------------------------------- //

    private final transient ShadowingMemoryMap memoryMap = new ShadowingMemoryMap();
    private final transient MemoryMap portMap = new EightBitPortMap();
    private final transient List<MemoryMappedDevice> devices = new CopyOnWriteArrayList<>();
    private final transient List<Resettable> resettableDevices = new CopyOnWriteArrayList<>();
    private final transient List<Steppable> steppableDevices = new CopyOnWriteArrayList<>();

    private final Z80CPU cpu;
    private final Z80InterruptController interruptController;
    private boolean isRunning;
    private boolean isBootRomMapped;

    // ------------------------------------------------------------- //

    public Z80Board() {
        cpu = Z80CPU.create(memoryMap, portMap);
        interruptController = new Z80InterruptController(cpu);
        steppableDevices.add(cpu);
    }

    // ------------------------------------------------------------- //

    public Z80CPU getCpu() {
        return cpu;
    }

    public MemoryMap getMemoryMap() {
        return memoryMap;
    }

    public MemoryMap getPortMap() {
        return portMap;
    }

    public Z80InterruptController getInterruptController() {
        return interruptController;
    }

    public List<MemoryMappedDevice> getDevices() {
        return Collections.unmodifiableList(devices);
    }

    public void setBootRom(@Nullable final MemoryMappedDevice rom) {
        if (rom != null && (rom.getLength() <= 0 || rom.getLength() > ADDRESS_SPACE_SIZE)) {
            throw new IllegalArgumentException("Boot ROM does not fit the address space.");
        }

        memoryMap.setRom(rom);
        isBootRomMapped = rom != null;
        cpu.invalidateCaches();
    }

    public boolean isBootRomMapped() {
        return isBootRomMapped;
    }

    public void setBootRomMapped(final boolean value) {
        if (isBootRomMapped == value) {
            return;
        }

        isBootRomMapped = value;
        cpu.invalidateCaches();
    }

    public boolean addDevice(final int address, final MemoryMappedDevice device) {
        return addDevice(memoryMap, address, device);
    }

    public boolean addPortDevice(final int port, final MemoryMappedDevice device) {
        if (port + device.getLength() > RESERVED_PORT) {
            return false;
        }
        return addDevice(portMap, port, device);
    }

    public void removeDevice(final MemoryMappedDevice device) {
        memoryMap.removeDevice(device);
        portMap.removeDevice(device);
        devices.remove(device);
        if (device instanceof final Resettable resettable) {
            resettableDevices.remove(resettable);
        }
        if (device instanceof final Steppable steppable) {
            steppableDevices.remove(steppable);
        }
        cpu.invalidateCaches();
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(final boolean value) {
        isRunning = value;
    }

    public boolean isHalted() {
        final Z80CPUBase cpu = (Z80CPUBase) this.cpu;
        return cpu.isHalted() && !cpu.iff1 && !cpu.nmiRequested;
    }

    @Override
    public void step(final int cycles) {
        if (!isRunning) {
            return;
        }

        for (final Steppable device : steppableDevices) {
            device.step(cycles);
        }
    }

    @Override
    public void reset() {
        isBootRomMapped = memoryMap.hasRom();
        cpu.invalidateCaches();
        interruptController.reset();
        cpu.reset();
        for (final Resettable device : resettableDevices) {
            device.reset();
        }
    }

    // ------------------------------------------------------------- //

    private boolean addDevice(final MemoryMap map, final int address, final MemoryMappedDevice device) {
        if (device.getLength() == 0 || address < 0 || address + device.getLength() > ADDRESS_SPACE_SIZE) {
            return false;
        }

        if (devices.contains(device)) {
            return false;
        }

        if (!map.addDevice(address, device)) {
            return false;
        }

        devices.add(device);
        if (device instanceof final Resettable resettable) {
            resettableDevices.add(resettable);
        }
        if (device instanceof final Steppable steppable) {
            steppableDevices.add(steppable);
        }

        cpu.invalidateCaches();

        return true;
    }

    private final class ShadowingMemoryMap implements MemoryMap {
        private final SimpleMemoryMap map = new SimpleMemoryMap();
        @Nullable
        private MappedMemoryRange rom;
        @Nullable
        private MappedMemoryRange overlay;

        void setRom(@Nullable final MemoryMappedDevice rom) {
            this.rom = rom != null ? new MappedMemoryRange(rom, 0) : null;
            this.overlay = rom != null ? new MappedMemoryRange(new Overlay(), 0) : null;
        }

        boolean hasRom() {
            return rom != null;
        }

        @Override
        public boolean addDevice(final long address, final MemoryMappedDevice device) {
            return map.addDevice(address, device);
        }

        @Override
        public void removeDevice(final MemoryMappedDevice device) {
            map.removeDevice(device);
        }

        @Override
        public Optional<MappedMemoryRange> getMemoryRange(final MemoryMappedDevice device) {
            final MappedMemoryRange range = rom;
            if (range != null && range.device == device) {
                return Optional.of(range);
            }
            return map.getMemoryRange(device);
        }

        @Override
        public Optional<MappedMemoryRange> getMemoryRange(final MemoryRange range) {
            return map.getMemoryRange(range);
        }

        /**
         * While the ROM is mapped, every address resolves to a single range covering the whole
         * address space. The CPU caches one range for loads and stores alike, so handing out the
         * range of a device that spans the shadowed region (64 KiB of RAM, typically) would
         * let a later access below the ROM hit that cached range and see straight through the
         * shadow. Steady state is unaffected: once the latch is cleared, ranges are the plain
         * device ranges again.
         */
        @Nullable
        @Override
        public MappedMemoryRange getMemoryRange(final long address) {
            final MappedMemoryRange overlay = this.overlay;
            return isBootRomMapped && overlay != null ? overlay : map.getMemoryRange(address);
        }

        @Override
        public void setDirty(final MemoryRange range, final int offset) {
            map.setDirty(range, offset);
        }

        @Override
        public long load(final long address, final int sizeLog2) throws MemoryAccessException {
            final MappedMemoryRange shadowed = shadowing(address);
            if (shadowed == null) {
                return map.load(address, sizeLog2);
            }
            if ((shadowed.device.getSupportedSizes() & (1 << sizeLog2)) == 0) {
                throw new MemoryAccessException();
            }
            return shadowed.device.load((int) (address - shadowed.start), sizeLog2);
        }

        @Override
        public void store(final long address, final long value, final int sizeLog2) throws MemoryAccessException {
            final MappedMemoryRange shadowed = shadowing(address);
            if (shadowed == null) {
                map.store(address, value, sizeLog2);
                return;
            }
            if ((shadowed.device.getSupportedSizes() & (1 << sizeLog2)) == 0) {
                throw new MemoryAccessException();
            }
            shadowed.device.store((int) (address - shadowed.start), value, sizeLog2);
        }

        @Nullable
        private MappedMemoryRange shadowing(final long address) {
            final MappedMemoryRange range = rom;
            return isBootRomMapped && range != null && range.contains(address) ? range : null;
        }

        private final class Overlay implements MemoryMappedDevice {
            @Override
            public int getLength() {
                return ADDRESS_SPACE_SIZE;
            }

            @Override
            public int getSupportedSizes() {
                return 1 << Sizes.SIZE_8_LOG2;
            }

            @Override
            public boolean supportsFetch() {
                return true;
            }

            @Override
            public long load(final int offset, final int sizeLog2) throws MemoryAccessException {
                return ShadowingMemoryMap.this.load(offset, sizeLog2);
            }

            @Override
            public void store(final int offset, final long value, final int sizeLog2) throws MemoryAccessException {
                ShadowingMemoryMap.this.store(offset, value, sizeLog2);
            }
        }
    }

    /**
     * Decodes only the low 8 bits of the port address on lookups; devices are mapped at their
     * 8-bit port number.
     */
    private static final class EightBitPortMap implements MemoryMap {
        private final SimpleMemoryMap map = new SimpleMemoryMap();

        @Override
        public boolean addDevice(final long address, final MemoryMappedDevice device) {
            return map.addDevice(address, device);
        }

        @Override
        public void removeDevice(final MemoryMappedDevice device) {
            map.removeDevice(device);
        }

        @Override
        public Optional<MappedMemoryRange> getMemoryRange(final MemoryMappedDevice device) {
            return map.getMemoryRange(device);
        }

        @Override
        public Optional<MappedMemoryRange> getMemoryRange(final MemoryRange range) {
            return map.getMemoryRange(range);
        }

        @Override
        public MappedMemoryRange getMemoryRange(final long address) {
            return map.getMemoryRange(address & 0xFF);
        }

        @Override
        public void setDirty(final MemoryRange range, final int offset) {
            map.setDirty(range, offset);
        }

        @Override
        public long load(final long address, final int sizeLog2) throws MemoryAccessException {
            return map.load(address & 0xFF, sizeLog2);
        }

        @Override
        public void store(final long address, final long value, final int sizeLog2) throws MemoryAccessException {
            map.store(address & 0xFF, value, sizeLog2);
        }
    }
}
