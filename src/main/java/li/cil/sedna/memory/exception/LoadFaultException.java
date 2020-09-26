package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class LoadFaultException extends MemoryAccessException {
    public LoadFaultException(final int address) {
        super(address);
    }
}
