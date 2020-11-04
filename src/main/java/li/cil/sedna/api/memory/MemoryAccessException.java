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
}
