package li.cil.sedna;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.block.SparseBlockDevice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class SparseBlockDeviceTests {
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
        lower = new ByteBufferBlockDevice(data, true);
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
        assertEquals((byte) (lowerRawValue + 1), sparseValue);

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
        final SparseBlockDevice deserialized = BinarySerialization.deserialize(serialized, new SparseBlockDevice(lower));

        final byte[] deserializedData = new byte[overwriteData.length];
        deserialized.getInputStream().read(deserializedData);

        assertArrayEquals(overwriteData, deserializedData);
    }
}
