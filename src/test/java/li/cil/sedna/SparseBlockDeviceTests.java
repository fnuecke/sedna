package li.cil.sedna;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.block.SparseBlockDevice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class SparseBlockDeviceTests {
    private static final int BLOCK_SIZE = 16;
    private static final int DEVICE_SIZE = 64;

    private byte[] array;
    private ByteBufferBlockDevice lower;
    private SparseBlockDevice sparse;

    @BeforeAll
    public static void setup() {
        Sedna.initialize();
    }

    @BeforeEach
    public void setupEach() {
        array = new byte[1024];
        new Random(0xdeadbeef).nextBytes(array);
        final ByteBuffer data = ByteBuffer.wrap(array);
        lower = ByteBufferBlockDevice.wrap(data, true);
        sparse = new SparseBlockDevice(lower);
    }

    @Test
    public void readingReadsUnderlyingBlockDevice() {
        assertEquals(lower.getCapacity(), sparse.getCapacity());
        assertFalse(sparse.isReadonly());

        final byte[] sparseData = new byte[array.length];
        assertDoesNotThrow(() -> sparse.getInputStream().read(sparseData));

        assertArrayEquals(array, sparseData);
    }

    @Test
    public void writingCopiesUnderlyingDataAndStoresNewData() throws IOException {
        final int lowerValue = sparse.getInputStream().read();
        sparse.getOutputStream().write(lowerValue + 1);

        final InputStream lowerStream = lower.getInputStream();
        final InputStream sparseStream = sparse.getInputStream();

        final int lowerRawValue = lowerStream.read();
        final int sparseValue = sparseStream.read();

        assertEquals(lowerValue, lowerRawValue);
        assertNotEquals(lowerRawValue, sparseValue);
        assertEquals((lowerRawValue + 1) & 0xFF, sparseValue);

        final byte[] lowerValues = new byte[array.length - 1];
        final byte[] sparseValues = new byte[array.length - 1];

        lower.getInputStream(1).read(lowerValues);
        sparse.getInputStream(1).read(sparseValues);

        assertArrayEquals(lowerValues, sparseValues);
    }

    @Test
    public void serializationRetainsOverwrittenBlocks() throws IOException {
        final byte[] overwriteData = "something something not random out".getBytes(StandardCharsets.UTF_8);
        sparse.getOutputStream().write(overwriteData);

        final ByteBuffer serialized = BinarySerialization.serialize(sparse);
        final SparseBlockDevice deserialized = BinarySerialization.deserialize(serialized, new SparseBlockDevice(
                lower));

        final byte[] deserializedData = new byte[overwriteData.length];
        deserialized.getInputStream().read(deserializedData);

        assertArrayEquals(overwriteData, deserializedData);
    }

    @Test
    public void multiBlockDeviceHasMoreThanOneShadowBlock() throws IOException {
        final SparseBlockDevice device = multiBlock();
        device.getOutputStream(0).write(1);
        device.getOutputStream(BLOCK_SIZE).write(1);

        assertEquals(2, device.getBlockCount());
    }

    @Test
    public void readStartingInOverwrittenBlockStopsAtBlockBoundary() throws IOException {
        final SparseBlockDevice device = multiBlock();

        final byte[] written = new byte[BLOCK_SIZE];
        Arrays.fill(written, (byte) 0xAA);
        device.getOutputStream(0).write(written);

        // A short read is allowed; it must not run past the block and pick up lower data.
        final byte[] buffer = new byte[BLOCK_SIZE * 2];
        final int read = device.getInputStream(0).read(buffer, 0, buffer.length);

        assertEquals(BLOCK_SIZE, read);
        assertArrayEquals(written, Arrays.copyOf(buffer, BLOCK_SIZE));
    }

    @Test
    public void writesSpanningSeveralBlocksAreStored() throws IOException {
        final SparseBlockDevice device = multiBlock();

        final byte[] written = new byte[BLOCK_SIZE * 3];
        Arrays.fill(written, (byte) 0xCC);
        device.getOutputStream(BLOCK_SIZE / 2).write(written);

        assertArrayEquals(written, readFully(device, BLOCK_SIZE / 2, written.length));
    }

    @Test
    public void skipReturnsNumberOfBytesSkipped() throws IOException {
        final InputStream stream = multiBlock().getInputStream(4);

        assertEquals(8, stream.skip(8));
        assertEquals(0, stream.skip(0));
        assertEquals(0, stream.skip(-1));
    }

    @Test
    public void skipIsClampedToCapacity() throws IOException {
        final SparseBlockDevice device = multiBlock();
        final InputStream stream = device.getInputStream(0);

        assertEquals(DEVICE_SIZE, stream.skip(DEVICE_SIZE * 1000L));
        assertEquals(0, stream.skip(1));
        assertEquals(-1, stream.read());
    }

    @Test
    public void readAfterSkipReadsFromTheSkippedToOffset() throws IOException {
        final InputStream stream = multiBlock().getInputStream(0);

        assertEquals(8, stream.skip(8));
        assertEquals(8, stream.read());

        final byte[] buffer = new byte[4];
        assertEquals(4, stream.read(buffer, 0, 4));
        assertArrayEquals(new byte[]{9, 10, 11, 12}, buffer);
    }

    @Test
    public void skipCrossesIntoOverwrittenBlocks() throws IOException {
        final SparseBlockDevice device = multiBlock();
        device.getOutputStream(BLOCK_SIZE).write(0xAA);

        final InputStream stream = device.getInputStream(0);
        assertEquals(BLOCK_SIZE, stream.skip(BLOCK_SIZE));
        assertEquals(0xAA, stream.read());
    }

    @Test
    public void availableIsClampedToCapacity() throws IOException {
        final InputStream stream = multiBlock().getInputStream(0);

        assertEquals(DEVICE_SIZE, stream.available());
        assertEquals(4, stream.skip(4));
        assertEquals(DEVICE_SIZE - 4, stream.available());
    }

    @Test
    public void singleByteReadOfHighShadowedByteIsUnsigned() throws IOException {
        final SparseBlockDevice device = multiBlock();
        device.getOutputStream(0).write(0xFF);
        device.getOutputStream(1).write(0x80);

        final InputStream stream = device.getInputStream(0);
        assertEquals(0xFF, stream.read());
        assertEquals(0x80, stream.read());
    }

    @Test
    public void singleByteReadOfHighLowerByteIsUnsigned() throws IOException {
        final byte[] data = {(byte) 0xFF, (byte) 0x80};
        final BlockDevice device = new SparseBlockDevice(
                ByteBufferBlockDevice.wrap(ByteBuffer.wrap(data), true), false, BLOCK_SIZE);

        final InputStream stream = device.getInputStream(0);
        assertEquals(0xFF, stream.read());
        assertEquals(0x80, stream.read());
        assertEquals(-1, stream.read());
    }

    @Test
    public void singleByteReadsWalkTheWholeDeviceAcrossBlockBoundaries() throws IOException {
        final SparseBlockDevice device = multiBlock();
        device.getOutputStream(BLOCK_SIZE).write(0xFF);

        final InputStream stream = device.getInputStream(0);
        for (int i = 0; i < DEVICE_SIZE; i++) {
            assertEquals(i == BLOCK_SIZE ? 0xFF : i, stream.read(), "at offset " + i);
        }
        assertEquals(-1, stream.read());
    }

    @Test
    public void sparseDevicesCanBeNested() throws IOException {
        final byte[] data = new byte[DEVICE_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        final BlockDevice base = ByteBufferBlockDevice.wrap(ByteBuffer.wrap(data), true);
        final SparseBlockDevice inner = new SparseBlockDevice(base, false, BLOCK_SIZE);
        final SparseBlockDevice outer = new SparseBlockDevice(inner, false, BLOCK_SIZE);

        inner.getOutputStream(0).write(0x11);
        outer.getOutputStream(BLOCK_SIZE).write(0x22);

        assertEquals(0x11, outer.getInputStream(0).read());
        assertEquals(0x22, outer.getInputStream(BLOCK_SIZE).read());

        final byte[] expected = data.clone();
        expected[0] = 0x11;
        expected[BLOCK_SIZE] = 0x22;
        assertArrayEquals(expected, readFully(outer, 0, DEVICE_SIZE));
    }

    @Test
    public void writingToNestedDeviceDoesNotAffectTheBase() throws IOException {
        final byte[] data = new byte[DEVICE_SIZE];
        final BlockDevice base = ByteBufferBlockDevice.wrap(ByteBuffer.wrap(data), true);
        final SparseBlockDevice device = new SparseBlockDevice(base, false, BLOCK_SIZE);

        final byte[] written = new byte[DEVICE_SIZE];
        Arrays.fill(written, (byte) 0xDD);
        device.getOutputStream(0).write(written);

        assertArrayEquals(new byte[DEVICE_SIZE], readFully(base, 0, DEVICE_SIZE));
        assertArrayEquals(written, readFully(device, 0, DEVICE_SIZE));
    }

    @Test
    public void readonlyDevicesRejectWrites() {
        final SparseBlockDevice device = new SparseBlockDevice(lower, true);

        assertTrue(device.isReadonly());
        assertThrows(UnsupportedOperationException.class, device::getOutputStream);
    }

    @Test
    public void readsPastCapacityReportEndOfStream() throws IOException {
        final SparseBlockDevice device = multiBlock();

        final InputStream atEnd = device.getInputStream(DEVICE_SIZE);
        assertEquals(-1, atEnd.read());
        assertEquals(-1, atEnd.read(new byte[4], 0, 4));
    }

    private static SparseBlockDevice multiBlock() {
        final byte[] data = new byte[DEVICE_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        return new SparseBlockDevice(ByteBufferBlockDevice.wrap(ByteBuffer.wrap(data), true), false, BLOCK_SIZE);
    }

    private static byte[] readFully(final BlockDevice device, final long offset,
                                    final int length) throws IOException {
        final byte[] result = new byte[length];
        final InputStream stream = device.getInputStream(offset);
        int total = 0;
        while (total < length) {
            final int read = stream.read(result, total, length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        assertEquals(length, total);
        return result;
    }
}
