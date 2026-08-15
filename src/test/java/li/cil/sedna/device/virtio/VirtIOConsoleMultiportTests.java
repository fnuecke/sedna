package li.cil.sedna.device.virtio;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public final class VirtIOConsoleMultiportTests {
    private static final int VIRTIO_MMIO_DEVICE_FEATURES = 0x010;
    private static final int VIRTIO_MMIO_DEVICE_FEATURES_SEL = 0x014;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_QUEUE_SEL = 0x030;
    private static final int VIRTIO_MMIO_QUEUE_NUM = 0x038;
    private static final int VIRTIO_MMIO_QUEUE_READY = 0x044;
    private static final int VIRTIO_MMIO_QUEUE_NOTIFY = 0x050;
    private static final int VIRTIO_MMIO_STATUS = 0x070;
    private static final int VIRTIO_MMIO_CONFIG = 0x100;
    private static final int VIRTIO_MMIO_QUEUE_DESC_LOW = 0x080;
    private static final int VIRTIO_MMIO_QUEUE_DESC_HIGH = 0x084;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_LOW = 0x090;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_HIGH = 0x094;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_LOW = 0x0A0;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_HIGH = 0x0A4;

    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1 << 0; // feature bit 32, high word
    private static final int MULTIPORT_LOW = 1 << 1;  // feature bit 1, low word
    private static final int SIZE_LOW = 1 << 0;

    private static final int VIRTQ_DESC_F_WRITE = 2;

    private static final int VIRTIO_CONSOLE_DEVICE_READY = 0;
    private static final int VIRTIO_CONSOLE_DEVICE_ADD = 1;
    private static final int VIRTIO_CONSOLE_PORT_READY = 3;
    private static final int VIRTIO_CONSOLE_PORT_OPEN = 6;
    private static final int VIRTIO_CONSOLE_PORT_NAME = 7;

    private static final int BAD_ID = 0xFFFFFFFF;

    private static final int VIRTQ_RECEIVE_CONTROL = 2;
    private static final int VIRTQ_TRANSMIT_CONTROL = 3;

    private static final int QUEUE_SIZE = 256;
    private static final int CONTROL_MESSAGE_SIZE = 8;
    private static final int BUFFER_SIZE = 64;

    private static final long MEMORY_START = 0x80000000L;
    private static final int MEMORY_LENGTH = 1024 * 1024;
    private static final long QUEUE_STRIDE = 0x10000;

    private static final String PORT_NAME = "test.rpc.0";
    private static final int MAX_QUEUES = 16;

    private MemoryMap memoryMap;
    private VirtIOConsoleDevice device;
    private int queueCount;
    private final int[] descriptorsUsed = new int[MAX_QUEUES];
    private final int[] availIndex = new int[MAX_QUEUES];

    @BeforeEach
    public void setUp() {
        createDevice(PORT_NAME);
    }

    // ------------------------------------------------------------- //

    @Test
    public void multiportDeviceOffersMultiportAndOnePort() {
        device.reset(); // config space is populated on reset, which a driver always does first

        device.store(VIRTIO_MMIO_DEVICE_FEATURES_SEL, 0, Sizes.SIZE_32_LOG2);
        final long offered = device.load(VIRTIO_MMIO_DEVICE_FEATURES, Sizes.SIZE_32_LOG2);

        assertNotEquals(0, offered & MULTIPORT_LOW, "multiport device must offer VIRTIO_CONSOLE_F_MULTIPORT");
        assertEquals(1, device.load(VIRTIO_MMIO_CONFIG + 4, Sizes.SIZE_32_LOG2),
                "max_nr_ports must advertise the single port");
    }

    @Test
    public void consoleDeviceIsUnchanged() {
        final VirtIOConsoleDevice console = new VirtIOConsoleDevice(memoryMap);

        console.store(VIRTIO_MMIO_DEVICE_FEATURES_SEL, 0, Sizes.SIZE_32_LOG2);
        final long offered = console.load(VIRTIO_MMIO_DEVICE_FEATURES, Sizes.SIZE_32_LOG2);

        assertNotEquals(0, offered & SIZE_LOW, "the plain console must still offer VIRTIO_CONSOLE_F_SIZE");
        assertEquals(0, offered & MULTIPORT_LOW, "the plain console must not offer multiport");
    }

    @Test
    public void deviceAnnouncesPortOnlyAfterDriverIsReady() throws Exception {
        bringUp();
        postControlReceiveBuffers(4);

        assertNull(readControlMessage(0), "nothing may be sent before the driver says it is ready");

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);

        final ControlMessage added = readControlMessage(0);
        assertNotNull(added, "device must announce its port once the driver is ready");
        assertEquals(VIRTIO_CONSOLE_DEVICE_ADD, added.event);
        assertEquals(0, added.id, "the single port is port 0");
    }

    @Test
    public void deviceSendsNameAndOpensPortAfterPortReady() throws Exception {
        bringUp();
        postControlReceiveBuffers(4);

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);
        sendControlMessage(VIRTIO_CONSOLE_PORT_READY, 0, 1);

        final ControlMessage name = readControlMessage(1);
        assertNotNull(name, "device must send the port name once the port is acknowledged");
        assertEquals(VIRTIO_CONSOLE_PORT_NAME, name.event);
        assertEquals(PORT_NAME, name.payload,
                "the name is what the guest exposes at /sys/class/virtio-ports/*/name");
        assertEquals(CONTROL_MESSAGE_SIZE + PORT_NAME.length(), name.length,
                "the driver sizes the name from the used-ring length, so it must be exact "
                        + "-- the name is deliberately sent without a terminator");

        final ControlMessage open = readControlMessage(2);
        assertNotNull(open, "device must mark its own end of the port open");
        assertEquals(VIRTIO_CONSOLE_PORT_OPEN, open.event);
        assertEquals(1, open.value, "a value of zero would leave the guest unable to write");
    }

    @Test
    public void nothingIsSentBeforeThePortIsAcknowledged() throws Exception {
        bringUp();
        postControlReceiveBuffers(4);

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);

        // Only the DEVICE_ADD; the driver has not acknowledged the port yet.
        assertNotNull(readControlMessage(0));
        assertNull(readControlMessage(1), "the driver ignores messages about a port it has not acknowledged");
    }

    @Test
    public void handshakeIsRedoneAfterReset() throws MemoryAccessException {
        bringUp();
        postControlReceiveBuffers(4);
        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);
        assertNotNull(readControlMessage(0), "precondition: port announced once");

        device.reset();

        resetDriverState();
        bringUp();
        postControlReceiveBuffers(4);
        assertNull(readControlMessage(0), "a reset device must wait for the driver all over again");

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);
        final ControlMessage added = readControlMessage(0);
        assertNotNull(added, "port must be announced again after a reset");
        assertEquals(VIRTIO_CONSOLE_DEVICE_ADD, added.event);
    }

    @Test
    public void handshakeResumesWhenBuffersArriveLate() throws Exception {
        bringUp();

        // Driver announces itself without having posted anywhere to reply.
        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);

        // Posting buffers afterwards must let the stalled handshake continue rather
        // than deadlock, since the driver kicks the receive queue when it refills it.
        postControlReceiveBuffers(4);
        device.store(VIRTIO_MMIO_QUEUE_NOTIFY, VIRTQ_RECEIVE_CONTROL, Sizes.SIZE_32_LOG2);

        final ControlMessage added = readControlMessage(0);
        assertNotNull(added, "handshake must recover once the driver provides buffers");
        assertEquals(VIRTIO_CONSOLE_DEVICE_ADD, added.event);
    }

    @Test
    public void everyPortIsAnnouncedAndNamed() throws Exception {
        createDevice("test.rpc.0", "test.blob.0", "test.event.0");
        bringUp();
        postControlReceiveBuffers(16);

        assertEquals(3, device.load(VIRTIO_MMIO_CONFIG + 4, Sizes.SIZE_32_LOG2),
                "max_nr_ports must match the number of names");

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);

        // One DEVICE_ADD per port, in port order.
        for (int id = 0; id < 3; id++) {
            final ControlMessage added = readControlMessage(id);
            assertNotNull(added, "port " + id + " was never announced");
            assertEquals(VIRTIO_CONSOLE_DEVICE_ADD, added.event);
            assertEquals(id, added.id);
        }

        sendControlMessage(VIRTIO_CONSOLE_PORT_READY, 1, 1);
        final ControlMessage name = readControlMessage(3);
        assertNotNull(name, "acknowledged port must be named");
        assertEquals(VIRTIO_CONSOLE_PORT_NAME, name.event);
        assertEquals(1, name.id, "the name must be for the port that was acknowledged");
        assertEquals("test.blob.0", name.payload);

        final ControlMessage open = readControlMessage(4);
        assertNotNull(open);
        assertEquals(VIRTIO_CONSOLE_PORT_OPEN, open.event);
        assertEquals(1, open.id);

        assertNull(readControlMessage(5), "ports the driver has not acknowledged stay quiet");
    }

    @Test
    public void dataUsesThePerPortQueues() throws Exception {
        createDevice("first", "second");
        bringUp();

        publishGuestData(transmitQueue(1), new byte[]{'h', 'i'});
        assertEquals(-1, device.read(0), "port 0 must not see port 1's data");
        assertEquals('h', device.read(1));
        assertEquals('i', device.read(1));
        assertEquals(-1, device.read(1));

        final int index = publish(receiveQueue(1), BUFFER_SIZE, true);
        device.putByte(1, (byte) 'X');
        device.flush(1);
        assertEquals('X', readDeviceWrite(receiveQueue(1), 0, index),
                "host byte did not land on the port's own receive queue");
    }

    @Test
    public void portCountIsValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtIOConsoleDevice(memoryMap, new String[0]),
                "a multiport device with no ports is meaningless");
        assertThrows(IllegalArgumentException.class,
                () -> new VirtIOConsoleDevice(memoryMap, "a", null),
                "an unnamed port cannot be advertised");

        assertDoesNotThrow(() -> new VirtIOConsoleDevice(memoryMap,
                "a", "b", "c", "d", "e", "f", "g"));
        assertThrows(IllegalArgumentException.class, () -> new VirtIOConsoleDevice(memoryMap,
                        "a", "b", "c", "d", "e", "f", "g", "h"),
                "the queue budget must be enforced rather than overflowing the spec");
    }

    @Test
    public void controlBufferTooSmallForTheHeaderIsNotLeaked() throws Exception {
        bringUp();
        publish(VIRTQ_RECEIVE_CONTROL, CONTROL_MESSAGE_SIZE - 4, true);

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);

        assertEquals(1, usedCount(VIRTQ_RECEIVE_CONTROL),
                "an unusable control buffer must still be returned to the driver");
        assertNotEquals(0, device.getStatus() & AbstractVirtIODevice.VIRTIO_STATUS_DEVICE_NEEDS_RESET,
                "a control buffer too small for the header is a driver bug and should say so");
    }

    @Test
    public void nameTooLongForTheBufferIsTruncatedRatherThanDropped() throws Exception {
        bringUp();
        // Room for the header plus two bytes of the nine-character name.
        for (int i = 0; i < 4; i++) {
            publish(VIRTQ_RECEIVE_CONTROL, CONTROL_MESSAGE_SIZE + 2, true);
        }

        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);
        sendControlMessage(VIRTIO_CONSOLE_PORT_READY, 0, 1);

        final ControlMessage name = readControlMessage(1);
        assertNotNull(name, "the name message must still be sent");
        assertEquals(VIRTIO_CONSOLE_PORT_NAME, name.event);
        assertEquals(PORT_NAME.substring(0, 2), name.payload, "the name should be truncated to fit");
        assertEquals(0, device.getStatus() & AbstractVirtIODevice.VIRTIO_STATUS_DEVICE_NEEDS_RESET,
                "a short name is not a driver error");
    }

    @Test
    public void handshakeResumesFromTheDataPathWithoutAKick() throws Exception {
        bringUp();

        // Driver announces itself with nowhere to reply, so the device is left owing a
        // DEVICE_ADD.
        sendControlMessage(VIRTIO_CONSOLE_DEVICE_READY, BAD_ID, 1);
        postControlReceiveBuffers(4);

        // No QUEUE_NOTIFY this time: after a savegame restore nothing kicks the control
        // queue again, and the only thing still running is the host polling the data
        // path. That has to be enough to finish the handshake.
        assertEquals(-1, device.read(0), "no guest data was queued");

        final ControlMessage added = readControlMessage(0);
        assertNotNull(added, "handshake must advance from a data-path poll alone");
        assertEquals(VIRTIO_CONSOLE_DEVICE_ADD, added.event);
    }

    // ------------------------------------------------------------- //

    private record ControlMessage(int id, int event, int value, String payload, int length) {
    }

    private void createDevice(final String... portNames) {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(MEMORY_START, Memory.create(MEMORY_LENGTH));
        device = new VirtIOConsoleDevice(memoryMap, portNames);
        queueCount = portNames.length * 2 + 2;
        java.util.Arrays.fill(descriptorsUsed, 0);
        java.util.Arrays.fill(availIndex, 0);
    }

    private void bringUp() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, MULTIPORT_LOW, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
                | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        for (int queue = 0; queue < queueCount; queue++) {
            device.store(VIRTIO_MMIO_QUEUE_SEL, queue, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_NUM, QUEUE_SIZE, Sizes.SIZE_32_LOG2);
            storeAddress(VIRTIO_MMIO_QUEUE_DESC_LOW, VIRTIO_MMIO_QUEUE_DESC_HIGH, desc(queue));
            storeAddress(VIRTIO_MMIO_QUEUE_DRIVER_LOW, VIRTIO_MMIO_QUEUE_DRIVER_HIGH, avail(queue));
            storeAddress(VIRTIO_MMIO_QUEUE_DEVICE_LOW, VIRTIO_MMIO_QUEUE_DEVICE_HIGH, used(queue));
            device.store(VIRTIO_MMIO_QUEUE_READY, 1, Sizes.SIZE_32_LOG2);
        }

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
                | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK
                | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER_OK, Sizes.SIZE_32_LOG2);
    }

    private void resetDriverState() throws MemoryAccessException {
        for (int queue = 0; queue < queueCount; queue++) {
            for (int i = 0; i < 16; i++) {
                memoryMap.store(used(queue) + 4 + (long) i * 8, 0, Sizes.SIZE_64_LOG2);
            }
            memoryMap.store(used(queue) + 2, 0, Sizes.SIZE_16_LOG2);
            memoryMap.store(avail(queue) + 2, 0, Sizes.SIZE_16_LOG2);
        }
        java.util.Arrays.fill(descriptorsUsed, 0);
        java.util.Arrays.fill(availIndex, 0);
    }

    private long base(final int queue) {
        return MEMORY_START + QUEUE_STRIDE * queue;
    }

    private long desc(final int queue) {
        return base(queue);
    }

    private long avail(final int queue) {
        return base(queue) + 0x2000;
    }

    private long used(final int queue) {
        return base(queue) + 0x4000;
    }

    private long data(final int queue, final int index) {
        return base(queue) + 0x6000 + (long) index * BUFFER_SIZE;
    }

    private void publishGuestData(final int queue, final byte[] bytes) throws MemoryAccessException {
        final int index = descriptorsUsed[queue];
        final long buffer = data(queue, index);
        for (int i = 0; i < bytes.length; i++) {
            memoryMap.store(buffer + i, bytes[i], Sizes.SIZE_8_LOG2);
        }
        publish(queue, bytes.length, false);
    }

    private int readDeviceWrite(final int queue, final int usedIndex, final int descriptorIndex)
            throws MemoryAccessException {
        final long usedIdx = memoryMap.load(used(queue) + 2, Sizes.SIZE_16_LOG2) & 0xFFFF;
        assertTrue(usedIdx > usedIndex, "device wrote nothing to queue " + queue);
        return (int) (memoryMap.load(data(queue, descriptorIndex), Sizes.SIZE_8_LOG2) & 0xFF);
    }

    private int usedCount(final int queue) throws MemoryAccessException {
        return (int) (memoryMap.load(used(queue) + 2, Sizes.SIZE_16_LOG2) & 0xFFFF);
    }

    private void storeAddress(final int lowRegister, final int highRegister, final long address) {
        device.store(lowRegister, (int) address, Sizes.SIZE_32_LOG2);
        device.store(highRegister, (int) (address >>> 32), Sizes.SIZE_32_LOG2);
    }

    private void postControlReceiveBuffers(final int count) throws MemoryAccessException {
        for (int i = 0; i < count; i++) {
            publish(VIRTQ_RECEIVE_CONTROL, BUFFER_SIZE, true);
        }
    }

    private void sendControlMessage(final int event, final int id, final int value) throws MemoryAccessException {
        final int index = publish(VIRTQ_TRANSMIT_CONTROL, CONTROL_MESSAGE_SIZE, false);
        final long buffer = data(VIRTQ_TRANSMIT_CONTROL, index);
        memoryMap.store(buffer, id, Sizes.SIZE_32_LOG2);
        memoryMap.store(buffer + 4, event, Sizes.SIZE_16_LOG2);
        memoryMap.store(buffer + 6, value, Sizes.SIZE_16_LOG2);

        device.store(VIRTIO_MMIO_QUEUE_NOTIFY, VIRTQ_TRANSMIT_CONTROL, Sizes.SIZE_32_LOG2);
    }

    private int publish(final int queue, final int length, final boolean deviceWritable) throws MemoryAccessException {
        final int index = descriptorsUsed[queue]++;
        final long descriptor = desc(queue) + (long) index * 16;
        final long buffer = data(queue, index);

        memoryMap.store(descriptor, buffer, Sizes.SIZE_64_LOG2);
        memoryMap.store(descriptor + 8, length, Sizes.SIZE_32_LOG2);
        memoryMap.store(descriptor + 12, deviceWritable ? VIRTQ_DESC_F_WRITE : 0, Sizes.SIZE_16_LOG2);
        memoryMap.store(descriptor + 14, 0, Sizes.SIZE_16_LOG2);

        memoryMap.store(avail(queue) + 4 + (long) index * 2, index, Sizes.SIZE_16_LOG2);
        memoryMap.store(avail(queue) + 2, ++availIndex[queue], Sizes.SIZE_16_LOG2); // idx last
        return index;
    }

    private ControlMessage readControlMessage(final int index) throws MemoryAccessException {
        final long usedIdx = memoryMap.load(used(VIRTQ_RECEIVE_CONTROL) + 2, Sizes.SIZE_16_LOG2) & 0xFFFF;
        if (usedIdx <= index) {
            return null;
        }

        // virtq_used: flags, idx, ring[] of {id: u32, len: u32}
        final long entry = used(VIRTQ_RECEIVE_CONTROL) + 4 + (long) index * 8;
        final int id = (int) memoryMap.load(entry, Sizes.SIZE_32_LOG2);
        final int length = (int) memoryMap.load(entry + 4, Sizes.SIZE_32_LOG2);

        final long buffer = data(VIRTQ_RECEIVE_CONTROL, id);
        final int messageId = (int) memoryMap.load(buffer, Sizes.SIZE_32_LOG2);
        final int event = (int) memoryMap.load(buffer + 4, Sizes.SIZE_16_LOG2) & 0xFFFF;
        final int value = (int) memoryMap.load(buffer + 6, Sizes.SIZE_16_LOG2) & 0xFFFF;

        String payload = null;
        if (length > CONTROL_MESSAGE_SIZE) {
            final byte[] bytes = new byte[length - CONTROL_MESSAGE_SIZE];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) memoryMap.load(buffer + CONTROL_MESSAGE_SIZE + i, Sizes.SIZE_8_LOG2);
            }
            payload = new String(bytes, StandardCharsets.UTF_8);
        }

        return new ControlMessage(messageId, event, value, payload, length);
    }

    private static int receiveQueue(final int port) {
        return port == 0 ? 0 : port * 2 + 2;
    }

    private static int transmitQueue(final int port) {
        return port == 0 ? 1 : port * 2 + 3;
    }
}
