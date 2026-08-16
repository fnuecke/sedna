package li.cil.sedna.device.virtio;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.fs.FileSystem;
import li.cil.sedna.p9.FileSystemFileMap;
import li.cil.sedna.p9.P9Server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Plan 9 file protocol device.
 * <p>
 * Can be used to provide direct access to a {@link FileSystem} implementation, which could be a directory
 * in the host operating system or a ZIP file, for example.
 * <p>
 * This is only the virtio transport; the protocol itself lives in {@link P9Server}.
 */
public final class VirtIOFileSystemDevice extends AbstractVirtIODevice implements Steppable {
    private static final int BYTES_PER_THOUSAND_CYCLES = 32;

    private static final long VIRTIO_9P_F_MOUNT_TAG = 1L << 0; // We have a tag name that can be used to mount us.

    private static final int VIRTQ_REQUEST = 0;

    private final String tag;
    private final transient P9Server server;
    private int remainingByteProcessingQuota;

    // The fid table stays a field of the device: it is what a savestate contains, and moving it into
    // the server would nest it one level deeper in the ceres output, changing the format.
    @Serialized
    private final FileSystemFileMap files = new FileSystemFileMap();
    @Serialized
    private boolean hasPendingRequest;

    public VirtIOFileSystemDevice(final MemoryMap memoryMap, final String tag, final FileSystem fileSystem) {
        this(memoryMap, tag, fileSystem, VirtIODeviceSpec.DEFAULT_QUEUE_SIZE_MAX);
    }

    public VirtIOFileSystemDevice(final MemoryMap memoryMap, final String tag, final FileSystem fileSystem, final int queueSizeMax) {
        super(memoryMap, VirtIODeviceSpec
                .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_9P_TRANSPORT)
                .features(VIRTIO_9P_F_MOUNT_TAG)
                .queueCount(1)
                .queueSizeMax(queueSizeMax)
                .configSpaceSize(2 + Math.min(tag.length(), 0xFFFF))
                .build());
        this.tag = tag;
        this.server = new P9Server(fileSystem, files);
    }

    @Override
    public void reset() {
        super.reset();

        server.reset();
    }

    @Override
    public void step(final int cycles) {
        if (remainingByteProcessingQuota <= 0) {
            remainingByteProcessingQuota += Math.max(1, cycles * BYTES_PER_THOUSAND_CYCLES / 1000);
        }

        if (!hasPendingRequest) {
            return;
        }

        if ((getStatus() & VIRTIO_STATUS_FAILED) != 0) {
            return;
        }

        try {
            while (remainingByteProcessingQuota > 0) {
                final int processedBytes = processRequest();
                if (processedBytes < 0) {
                    break;
                }
                remainingByteProcessingQuota -= processedBytes;
            }
        } catch (final Throwable e) {
            error();
        }
    }

    @Override
    protected void initializeConfig() {
        super.initializeConfig();
        final ByteBuffer configuration = getConfiguration();
        configuration.clear();
        configuration.putShort((short) tag.length());
        configuration.put(tag.getBytes(StandardCharsets.US_ASCII));
        configuration.flip();
    }

    @Override
    protected void handleQueueNotification(final int queueIndex) {
        hasPendingRequest = true;
    }

    private int processRequest() throws VirtIODeviceException, IOException {
        final VirtqueueIterator queue = getQueueIterator(VIRTQ_REQUEST);
        if (queue == null) {
            hasPendingRequest = false;
            return -1;
        }

        if (!queue.hasNext()) {
            hasPendingRequest = false;
            return -1;
        }
        final DescriptorChain chain = queue.next();

        final int processedBytes = chain.readableBytes() + chain.writableBytes();

        final ByteBuffer request = ByteBuffer.allocate(chain.readableBytes()).order(ByteOrder.LITTLE_ENDIAN);
        chain.get(request);
        request.flip();

        final ByteBuffer reply = server.handleRequest(request);

        chain.skip(chain.readableBytes());
        chain.put(reply);

        chain.use();

        return processedBytes;
    }
}
