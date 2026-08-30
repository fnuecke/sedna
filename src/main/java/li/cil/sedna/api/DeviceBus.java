package li.cil.sedna.api;

import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRangeAllocationStrategy;

import java.util.OptionalLong;

/**
 * An address space devices can be mapped into.
 * <p>
 * For architectures with memory mapped IO such as our RISC-V board, this maps into the
 * general memory map. For architectures with a dedicated device bus such as Z80 with
 * it's port bus, this is that separate bus.
 */
public interface DeviceBus {
    MemoryMap getMemoryMap();

    MemoryRangeAllocationStrategy getAllocationStrategy();

    boolean addDevice(long address, MemoryMappedDevice device);

    OptionalLong addDevice(MemoryMappedDevice device);

    void removeDevice(MemoryMappedDevice device);
}
