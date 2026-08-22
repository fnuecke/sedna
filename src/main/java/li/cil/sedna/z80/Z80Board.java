package li.cil.sedna.z80;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.memory.SimpleMemoryMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Serialized
public final class Z80Board implements Steppable, Resettable {
    private static final int ADDRESS_SPACE_SIZE = 0x10000;
    private static final int PORT_SPACE_SIZE = 0x100;

    private final transient MemoryMap memoryMap = new SimpleMemoryMap();
    private final transient MemoryMap portMap = new EightBitPortMap();
    private final transient List<MemoryMappedDevice> devices = new ArrayList<>();
    private final transient List<Resettable> resettableDevices = new ArrayList<>();
    private final transient List<Steppable> steppableDevices = new ArrayList<>();

    private final Z80CPU cpu;
    private boolean isRunning;

    public Z80Board() {
        cpu = Z80CPU.create(memoryMap, portMap);
        steppableDevices.add(cpu);
    }

    public Z80CPU getCpu() {
        return cpu;
    }

    public MemoryMap getMemoryMap() {
        return memoryMap;
    }

    public MemoryMap getPortMap() {
        return portMap;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(final boolean value) {
        isRunning = value;
    }

    /**
     * A HALT with both interrupt flip-flops cleared can, absent an NMI, never resume; it is the
     * closest thing the Z80 has to powering down.
     */
    public boolean isStopped() {
        final Z80CPUBase cpu = (Z80CPUBase) this.cpu;
        return cpu.isHalted() && !cpu.iff1 && !cpu.nmiRequested;
    }

    public boolean addDevice(final int address, final MemoryMappedDevice device) {
        return addDevice(memoryMap, address, device);
    }

    public boolean addPortDevice(final int port, final MemoryMappedDevice device) {
        if (port + device.getLength() > PORT_SPACE_SIZE) {
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
        cpu.reset();
        for (final Resettable device : resettableDevices) {
            device.reset();
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
