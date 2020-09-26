package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class FetchPageFaultException extends MemoryAccessException {
    public FetchPageFaultException(final int address) {
        super(address);
    }
}
