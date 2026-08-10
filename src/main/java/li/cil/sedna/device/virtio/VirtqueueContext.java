package li.cil.sedna.device.virtio;

import li.cil.sedna.api.memory.MemoryAccessException;

/**
 * The parts of a VirtIO device a virtqueue needs to do its job.
 */
interface VirtqueueContext {
    /**
     * Signals that the device has entered an error state and needs a reset.
     * <p>
     * Called when a driver hands us something we refuse to follow, such as a descriptor chain that
     * looks like a loop or one that puts read-only descriptors after write-only ones.
     */
    void error();

    /**
     * Gets the feature set negotiated with the driver.
     * <p>
     * Virtqueues need this to decide how used buffer notifications are suppressed.
     */
    long getNegotiatedFeatures();

    /**
     * Signals that a buffer was marked as used and the driver should be notified.
     */
    void raiseUsedBufferInterrupt();

    /**
     * Forwards a queue notification to the device implementation.
     *
     * @param queueIndex the index of the queue descriptors became available in.
     */
    void dispatchQueueNotification(final int queueIndex) throws VirtIODeviceException, MemoryAccessException;
}
