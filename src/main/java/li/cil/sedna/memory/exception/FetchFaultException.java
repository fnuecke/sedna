package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class FetchFaultException extends MemoryAccessException {
    public FetchFaultException(final int address) {
        super(address);
    }
}
