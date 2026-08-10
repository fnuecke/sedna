package li.cil.sedna.device.virtio;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.memory.MemoryAccessException;

/**
 * Abstract representation of a Virtqueue.
 * <p>
 * Actual implementations are {@link SplitVirtqueue}s and <em>Packed Virtqueues</em>.
 */
@Serialized
abstract class AbstractVirtqueue implements VirtqueueIterator {
    static final int VIRTQ_MAX_QUEUE_SIZE = 256; // Size of descriptor rings.

    int ready;
    int num = VIRTQ_MAX_QUEUE_SIZE; // Guaranteed to be a power of two.
    long desc; // Descriptor Area - used for describing buffers.
    long driver; // Driver Area - extra data supplied by driver to the device.
    long device; // Device Area - extra data supplied by device to driver.

    boolean dispatchQueueNotifications = true;

    void reset() {
        ready = 0; // 4.2.2.1: Must set to zero on reset.
        num = VIRTQ_MAX_QUEUE_SIZE;
        desc = 0;
        driver = 0;
        device = 0;
    }

    abstract void handleQueueNotification(final int queueIndex) throws VirtIODeviceException, MemoryAccessException;
}
