package li.cil.sedna.api.memory;

import li.cil.sedna.api.device.MemoryMappedDevice;

import java.io.IOException;

/**
 * Base class for all memory related exceptions.
 * <p>
 * This exception may be thrown whenever memory mapped in a {@link MemoryMap}
 * is accessed, specifically any {@link MemoryMappedDevice} may throw these exceptions to signal an
 * invalid access.
 */
public final class MemoryAccessException extends IOException {
    public enum Type {
        FETCH_FAULT,
        LOAD_FAULT,
        STORE_FAULT,
        FETCH_PAGE_FAULT,
        LOAD_PAGE_FAULT,
        STORE_PAGE_FAULT,
        MISALIGNED_FETCH,
        MISALIGNED_LOAD,
        MISALIGNED_STORE,
    }

    private final long address;
    private final Type type;

    public MemoryAccessException(final long address, final Type type) {
        this.address = address;
        this.type = type;
    }

    public long getAddress() {
        return address;
    }

    public Type getType() {
        return type;
    }
}
