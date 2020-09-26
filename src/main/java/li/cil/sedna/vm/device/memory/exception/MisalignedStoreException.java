package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class MisalignedStoreException extends MemoryAccessException {
    public MisalignedStoreException(final int address) {
        super(address);
    }
}
