package li.cil.sedna.device.virtio;

/**
 * Device specifications for a VirtIO device.
 * <p>
 * Create instances of this type using a builder obtained by calling {@link #builder(int)}.
 */
public final class VirtIODeviceSpec {
    private static final int MAX_CONFIG_SPACE_SIZE = 256;
    static final int MAX_VIRTQUEUE_COUNT = 16;

    public final int deviceId;
    public final int vendorId;
    public final long features;
    public final int configSpaceSizeInBytes;
    public final int virtQueueCount;
    public final int virtQueueSizeMax;

    VirtIODeviceSpec(final int deviceId,
                     final int vendorId,
                     final long features,
                     final int configSpaceSizeInBytes,
                     final int virtQueueCount,
                     final int virtQueueSizeMax) {
        if (configSpaceSizeInBytes < 0 || configSpaceSizeInBytes > MAX_CONFIG_SPACE_SIZE) {
            throw new IndexOutOfBoundsException();
        }
        if (virtQueueCount < 0 || virtQueueCount > MAX_VIRTQUEUE_COUNT) {
            throw new IndexOutOfBoundsException();
        }

        this.deviceId = deviceId;
        this.vendorId = vendorId;
        this.features = features | AbstractVirtIODevice.VIRTIO_F_VERSION_1;
        if (virtQueueSizeMax < 1 || virtQueueSizeMax > AbstractVirtqueue.VIRTQ_MAX_QUEUE_SIZE
                || Integer.bitCount(virtQueueSizeMax) != 1) {
            throw new IllegalArgumentException("Queue size must be a power of two in [1, "
                    + AbstractVirtqueue.VIRTQ_MAX_QUEUE_SIZE + "].");
        }

        this.configSpaceSizeInBytes = configSpaceSizeInBytes;
        this.virtQueueCount = virtQueueCount;
        this.virtQueueSizeMax = virtQueueSizeMax;
    }

    /**
     * Creates a new spec build for setting up specs using method chaining.
     *
     * @param deviceId the device id of the VirtIO device this spec is for.
     * @return a new spec builder.
     */
    public static Builder builder(final int deviceId) {
        return new Builder(deviceId);
    }

    /**
     * Builder for {@link VirtIODeviceSpec} instances using method chaining.
     */
    public static final class Builder {
        private final int deviceId;
        private int vendorId = AbstractVirtIODevice.VIRTIO_VENDOR_ID_GENERIC;
        private long features;
        private int configSpaceSizeInBytes;
        private int virtQueueCount;
        private int virtQueueSizeMax = AbstractVirtqueue.VIRTQ_MAX_QUEUE_SIZE;

        /**
         * Configures the vendor id for devices with this device spec.
         * <p>
         * This defaults to the generic/experimental vendor id.
         *
         * @param value the vendor id to use.
         * @return this builder for method chaining.
         */
        public Builder vendorId(final int value) {
            this.vendorId = value;
            return this;
        }

        /**
         * Configures the supported feature set for devices with this device spec.
         *
         * @param value the bitmask defining the supported features.
         * @return this builder for method chaining.
         */
        public Builder features(final long value) {
            this.features = value;
            return this;
        }

        /**
         * Configures the size of the config space for devices with this device spec.
         *
         * @param sizeInBytes the size of the config space in bytes.
         * @return this builder for method chaining.
         */
        public Builder configSpaceSize(final int sizeInBytes) {
            if (sizeInBytes < 0 || sizeInBytes > MAX_CONFIG_SPACE_SIZE) {
                throw new IndexOutOfBoundsException();
            }
            this.configSpaceSizeInBytes = sizeInBytes;
            return this;
        }

        /**
         * Configures the number for {@link VirtqueueIterator}s
         * devices with this spec use.
         *
         * @param value the number of queues.
         * @return this builder for method chaining.
         */
        public Builder queueCount(final int value) {
            if (value < 0 || value > MAX_VIRTQUEUE_COUNT) {
                throw new IndexOutOfBoundsException();
            }
            virtQueueCount = value;
            return this;
        }

        /**
         * Configures the largest descriptor ring devices with this spec offer the driver.
         * <p>
         * Linux drivers generally take whatever maximum is offered and then fill the
         * receive ring with page-sized buffers, so this effectively controls how much
         * <em>guest</em> memory the device costs: a 256-entry ring is a megabyte per
         * receive queue.
         *
         * @param size the maximum ring size; a power of two, at most
         *             {@link AbstractVirtqueue#VIRTQ_MAX_QUEUE_SIZE}.
         * @return this builder for method chaining.
         */
        public Builder queueSizeMax(final int size) {
            if (size < 1 || size > AbstractVirtqueue.VIRTQ_MAX_QUEUE_SIZE || Integer.bitCount(size) != 1) {
                throw new IllegalArgumentException();
            }
            this.virtQueueSizeMax = size;
            return this;
        }

        /**
         * Finishes construction of a {@link VirtIODeviceSpec} and returns it.
         *
         * @return the spec configured using this builder.
         */
        public VirtIODeviceSpec build() {
            return new VirtIODeviceSpec(deviceId, vendorId, features, configSpaceSizeInBytes, virtQueueCount, virtQueueSizeMax);
        }

        private Builder(final int deviceId) {
            this.deviceId = deviceId;
        }
    }
}
