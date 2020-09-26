package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class LoadFaultException extends MemoryAccessException {
    public LoadFaultException(final int address) {
        super(address);
    }
}
