package li.cil.sedna.device.virtio;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.serial.SerialDevice;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * VirtIO Console device.
 * <p>
 * This device can be used in two modes:
 * <ul>
 *     <li>As a single-port <em>console</em> device (tty)</li>
 *     <li>As a multi-port <em>serial</em> device</li>
 * </ul>
 */
@SuppressWarnings("PointlessBitwiseExpression")
public final class VirtIOConsoleDevice extends AbstractVirtIODevice implements SerialDevice {
    private static final short DEFAULT_COLUMN_COUNT = 80;
    private static final short DEFAULT_ROW_COUNT = 25;

    private static final int BUFFER_SIZE = 4 * 1024;
    private static final int QUEUE_SIZE = 4; // 16kib -> well enough for a console/serial port

    private static final long VIRTIO_CONSOLE_F_SIZE = 1L << 0; // Configuration for cols and rows.
    private static final long VIRTIO_CONSOLE_F_MULTIPORT = 1L << 1; // Configuration max_nr_ports, control virtqueues.
    private static final long VIRTIO_CONSOLE_F_EMERG_WRITE = 1L << 2; // Supports emergency write.

    private static final int VIRTIO_CONSOLE_DEVICE_READY = 0;
    private static final int VIRTIO_CONSOLE_DEVICE_ADD = 1;
    private static final int VIRTIO_CONSOLE_DEVICE_REMOVE = 2;
    private static final int VIRTIO_CONSOLE_PORT_READY = 3;
    private static final int VIRTIO_CONSOLE_CONSOLE_PORT = 4;
    private static final int VIRTIO_CONSOLE_RESIZE = 5;
    private static final int VIRTIO_CONSOLE_PORT_OPEN = 6;
    private static final int VIRTIO_CONSOLE_PORT_NAME = 7;

    private static final int VIRTIO_CONSOLE_CFG_COLS_OFFSET = 0;
    private static final int VIRTIO_CONSOLE_CFG_ROWS_OFFSET = 2;
    private static final int VIRTIO_CONSOLE_CFG_MAX_NR_PORTS_OFFSET = 4;
    private static final int VIRTIO_CONSOLE_CFG_EMERG_WR_OFFSET = 8;

    private static final int VIRTQ_RECEIVE_CONTROL = 2; // control receiveq
    private static final int VIRTQ_TRANSMIT_CONTROL = 3; // control transmitq

    // struct virtio_console_control { le32 id; le16 event; le16 value; }
    private static final int CONTROL_MESSAGE_SIZE = 8;
    private static final int MAX_PORT_COUNT = (VirtIODeviceSpec.MAX_VIRTQUEUE_COUNT - 2) / 2;

    private enum PortState {
        AWAITING_PORT_ADD,
        AWAITING_PORT_READY,
        AWAITING_PORT_NAME,
        AWAITING_HOST_OPEN,
        READY,
    }

    private static final class Port {
        @Nullable
        transient String name;
        @Serialized
        PortState state = PortState.AWAITING_PORT_ADD;
        @Serialized
        boolean guestOpen;
        // Store input and output in own buffers to avoid storing chains for serialization.
        @Serialized
        final ByteArrayFIFOQueue transmitBuffer = new ByteArrayFIFOQueue(BUFFER_SIZE);
        @Serialized
        final ByteArrayFIFOQueue receiveBuffer = new ByteArrayFIFOQueue(BUFFER_SIZE);
    }

    @Serialized
    private final Port[] ports;
    @Serialized
    private boolean deviceReady;
    @Serialized
    private boolean isHandshakeComplete;

