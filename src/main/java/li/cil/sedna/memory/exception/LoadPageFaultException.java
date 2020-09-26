package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class LoadPageFaultException extends MemoryAccessException {
    public LoadPageFaultException(final int address) {
        super(address);
    }
}
