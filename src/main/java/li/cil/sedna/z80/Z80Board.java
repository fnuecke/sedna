package li.cil.sedna.z80;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Board;
import li.cil.sedna.api.DeviceBus;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.*;
import li.cil.sedna.device.DeviceWindow;
import li.cil.sedna.device.bus.DevicePortRegistry;
import li.cil.sedna.gdbstub.GDBStub;
import li.cil.sedna.memory.SimpleMemoryMap;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Serialized
public final class Z80Board implements Board {
    static final int ADDRESS_SPACE_SIZE = 0x10000;
    private static final int PORT_SPACE_SIZE = 0x100;
    private static final int INTERRUPT_COUNT = 32;

    /**
     * Port {@code FF} is never handed out: a device enumerator reports it as "not port-mapped", so
     * a device sitting there would be indistinguishable from an absent one.
     */
    static final int RESERVED_PORT = 0xFF;

    // ------------------------------------------------------------- //

    private final transient MemoryRangeAllocationStrategy allocationStrategy = new Z80MemoryRangeAllocationStrategy();
    private final transient MemoryRangeAllocationStrategy portAllocationStrategy = new Z80PortAllocationStrategy();

    private final transient DeviceBus deviceBus = new MemoryBus();
    private final transient DeviceBus portBus = new PortBus();
    private final transient ShadowingMemoryMap memoryMap = new ShadowingMemoryMap();
    private final transient MemoryMap portMap = new EightBitPortMap();
    private final transient List<MemoryMappedDevice> devices = new CopyOnWriteArrayList<>();
    private final transient List<Steppable> steppableDevices = new CopyOnWriteArrayList<>();
    private final transient List<Resettable> resettableDevices = new CopyOnWriteArrayList<>();
    private transient GDBStub gdbStub;

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

    public Z80CPU getCpu() {
        return cpu;
    }

    @Override
    public MemoryMap getMemoryMap() {
        return memoryMap;
    }

    @Override
    public DeviceBus getDeviceBus() {
        return deviceBus;
    }

    @Override
    public Z80InterruptController getInterruptController() {
        return interruptController;
    }

    @Override
    public int getInterruptCount() {
        return INTERRUPT_COUNT;
    }

    public MemoryRangeAllocationStrategy getAllocationStrategy() {
        return allocationStrategy;
    }

    public MemoryMap getPortMap() {
        return portMap;
    }

    public DeviceBus getPortBus() {
        return portBus;
    }

    public List<MemoryMappedDevice> getDevices() {
        return Collections.unmodifiableList(devices);
    }

    public boolean addDevice(final long address, final MemoryMappedDevice device) {
        return addDevice(memoryMap, address, device);
    }

    public OptionalLong addDevice(final MemoryMappedDevice device) {
        final OptionalLong address = allocationStrategy.findMemoryRange(device,
            MemoryRangeAllocationStrategy.getMemoryMapIntersectionProvider(memoryMap));
        if (address.isEmpty() || !addDevice(address.getAsLong(), device)) {
            return OptionalLong.empty();
        }
        return address;
    }

    public boolean addPortDevice(final int port, final MemoryMappedDevice device) {
        if (port + portWidth(device) > RESERVED_PORT || devices.contains(mappedForm(device))) {
            return false;
        }
        return addDevice(portMap, port, window(device));
    }

