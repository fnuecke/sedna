package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class StorePageFaultException extends MemoryAccessException {
    public StorePageFaultException(final int address) {
        super(address);
    }
}
