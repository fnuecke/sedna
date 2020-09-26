package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class MisalignedFetchException extends MemoryAccessException {
    public MisalignedFetchException(final int address) {
        super(address);
    }
}
