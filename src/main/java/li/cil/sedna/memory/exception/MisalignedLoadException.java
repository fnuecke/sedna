package li.cil.sedna.memory.exception;

import li.cil.sedna.api.memory.MemoryAccessException;

public final class MisalignedLoadException extends MemoryAccessException {
    public MisalignedLoadException(final int address) {
        super(address);
    }
}
