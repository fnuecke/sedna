package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class StoreFaultException extends MemoryAccessException {
    public StoreFaultException(final int address) {
        super(address);
    }
}
