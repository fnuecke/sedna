package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class StoreFaultException extends MemoryAccessException {
    public StoreFaultException(final int address) {
        super(address);
    }
}
