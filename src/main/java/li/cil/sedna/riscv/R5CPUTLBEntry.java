package li.cil.sedna.riscv;

import li.cil.sedna.api.device.MemoryMappedDevice;

final class R5CPUTLBEntry {
    public int hash = -1;
    public int toOffset;
    public MemoryMappedDevice device;
}
