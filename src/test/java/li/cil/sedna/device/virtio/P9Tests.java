package li.cil.sedna.device.virtio;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class P9Tests {
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_QUEUE_SEL = 0x030;
    private static final int VIRTIO_MMIO_QUEUE_NUM = 0x038;
    private static final int VIRTIO_MMIO_QUEUE_READY = 0x044;
    private static final int VIRTIO_MMIO_QUEUE_NOTIFY = 0x050;
    private static final int VIRTIO_MMIO_STATUS = 0x070;
    private static final int VIRTIO_MMIO_QUEUE_DESC_LOW = 0x080;
    private static final int VIRTIO_MMIO_QUEUE_DESC_HIGH = 0x084;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_LOW = 0x090;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_HIGH = 0x094;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_LOW = 0x0A0;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_HIGH = 0x0A4;

    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1 << 0;

    private static final int VIRTQ_DESC_F_NEXT = 1;
    private static final int VIRTQ_DESC_F_WRITE = 2;

    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 1024 * 1024;

    private static final long DESC = PHYSICAL_MEMORY_START + 0x1000;
    private static final long AVAIL = PHYSICAL_MEMORY_START + 0x3000;
    private static final long USED = PHYSICAL_MEMORY_START + 0x5000;
    private static final long REQUEST = PHYSICAL_MEMORY_START + 0x8000;
    private static final long REPLY = PHYSICAL_MEMORY_START + 0xC000;

    private static final int QUEUE_SIZE = 256;
    private static final int REPLY_CAPACITY = 8 * 1024;

    // 9P message ids. Replies are always the request id plus one.
    private static final byte P9_TLERROR = 6;
    private static final byte P9_TSTATFS = 8;
    private static final byte P9_TLOPEN = 12;
    private static final byte P9_TGETATTR = 24;
    private static final byte P9_TREADDIR = 40;
    private static final byte P9_TVERSION = 100;
    private static final byte P9_TATTACH = 104;
    private static final byte P9_TWALK = 110;
    private static final byte P9_TREAD = 116;
    private static final byte P9_TCLUNK = 120;


    private static final int LINUX_ERRNO_ENOENT = 2;

    private static final int ROOT_FID = 0;
    private static final int FILE_FID = 1;

    @TempDir
    java.nio.file.Path tempDir;

    private MemoryMap memoryMap;
    private VirtIOFileSystemDevice device;
    private short availIdx;

    @BeforeAll
    public static void setUpAll() {
        Sedna.initialize();
    }

    @BeforeEach
    public void setUp() throws IOException {
        final java.nio.file.Path exported = Files.createDirectory(tempDir.resolve("exported"));
        Files.write(exported.resolve("greeting"), "hello 9p".getBytes(StandardCharsets.US_ASCII));
        Files.createDirectory(exported.resolve("subdir"));

        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));
        device = new VirtIOFileSystemDevice(memoryMap, "test", new HostFileSystem(exported.toFile()));
        availIdx = 0;

        bringUpQueue();
    }

    @Test
    public void versionHandshakeNegotiates9P2000L() throws Exception {
        final ByteBuffer reply = request(P9_TVERSION, 0, body -> {
            body.putInt(8 * 1024); // msize
            putString(body, "9P2000.L");
        });

        assertEquals(P9_TVERSION + 1, reply.get(4), "reply id must be the request id plus one");
        reply.position(7);
        assertEquals(8 * 1024, reply.getInt(), "server must not offer more than the client asked for");
        assertEquals("9P2000.L", getString(reply));
    }

    @Test
    public void attachWalkOpenReadReturnsFileContents() throws Exception {
        attachRoot();

        // Walk from the root fid to "greeting" under a new fid.
        final ByteBuffer walkReply = request(P9_TWALK, 3, body -> {
            body.putInt(ROOT_FID);
            body.putInt(FILE_FID);
            body.putShort((short) 1);
            putString(body, "greeting");
        });
        assertEquals(P9_TWALK + 1, walkReply.get(4), "walk must succeed");
        walkReply.position(7);
        assertEquals(1, walkReply.getShort(), "one path element was walked");

        final ByteBuffer openReply = request(P9_TLOPEN, 4, body -> {
            body.putInt(FILE_FID);
            body.putInt(0); // O_RDONLY
        });
        assertEquals(P9_TLOPEN + 1, openReply.get(4), "open must succeed");

        final ByteBuffer readReply = request(P9_TREAD, 5, body -> {
            body.putInt(FILE_FID);
            body.putLong(0); // offset
            body.putInt(64); // count
        });
        assertEquals(P9_TREAD + 1, readReply.get(4), "read must succeed");
        readReply.position(7);
        final int count = readReply.getInt();
        final byte[] data = new byte[count];
        readReply.get(data);
        assertEquals("hello 9p", new String(data, StandardCharsets.US_ASCII));
    }

    @Test
    public void walkingToAMissingFirstElementIsAnError() throws Exception {
        attachRoot();

        final ByteBuffer reply = request(P9_TWALK, 3, body -> {
            body.putInt(ROOT_FID);
            body.putInt(FILE_FID);
            body.putShort((short) 1);
            putString(body, "absent");
        });

        assertEquals(P9_TLERROR + 1, reply.get(4), "walking to a missing first element must fail");
        reply.position(7);
        assertEquals(LINUX_ERRNO_ENOENT, reply.getInt(), "a missing name must report ENOENT");
    }

    @Test
    public void walkingToAMissingLaterElementReturnsAShortWalk() throws Exception {
        attachRoot();

        final ByteBuffer reply = request(P9_TWALK, 3, body -> {
            body.putInt(ROOT_FID);
            body.putInt(FILE_FID);
            body.putShort((short) 2);
            putString(body, "subdir");
            putString(body, "absent");
        });

        assertEquals(P9_TWALK + 1, reply.get(4), "a partial walk must still succeed");
        reply.position(7);
        assertEquals(1, reply.getShort(), "only the first element was walked");
    }

    @Test
    public void statfsValidatesTheFid() throws Exception {
        attachRoot();

        final ByteBuffer reply = request(P9_TSTATFS, 3, body -> body.putInt(0x4242));

        assertEquals(P9_TLERROR + 1, reply.get(4), "statfs must reject a fid it never handed out");
    }

    @Test
    public void readingAnUnknownFidIsAnError() throws Exception {
        attachRoot();

        final ByteBuffer reply = request(P9_TREAD, 3, body -> {
            body.putInt(0x4242); // never established
            body.putLong(0);
            body.putInt(16);
        });

        assertEquals(P9_TLERROR + 1, reply.get(4), "an unknown fid must produce an error reply");
    }

    @Test
    public void statfsReportsTheBackingFileSystem() throws Exception {
        attachRoot();

        final ByteBuffer reply = request(P9_TSTATFS, 3, body -> body.putInt(ROOT_FID));

        assertEquals(P9_TSTATFS + 1, reply.get(4));
        reply.position(7);
        reply.getInt(); // type
        assertTrue(reply.getInt() > 0, "block size must be positive");
    }

    @Test
    public void getattrReportsADirectoryForTheRoot() throws Exception {
        attachRoot();

        final ByteBuffer reply = request(P9_TGETATTR, 3, body -> {
            body.putInt(ROOT_FID);
            body.putLong(0x000007FFL); // request everything
        });

        assertEquals(P9_TGETATTR + 1, reply.get(4));
        reply.position(7);
        reply.getLong(); // valid mask
        reply.get();     // qid.type
        reply.getInt();  // qid.version
        reply.getLong(); // qid.path
        final int mode = reply.getInt();
        assertEquals(0x4000, mode & 0xF000, "the root must report as a directory");
    }

    @Test
    public void readdirListsDirectoryEntries() throws Exception {
        attachRoot();

        final ByteBuffer openReply = request(P9_TLOPEN, 3, body -> {
            body.putInt(ROOT_FID);
            body.putInt(0);
        });
        assertEquals(P9_TLOPEN + 1, openReply.get(4), "opening the root directory must succeed");

        final ByteBuffer reply = request(P9_TREADDIR, 4, body -> {
            body.putInt(ROOT_FID);
            body.putLong(0);
            body.putInt(2048);
        });

        assertEquals(P9_TREADDIR + 1, reply.get(4));
        reply.position(7);
        final int count = reply.getInt();
        assertTrue(count > 0, "the exported directory is not empty");

        final StringBuilder names = new StringBuilder();
        final int end = 11 + count;
        while (reply.position() < end) {
            reply.get();      // qid.type
            reply.getInt();   // qid.version
            reply.getLong();  // qid.path
            reply.getLong();  // offset
            reply.get();      // d_type
            names.append(getString(reply)).append(' ');
        }
        assertTrue(names.toString().contains("greeting"), "expected 'greeting' in " + names);
        assertTrue(names.toString().contains("subdir"), "expected 'subdir' in " + names);
    }

    @Test
    public void clunkReleasesTheFid() throws Exception {
        attachRoot();

        final ByteBuffer clunkReply = request(P9_TCLUNK, 3, body -> body.putInt(ROOT_FID));
        assertEquals(P9_TCLUNK + 1, clunkReply.get(4));

        final ByteBuffer reply = request(P9_TGETATTR, 4, body -> {
            body.putInt(ROOT_FID);
            body.putLong(0x000007FFL);
        });
        assertEquals(P9_TLERROR + 1, reply.get(4), "the fid must be gone after clunk");
    }

    @Test
    public void fidTableSerializationIsUnchanged() throws Exception {
        attachRoot();
        request(P9_TWALK, 3, body -> {
            body.putInt(ROOT_FID);
            body.putInt(FILE_FID);
            body.putShort((short) 1);
            putString(body, "greeting");
        });

        final ByteBuffer data = BinarySerialization.serialize(device);
        final byte[] bytes = new byte[data.remaining()];
        data.get(bytes);

        assertEquals(EXPECTED_SERIALIZED_SIZE, bytes.length,
            "the serialized size of the 9P device changed, which means the savestate format changed");
        assertEquals(EXPECTED_SERIALIZED_DIGEST, toHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            "the serialized bytes of the 9P device changed, which means the savestate format changed");
    }

    private static final int EXPECTED_SERIALIZED_SIZE = 173;
    private static final String EXPECTED_SERIALIZED_DIGEST =
        "10e55178f9eb5d68beb86d9365a905430a63e9992e5cb6dc4f088ef27beb1f44";

    private static String toHex(final byte[] value) {
        final StringBuilder sb = new StringBuilder(value.length * 2);
        for (final byte b : value) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void attachRoot() throws Exception {
        request(P9_TVERSION, 0, body -> {
            body.putInt(8 * 1024);
            putString(body, "9P2000.L");
        });
        final ByteBuffer reply = request(P9_TATTACH, 1, body -> {
            body.putInt(ROOT_FID);
            body.putInt(-1); // afid, NOFID
            putString(body, "root"); // uname
            putString(body, "");     // aname
            body.putInt(0);          // n_uname
        });
        assertEquals(P9_TATTACH + 1, reply.get(4), "attach must succeed");
    }

    private interface BodyWriter {
        void write(ByteBuffer body);
    }

    private ByteBuffer request(final byte messageId, final int tag, final BodyWriter body) throws MemoryAccessException {
        final ByteBuffer message = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(0); // size, patched below
        message.put(messageId);
        message.putShort((short) tag);
        body.write(message);
        message.putInt(0, message.position());
        message.flip();

        final int length = message.remaining();
        for (int i = 0; i < length; i++) {
            memoryMap.store(REQUEST + i, message.get(i), Sizes.SIZE_8_LOG2);
        }

        // Two descriptors: the request we just wrote, then space for the reply.
        writeDescriptor(0, REQUEST, length, VIRTQ_DESC_F_NEXT, 1);
        writeDescriptor(1, REPLY, REPLY_CAPACITY, VIRTQ_DESC_F_WRITE, 0);

        memoryMap.store(AVAIL + 4 + (availIdx & (QUEUE_SIZE - 1)) * 2L, 0, Sizes.SIZE_16_LOG2);
        availIdx++;
        memoryMap.store(AVAIL + 2, availIdx, Sizes.SIZE_16_LOG2);

        device.store(VIRTIO_MMIO_QUEUE_NOTIFY, 0, Sizes.SIZE_32_LOG2);
        device.step(1_000_000);

        final ByteBuffer reply = ByteBuffer.allocate(REPLY_CAPACITY).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < REPLY_CAPACITY; i++) {
            reply.put(i, (byte) memoryMap.load(REPLY + i, Sizes.SIZE_8_LOG2));
        }
        final int replyLength = reply.getInt(0);
        assertTrue(replyLength >= 7 && replyLength <= REPLY_CAPACITY,
            "device must write a framed reply, got length " + replyLength);
        reply.limit(replyLength);
        return reply;
    }

    private void writeDescriptor(final int index, final long buffer, final int length, final int flags, final int next) throws MemoryAccessException {
        final long descriptor = DESC + (long) index * 16;
        memoryMap.store(descriptor, buffer, Sizes.SIZE_64_LOG2);
        memoryMap.store(descriptor + 8, length, Sizes.SIZE_32_LOG2);
        memoryMap.store(descriptor + 12, flags, Sizes.SIZE_16_LOG2);
        memoryMap.store(descriptor + 14, next, Sizes.SIZE_16_LOG2);
    }

    private static void putString(final ByteBuffer buffer, final String value) {
        buffer.putShort((short) value.length());
        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String getString(final ByteBuffer buffer) {
        final int length = buffer.getShort() & 0xFFFF;
        final byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private void bringUpQueue() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_QUEUE_SEL, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_QUEUE_NUM, QUEUE_SIZE, Sizes.SIZE_32_LOG2);
        storeAddress(VIRTIO_MMIO_QUEUE_DESC_LOW, VIRTIO_MMIO_QUEUE_DESC_HIGH, DESC);
        storeAddress(VIRTIO_MMIO_QUEUE_DRIVER_LOW, VIRTIO_MMIO_QUEUE_DRIVER_HIGH, AVAIL);
        storeAddress(VIRTIO_MMIO_QUEUE_DEVICE_LOW, VIRTIO_MMIO_QUEUE_DEVICE_HIGH, USED);
        device.store(VIRTIO_MMIO_QUEUE_READY, 1, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER_OK, Sizes.SIZE_32_LOG2);
    }

    private void storeAddress(final int lowRegister, final int highRegister, final long address) {
        device.store(lowRegister, (int) address, Sizes.SIZE_32_LOG2);
        device.store(highRegister, (int) (address >>> 32), Sizes.SIZE_32_LOG2);
    }
}