    public OptionalInt addPortDevice(final MemoryMappedDevice device) {
        final OptionalLong port = portAllocationStrategy.findMemoryRange(device,
            MemoryRangeAllocationStrategy.getMemoryMapIntersectionProvider(portMap));
        if (port.isEmpty() || !addPortDevice((int) port.getAsLong(), device)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) port.getAsLong());
    }

    public void removeDevice(final MemoryMappedDevice device) {
        final MemoryMappedDevice mapped = mappedForm(device);
        memoryMap.removeDevice(mapped);
        portMap.removeDevice(mapped);
        devices.remove(mapped);
        if (mapped instanceof final Resettable resettable) {
            resettableDevices.remove(resettable);
        }
        if (mapped instanceof final Steppable steppable) {
            steppableDevices.remove(steppable);
        }
        cpu.invalidateCaches();
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

    public void enableGDB(final int port, final boolean waitForGdb) {
        GDBStub gdbStub;
        try {
            gdbStub = GDBStub.createDefault(cpu.getDebugInterface(), port);
            if (waitForGdb) {
                gdbStub.waitForAttach();
            }
        } catch (final IOException e) {
            e.printStackTrace();
            gdbStub = null;
        }
        this.gdbStub = gdbStub;
    }

    @Override
    public void step(final int cycles) {
        if (!isRunning) {
            return;
        }

        if (gdbStub != null) {
            gdbStub.poll();
            if (gdbStub.isHalted()) {
                return;
            }
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

    private static MemoryMappedDevice window(final MemoryMappedDevice device) {
        final int width = portWidth(device);
        return width == device.getLength() ? device : new DeviceWindow(device, width);
    }

    private static int portWidth(final MemoryMappedDevice device) {
        return DevicePortRegistry.getWidth(device).orElseGet(device::getLength);
    }

    private boolean addDevice(final MemoryMap map, final long address, final MemoryMappedDevice device) {
        final int length = Z80MemoryRangeAllocationStrategy.visibleLength(device);
        if (length == 0 || address < 0 || address + length > ADDRESS_SPACE_SIZE) {
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

    private MemoryMappedDevice mappedForm(final MemoryMappedDevice device) {
        for (final MemoryMappedDevice candidate : devices) {
            if (candidate instanceof final DeviceWindow window && window.getDevice() == device) {
                return window;
            }
        }
        return device;
    }

    private final class MemoryBus implements DeviceBus {
        @Override
        public MemoryMap getMemoryMap() {
            return Z80Board.this.getMemoryMap();
        }

        @Override
        public MemoryRangeAllocationStrategy getAllocationStrategy() {
            return Z80Board.this.getAllocationStrategy();
        }

        @Override
        public boolean addDevice(final long address, final MemoryMappedDevice device) {
            return Z80Board.this.addDevice(address, device);
        }

        @Override
        public OptionalLong addDevice(final MemoryMappedDevice device) {
            return Z80Board.this.addDevice(device);
        }

        @Override
        public void removeDevice(final MemoryMappedDevice device) {
            Z80Board.this.removeDevice(device);
        }
    }

    private final class PortBus implements DeviceBus {
        @Override
        public MemoryMap getMemoryMap() {
            return portMap;
        }

        @Override
        public MemoryRangeAllocationStrategy getAllocationStrategy() {
            return portAllocationStrategy;
        }

        @Override
        public boolean addDevice(final long address, final MemoryMappedDevice device) {
            return addPortDevice((int) address, device);
        }

        @Override
        public OptionalLong addDevice(final MemoryMappedDevice device) {
            final OptionalInt port = addPortDevice(device);
            return port.isPresent() ? OptionalLong.of(port.getAsInt()) : OptionalLong.empty();
        }

        @Override
        public void removeDevice(final MemoryMappedDevice device) {
            Z80Board.this.removeDevice(device);
        }
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

        @Nullable
        @Override
        public MappedMemoryRange getMemoryRange(final long address) {
            final MappedMemoryRange overlay = this.overlay;
            return isBootRomMapped && overlay != null ? overlay : map.getMemoryRange(address);
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

    private final class EightBitPortMap implements MemoryMap {
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
            return map.getMemoryRange(mappedForm(device));
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
        public long load(final long address, final int sizeLog2) throws MemoryAccessException {
            return map.load(address & 0xFF, sizeLog2);
        }

        @Override
        public void store(final long address, final long value, final int sizeLog2) throws MemoryAccessException {
            map.store(address & 0xFF, value, sizeLog2);
        }
    }
}