    private final transient boolean isMultiport;
    private final transient PortView[] portViews;
    private final transient ByteBuffer inboundMessage =
            ByteBuffer.allocate(CONTROL_MESSAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final transient ByteBuffer outboundMessage =
            ByteBuffer.allocate(CONTROL_MESSAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

    public VirtIOConsoleDevice(final MemoryMap memoryMap) {
        super(memoryMap, buildSpec(0));
        this.isMultiport = false;
        this.ports = new Port[]{new Port()};
        this.portViews = new PortView[]{new PortView(0)};
    }

    public VirtIOConsoleDevice(final MemoryMap memoryMap, final String... portNames) {
        super(memoryMap, buildSpec(validatePortNames(portNames).length));
        this.isMultiport = true;
        this.ports = new Port[portNames.length];
        this.portViews = new PortView[portNames.length];
        for (int i = 0; i < portNames.length; i++) {
            ports[i] = new Port();
            ports[i].name = portNames[i];
            portViews[i] = new PortView(i);
        }
    }

    // ------------------------------------------------------------- //

    public int getPortCount() {
        return ports.length;
    }

    public PortView getPort(final int port) {
        return portViews[checkPort(port)];
    }

    @Override
    public int read() {
        return read(0);
    }

    private int read(final int port) {
        if (hasDeviceFailed()) {
            return -1;
        }

        final ByteArrayFIFOQueue transmitBuffer = ports[checkPort(port)].transmitBuffer;
        if (transmitBuffer.isEmpty()) {
            resumeHandshake();

            try {
                // 5.3.6.1: The driver MUST NOT put a device-writable buffer in a transmitq.
                final DescriptorChain transmit = validateReadOnlyDescriptorChain(transmitQueue(port), null);
                if (transmit != null) {
                    while (transmit.readableBytes() > 0) {
                        transmitBuffer.enqueue(transmit.get());
                    }
                    transmit.use();
                }
            } catch (final VirtIODeviceException | MemoryAccessException e) {
                error();
                return -1;
            }
        }

        if (transmitBuffer.isEmpty()) {
            return -1;
        }

        return transmitBuffer.dequeueByte() & 0xFF;
    }

    @Override
    public boolean canPutByte() {
        return canPutByte(0);
    }

    private boolean canPutByte(final int port) {
        if (hasDeviceFailed()) {
            return false;
        }

        resumeHandshake();

        return ports[checkPort(port)].receiveBuffer.size() < BUFFER_SIZE;
    }

    @Override
    public void putByte(final byte value) {
        putByte(0, value);
    }

    private void putByte(final int port, final byte value) {
        if (hasDeviceFailed()) {
            return;
        }

        final ByteArrayFIFOQueue receiveBuffer = ports[checkPort(port)].receiveBuffer;
        if (receiveBuffer.size() < BUFFER_SIZE) {
            receiveBuffer.enqueue(value);
        }

        if (receiveBuffer.size() >= BUFFER_SIZE) {
            flush(port);
        }
    }

    @Override
    public void flush() {
        flush(0);
    }

    private void flush(final int port) {
        if (hasDeviceFailed()) {
            return;
        }

        resumeHandshake();

        final ByteArrayFIFOQueue receiveBuffer = ports[checkPort(port)].receiveBuffer;
        while (!receiveBuffer.isEmpty()) {
            try {
                // 5.3.6.1: The driver MUST NOT put a device-readable in a receiveq.
                final DescriptorChain receive = validateWriteOnlyDescriptorChain(receiveQueue(port), null);
                if (receive == null) {
                    return;
                }

                while (receive.writableBytes() > 0 && !receiveBuffer.isEmpty()) {
                    receive.put(receiveBuffer.dequeueByte());
                }
                receive.use();
            } catch (final VirtIODeviceException | MemoryAccessException e) {
                error();
                return;
            }
        }
    }

    @Override
    public void reset() {
        super.reset();

        deviceReady = false;
        for (final Port port : ports) {
            port.state = PortState.AWAITING_PORT_ADD;
            port.guestOpen = false;
            port.transmitBuffer.clear();
            port.receiveBuffer.clear();
        }
        isHandshakeComplete = false;
    }

    // ------------------------------------------------------------- //

    @Override
    protected void initializeConfig() {
        if (isMultiport) {
            setConfigValue(VIRTIO_CONSOLE_CFG_MAX_NR_PORTS_OFFSET, ports.length);
        } else {
            setConfigValue(VIRTIO_CONSOLE_CFG_COLS_OFFSET, DEFAULT_COLUMN_COUNT);
            setConfigValue(VIRTIO_CONSOLE_CFG_ROWS_OFFSET, DEFAULT_ROW_COUNT);
        }
    }

    @Override
    protected void storeConfig(final int offset, final long value, final int sizeLog2) {
        if (offset == VIRTIO_CONSOLE_CFG_EMERG_WR_OFFSET) {
            // 5.3.5.1: The device SHOULD transmit the lower byte written to emerg_wr [...]
        }
    }

    @Override
    protected void handleQueueNotification(final int queueIndex) throws VirtIODeviceException, MemoryAccessException {
        if (!isMultiport) {
            return;
        }

        if (queueIndex == VIRTQ_TRANSMIT_CONTROL) {
            receiveControlMessages();
        }

        sendPendingControlMessages();
    }

    private void receiveControlMessages() throws VirtIODeviceException, MemoryAccessException {
        DescriptorChain chain;
        while ((chain = validateReadOnlyDescriptorChain(VIRTQ_TRANSMIT_CONTROL, null)) != null) {
            if (chain.readableBytes() >= CONTROL_MESSAGE_SIZE) {
                inboundMessage.clear();
                chain.get(inboundMessage);
                inboundMessage.flip();
                final int id = inboundMessage.getInt();
                final int event = inboundMessage.getShort() & 0xFFFF;
                final int value = inboundMessage.getShort() & 0xFFFF;
                handleControlMessage(id, event, value);
            }
            chain.use();
        }
    }

    private void handleControlMessage(final int id, final int event, final int value) {
        switch (event) {
            case VIRTIO_CONSOLE_DEVICE_READY -> {
                deviceReady = value != 0;
                for (final Port port : ports) {
                    port.state = PortState.AWAITING_PORT_ADD;
                }
                isHandshakeComplete = false;
            }
            case VIRTIO_CONSOLE_PORT_READY -> {
                if (isPortValid(id) && ports[id].state == PortState.AWAITING_PORT_READY) {
                    ports[id].state = value != 0
                            ? PortState.AWAITING_PORT_NAME
                            : PortState.AWAITING_PORT_ADD;
                    isHandshakeComplete = false;
                }
            }
            case VIRTIO_CONSOLE_PORT_OPEN -> {
                if (isPortValid(id)) {
                    ports[id].guestOpen = value != 0;
                }
            }
            default -> {
                // not used
            }
        }
    }

    @Override
    protected void handleFeaturesNegotiated() {
        for (int port = 0; port < ports.length; port++) {
            setQueueNotifications(receiveQueue(port), false);
            setQueueNotifications(transmitQueue(port), false);
        }
    }

    // ------------------------------------------------------------- //

    private boolean hasDeviceFailed() {
        return (getStatus() & VIRTIO_STATUS_FAILED) != 0;
    }

    private boolean isPortValid(final int id) {
        return id >= 0 && id < ports.length;
    }

    private int checkPort(final int port) {
        if (port < 0 || port >= ports.length) {
            throw new IndexOutOfBoundsException(String.format(
                    "Port [%d] is out of range; this device has [%d].", port, ports.length));
        }
        return port;
    }

    private void resumeHandshake() {
        if (isHandshakeComplete || !isMultiport) {
            return;
        }

        try {
            sendPendingControlMessages();
        } catch (final VirtIODeviceException | MemoryAccessException e) {
            error();
            return;
        }

        isHandshakeComplete = isHandshakeComplete();
    }

    private boolean isHandshakeComplete() {
        if (!deviceReady) {
            return false;
        }
        for (final Port port : ports) {
            if (port.state != PortState.READY) {
                return false;
            }
        }
        return true;
    }

    private void sendPendingControlMessages() throws VirtIODeviceException, MemoryAccessException {
        if (!deviceReady) {
            return;
        }

        for (int id = 0; id < ports.length; id++) {
            if (!advancePort(id)) {
                return; // Buffers are full, try again later.
            }
        }
    }

    private boolean advancePort(final int id) throws VirtIODeviceException, MemoryAccessException {
        final Port port = ports[id];

        while (true) {
            switch (port.state) {
                case AWAITING_PORT_ADD -> {
                    if (!trySendControlMessage(id, VIRTIO_CONSOLE_DEVICE_ADD, 1, null)) {
                        return false;
                    }
                    port.state = PortState.AWAITING_PORT_READY;
                }
                case AWAITING_PORT_NAME -> {
                    if (!trySendControlMessage(id, VIRTIO_CONSOLE_PORT_NAME, 1, port.name)) {
                        return false;
                    }
                    port.state = PortState.AWAITING_HOST_OPEN;
                }
                case AWAITING_HOST_OPEN -> {
                    // Without this the driver leaves the port marked as not connected on
                    // our end, and guest writes to it fail.
                    if (!trySendControlMessage(id, VIRTIO_CONSOLE_PORT_OPEN, 1, null)) {
                        return false;
                    }
                    port.state = PortState.READY;
                }
                default -> {
                    return true;
                }
            }
        }
    }

    private boolean trySendControlMessage(final int id, final int event, final int value, @Nullable final String name)
            throws VirtIODeviceException, MemoryAccessException {
        final byte[] nameBytes = name != null ? name.getBytes(StandardCharsets.UTF_8) : null;

        final DescriptorChain chain = validateWriteOnlyDescriptorChain(VIRTQ_RECEIVE_CONTROL, null);
        if (chain == null) {
            return false;
        }

        final int writable = chain.writableBytes();
        if (writable < CONTROL_MESSAGE_SIZE) {
            // A control buffer too small for even the header is a broken driver; say so
            // rather than sitting in quiet confusion for the rest of the machine's life.
            chain.use();
            error();
            return false;
        }

        // struct virtio_console_control {
        //     le32 id;    /* Port number */
        //     le16 event; /* The kind of control event */
        //     le16 value; /* Extra information for the event */
        // };
        outboundMessage.clear();
        outboundMessage.putInt(id);
        outboundMessage.putShort((short) event);
        outboundMessage.putShort((short) value);
        outboundMessage.flip();
        chain.put(outboundMessage);

        if (nameBytes != null) {
            // The driver reads the remainder of the buffer as the name.
            // Truncated rather than dropped if it somehow does not fit.
            chain.put(nameBytes, 0, Math.min(nameBytes.length, writable - CONTROL_MESSAGE_SIZE));
        }

        chain.use();
        return true;
    }

    private static String[] validatePortNames(final String... portNames) {
        if (portNames.length == 0) {
            throw new IllegalArgumentException("At least one port name is required; " +
                    "use the single-argument constructor for a console.");
        }
        if (portNames.length > MAX_PORT_COUNT) {
            throw new IllegalArgumentException(String.format(
                    "Cannot expose [%d] ports; the queue budget allows at most [%d].",
                    portNames.length, MAX_PORT_COUNT));
        }
        for (final String portName : portNames) {
            if (portName == null || portName.isEmpty()) {
                throw new IllegalArgumentException("Port names must be non-empty.");
            }
        }
        return portNames;
    }

    private static VirtIODeviceSpec buildSpec(final int portCount) {
        final VirtIODeviceSpec.Builder builder = VirtIODeviceSpec
                .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_CONSOLE);
        if (portCount == 0) {
            return builder
                    .features(VIRTIO_CONSOLE_F_SIZE)
                    .queueCount(2)
                    .queueSizeMax(QUEUE_SIZE)
                    .configSpaceSize(4)
                    .build();
        } else {
            return builder
                    .features(VIRTIO_CONSOLE_F_MULTIPORT)
                    .queueCount(portCount * 2 + 2)
                    .queueSizeMax(QUEUE_SIZE)
                    .configSpaceSize(8) // through max_nr_ports
                    .build();
        }
    }

    private static int receiveQueue(final int port) {
        return port == 0 ? 0 : (port * 2 + 2);
    }

    private static int transmitQueue(final int port) {
        return port == 0 ? 1 : (port * 2 + 3);
    }

    public final class PortView implements SerialDevice {
        private final int port;

        PortView(final int port) {
            this.port = port;
        }

        public boolean isPortOpen() {
            return VirtIOConsoleDevice.this.ports[checkPort(port)].guestOpen;
        }

        @Override
        public int read() {
            return VirtIOConsoleDevice.this.read(port);
        }

        @Override
        public boolean canPutByte() {
            return VirtIOConsoleDevice.this.canPutByte(port);
        }

        @Override
        public void putByte(final byte value) {
            VirtIOConsoleDevice.this.putByte(port, value);
        }

        @Override
        public void flush() {
            VirtIOConsoleDevice.this.flush(port);
        }
    }
}
