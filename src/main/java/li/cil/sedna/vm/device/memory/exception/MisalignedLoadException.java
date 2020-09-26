package li.cil.sedna.vm.device.memory.exception;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;

public final class MisalignedLoadException extends MemoryAccessException {
    public MisalignedLoadException(final int address) {
        super(address);
    }
}
