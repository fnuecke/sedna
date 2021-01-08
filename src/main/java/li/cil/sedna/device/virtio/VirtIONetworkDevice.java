package li.cil.sedna.device.virtio;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;

import javax.annotation.Nullable;

public final class VirtIONetworkDevice extends AbstractVirtIODevice {
    // struct virtio_net_hdr {
    private static final int HEADER_SIZE =
            1 + // u8 flags;
            1 + // u8 gso_type;
            2 + // le16 hdr_len;
            2 + // le16 gso_size;
            2 + // le16 csum_start;
            2 + // le16 csum_offset;
            2;  // le16 num_buffers;
    // };

    private static final int VIRTQ_RECEIVE = 0; // receiveq1
    private static final int VIRTQ_TRANSMIT = 1; // transmitq1

    public VirtIONetworkDevice(final MemoryMap memoryMap) {
        super(memoryMap, VirtIODeviceSpec
                .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_NETWORK_CARD)
                .configSpaceSize(6 + 2) // mac + status
                .queueCount(2)
                .build());
    }

    @Nullable
    public byte[] readEthernetFrame() {
        if (hasDeviceFailed()) {
            return null;
        }

        try {
            final DescriptorChain transmit = validateReadOnlyDescriptorChain(VIRTQ_TRANSMIT, null);
            if (transmit == null) {
                return null;
            }

            // We completely ignore the header. We don't have any flags that would require us checking it.
            for (int i = 0; i < HEADER_SIZE; i++) {
                transmit.get();
            }

            final byte[] packet = new byte[transmit.readableBytes()];
            transmit.get(packet, 0, packet.length);

            transmit.use();

            return packet;
        } catch (final VirtIODeviceException | MemoryAccessException e) {
            error();
            return null;
        }
    }

    public void writeEthernetFrame(final byte[] packet) {
        if (hasDeviceFailed()) {
            return;
        }

        try {
            final DescriptorChain receive = validateWriteOnlyDescriptorChain(VIRTQ_RECEIVE, null);
            if (receive == null) {
                return;
            }

            // We don't use any flags that require us to provide a header, so just write an empty one.
            for (int i = 0; i < HEADER_SIZE; i++) {
                receive.put((byte) 0);
            }

            receive.put(packet, 0, packet.length);
            receive.use();
        } catch (final VirtIODeviceException | MemoryAccessException e) {
            error();
        }
    }

    @Override
    protected void handleFeaturesNegotiated() {
        setQueueNotifications(VIRTQ_RECEIVE, false);
        setQueueNotifications(VIRTQ_TRANSMIT, false);
    }

    private boolean hasDeviceFailed() {
        return (getStatus() & VIRTIO_STATUS_FAILED) != 0;
    }
}
