package li.cil.sedna.riscv;

import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.memory.Memory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class R5BoardDeviceTreeTests {
    private static final int MEMORY_SIZE = 4 * 1024 * 1024;
    private static final int FIRMWARE_SIZE = 16 * 1024;

    @BeforeAll
    public static void initialize() {
        Sedna.initialize();
    }

    @Test
    public void firmwareSizePublishesReservedMemory() throws Exception {
        final Map<String, Map<String, byte[]>> tree = buildDeviceTree(FIRMWARE_SIZE);

        final Map<String, byte[]> reserved = tree.get("/reserved-memory");
        assertNotNull(reserved, "no reserved-memory node");
        assertArrayEquals(cells(2), reserved.get("#address-cells"));
        assertArrayEquals(cells(2), reserved.get("#size-cells"));
        assertArrayEquals(new byte[0], reserved.get("ranges"));

        final Map<String, byte[]> firmware = tree.get("/reserved-memory/firmware@80000000");
        assertNotNull(firmware, "no firmware node");
        assertArrayEquals(cells(0, 0x80000000, 0, FIRMWARE_SIZE), firmware.get("reg"));
    }

    @Test
    public void noFirmwareSizeReservesNothing() throws Exception {
        assertNull(buildDeviceTree(0).get("/reserved-memory"));
    }

    // --------------------------------------------------------------------- //

    private static byte[] cells(final long... values) {
        final ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
        for (final long value : values) {
            buffer.putInt((int) value);
        }
        return buffer.array();
    }

    /**
     * Boots a minimal board and parses the device tree it wrote to memory into a map of absolute
     * node path to properties.
     */
    private static Map<String, Map<String, byte[]>> buildDeviceTree(final int firmwareSize) throws MemoryAccessException {
        final R5Board board = new R5Board();
        final PhysicalMemory memory = Memory.create(MEMORY_SIZE);
        board.addDevice(board.getDefaultProgramStart(), memory);
        board.setFirmwareSize(firmwareSize);
        board.reset();
        board.initialize();

        // The device tree sits at the aligned top of memory; grab the whole tail.
        final byte[] data = new byte[64 * 1024];
        final long start = board.getDefaultProgramStart() + MEMORY_SIZE - data.length;
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) board.getMemoryMap().load(start + i, Sizes.SIZE_8_LOG2);
        }
        return parse(data);
    }

    private static final int FDT_MAGIC = 0xD00DFEED;
    private static final int FDT_BEGIN_NODE = 1;
    private static final int FDT_END_NODE = 2;
    private static final int FDT_PROP = 3;
    private static final int FDT_NOP = 4;

    private static Map<String, Map<String, byte[]>> parse(final byte[] data) {
        final ByteBuffer buffer = ByteBuffer.wrap(data);
        int base = 0;
        while (buffer.getInt(base) != FDT_MAGIC) {
            base += 4;
        }

        final int structure = base + buffer.getInt(base + 8);
        final int strings = base + buffer.getInt(base + 12);

        final Map<String, Map<String, byte[]>> nodes = new HashMap<>();
        final List<String> path = new ArrayList<>();
        Map<String, byte[]> current = null;
        int offset = structure;
        for (; ; ) {
            final int token = buffer.getInt(offset);
            offset += 4;
            if (token == FDT_BEGIN_NODE) {
                final String name = string(data, offset);
                offset += (name.length() + 4) & ~3;
                path.add(name);
                current = new HashMap<>();
                nodes.put("/" + String.join("/", path.subList(1, path.size())), current);
            } else if (token == FDT_END_NODE) {
                path.remove(path.size() - 1);
                current = null;
            } else if (token == FDT_PROP) {
                final int length = buffer.getInt(offset);
                final String name = string(data, strings + buffer.getInt(offset + 4));
                final byte[] value = new byte[length];
                System.arraycopy(data, offset + 8, value, 0, length);
                offset += 8 + ((length + 3) & ~3);
                current.put(name, value);
            } else if (token != FDT_NOP) {
                return nodes;
            }
        }
    }

    private static String string(final byte[] data, final int offset) {
        int end = offset;
        while (data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
