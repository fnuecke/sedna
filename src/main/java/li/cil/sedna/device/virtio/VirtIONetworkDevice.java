package li.cil.sedna.device.virtio;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;

import javax.annotation.Nullable;

public final class VirtIONetworkDevice extends AbstractVirtIODevice {
    private static final long VIRTIO_NET_F_CSUM = 1L << 0; // Device handles packets with partial checksum. This "checksum offload" is a common feature on modern network cards.
    private static final long VIRTIO_NET_F_GUEST_CSUM = 1L << 1; // Driver handles packets with partial checksum.
    private static final long VIRTIO_NET_F_CTRL_GUEST_OFFLOADS = 1L << 2; // Control channel offloads reconfiguration support.
    private static final long VIRTIO_NET_F_MTU = 1L << 3; // Device maximum MTU reporting is supported. If offered by the device, device advises driver about the value of its maximum MTU. If negotiated, the driver uses mtu as the maximum MTU value.
    private static final long VIRTIO_NET_F_MAC = 1L << 5; // Device has given MAC address.
    private static final long VIRTIO_NET_F_GUEST_TSO4 = 1L << 7; // Driver can receive TSOv4.
    private static final long VIRTIO_NET_F_GUEST_TSO6 = 1L << 8; // Driver can receive TSOv6.
    private static final long VIRTIO_NET_F_GUEST_ECN = 1L << 9; // Driver can receive TSO with ECN.
    private static final long VIRTIO_NET_F_GUEST_UFO = 1L << 10; // Driver can receive UFO.
    private static final long VIRTIO_NET_F_HOST_TSO4 = 1L << 11; // Device can receive TSOv4.
    private static final long VIRTIO_NET_F_HOST_TSO6 = 1L << 12; // Device can receive TSOv6.
    private static final long VIRTIO_NET_F_HOST_ECN = 1L << 13; // Device can receive TSO with ECN.
    private static final long VIRTIO_NET_F_HOST_UFO = 1L << 14; // Device can receive UFO.
    private static final long VIRTIO_NET_F_MRG_RXBUF = 1L << 15; // Driver can merge receive buffers.
    private static final long VIRTIO_NET_F_STATUS = 1L << 16; // Configuration status field is available.
    private static final long VIRTIO_NET_F_CTRL_VQ = 1L << 17; // Control channel is available.
    private static final long VIRTIO_NET_F_CTRL_RX = 1L << 18; // Control channel RX mode support.
    private static final long VIRTIO_NET_F_CTRL_VLAN = 1L << 19; // Control channel VLAN filtering.
    private static final long VIRTIO_NET_F_GUEST_ANNOUNCE = 1L << 21; // Driver can send gratuitous packets.
    private static final long VIRTIO_NET_F_MQ = 1L << 22; // Device supports multiqueue with automatic receive steering.
    private static final long VIRTIO_NET_F_CTRL_MAC_ADDR = 1L << 23; // Set MAC address through control channel.
    private static final long VIRTIO_NET_F_RSC_EXT = 1L << 61; // Device can process duplicated ACKs and report number of coalesced segments and duplicated ACKs
    private static final long VIRTIO_NET_F_STANDBY = 1L << 62; // Device may act as a standby for a primary device with the same MAC address.

    private static final short VIRTIO_NET_S_LINK_UP = 1 << 0;
    private static final short VIRTIO_NET_S_ANNOUNCE = 1 << 1;

    // struct virtio_net_config {
    //   u8 mac[6];
    //   le16 status;
    //   le16 max_virtqueue_pairs;
    //   le16 mtu;
    // };
    private static final int VIRTIO_NETWORK_CFG_MAC_OFFSET = 0;
    private static final int VIRTIO_NETWORK_CFG_STATUS_OFFSET = 6;
    private static final int VIRTIO_NETWORK_CFG_MAX_VIRTQUEUE_PAIRS_OFFSET = 8;
    private static final int VIRTIO_NETWORK_CFG_MTU_OFFSET = 10;

    private static final byte VIRTIO_NET_CTRL_RX = 0;
    private static final byte VIRTIO_NET_CTRL_MAC = 1;
    private static final byte VIRTIO_NET_CTRL_VLAN = 2;
    private static final byte VIRTIO_NET_CTRL_ANNOUNCE = 3;

    private static final byte VIRTIO_NET_CTRL_RX_PROMISC = 0;
    private static final byte VIRTIO_NET_CTRL_RX_ALLMULTI = 1;
    private static final byte VIRTIO_NET_CTRL_RX_ALLUNI = 2;
    private static final byte VIRTIO_NET_CTRL_RX_NOMULTI = 3;
    private static final byte VIRTIO_NET_CTRL_RX_NOUNI = 4;
    private static final byte VIRTIO_NET_CTRL_RX_NOBCAST = 5;

    private static final byte VIRTIO_NET_CTRL_MAC_TABLE_SET = 0;
    private static final byte VIRTIO_NET_CTRL_MAC_ADDR_SET = 1;

    private static final byte VIRTIO_NET_CTRL_VLAN_ADD = 0;
    private static final byte VIRTIO_NET_CTRL_VLAN_DEL = 1;

    private static final byte VIRTIO_NET_CTRL_ANNOUNCE_ACK = 0;

    private static final byte VIRTIO_NET_OK = 0;
    private static final byte VIRTIO_NET_ERR = 1;

    public static final int VIRTIO_NET_HDR_F_NONE = 0;
    public static final int VIRTIO_NET_HDR_F_NEEDS_CSUM = 1;
    public static final int VIRTIO_NET_HDR_F_DATA_VALID = 2;
    public static final int VIRTIO_NET_HDR_F_RSC_INFO = 4;

    public static final int VIRTIO_NET_HDR_GSO_NONE = 0;
    public static final int VIRTIO_NET_HDR_GSO_TCPV4 = 1;
    public static final int VIRTIO_NET_HDR_GSO_UDP = 3;
    public static final int VIRTIO_NET_HDR_GSO_TCPV6 = 4;
    public static final int VIRTIO_NET_HDR_GSO_ECN = 0x80;

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
    private static final int VIRTQ_CONTROL = 2; // controlq

    @Serialized private byte[] mac = new byte[6];

    public VirtIONetworkDevice(final MemoryMap memoryMap) {
        super(memoryMap, VirtIODeviceSpec
                .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_NETWORK_CARD)
                .features(VIRTIO_NET_F_MAC)
                .configSpaceSize(6 + 2) // mac + status
                .queueCount(2)
                .build());

        // One of the OUI patterns safe for local use:
        //xE-xx-xx-xx-xx-xx
        mac[0] = (byte) 0x5E;
        mac[1] = (byte) 0xD0;

        // Fill the lower four bytes at quasi-random using our hash code.
        final int hash = hashCode();
        mac[2] = (byte) ((hash >> 24) & 0xFF);
        mac[3] = (byte) ((hash >> 16) & 0xFF);
        mac[4] = (byte) ((hash >> 8) & 0xFF);
        mac[5] = (byte) (hash & 0xFF);
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
    protected void initializeConfig() {
        setConfigValue(VIRTIO_NETWORK_CFG_MAC_OFFSET, mac);
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
