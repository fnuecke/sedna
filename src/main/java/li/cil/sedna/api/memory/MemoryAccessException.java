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
    @Override
    public synchronized Throwable fillInStackTrace() {
        // Keep it cheap, happens frequently when guest is testing the port bus on z80.
        return this;
    }
}
