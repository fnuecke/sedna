package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class FetchFaultException extends MemoryAccessException {
    public FetchFaultException(final int address) {
        super(address);
    }
}
