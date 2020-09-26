package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class MisalignedFetchException extends MemoryAccessException {
    public MisalignedFetchException(final int address) {
        super(address);
    }
}
