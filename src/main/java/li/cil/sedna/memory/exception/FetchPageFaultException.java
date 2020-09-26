package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class FetchPageFaultException extends MemoryAccessException {
    public FetchPageFaultException(final int address) {
        super(address);
    }
}
