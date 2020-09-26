package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class LoadPageFaultException extends MemoryAccessException {
    public LoadPageFaultException(final int address) {
        super(address);
    }
}
