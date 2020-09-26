package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class StorePageFaultException extends MemoryAccessException {
    public StorePageFaultException(final int address) {
        super(address);
    }
}
