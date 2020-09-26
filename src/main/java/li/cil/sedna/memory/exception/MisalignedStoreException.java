package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class MisalignedStoreException extends MemoryAccessException {
    public MisalignedStoreException(final int address) {
        super(address);
    }
}
