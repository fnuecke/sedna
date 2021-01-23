package li.cil.sedna.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MemoryMaps {
    /**
     * Computes the size of continuous physical memory starting at and including the specified address.
     *
     * @param memory  the memory map the address is local to.
     * @param address the address to start at.
     * @return the size of the continuous memory range.
     */
    public static int getContinuousMemorySize(final MemoryMap memory, final long address) {
        final MemoryRange range = memory.getMemoryRange(address);
        if (range == null || !(range.device instanceof PhysicalMemory)) {
            return 0;
        }

        return range.size() + getContinuousMemorySize(memory, range.end + 1);
    }

    /**
     * Block-copies data from a {@link MemoryMap} into the specified range of the specified array.
     *
     * @param memory  the memory map to copy from.
     * @param address the address in memory to copy from.
     * @param dst     the array to copy into.
     * @param offset  the offset into the array to start copying to.
     * @param length  the number of bytes to copy.
     * @throws MemoryAccessException when an exception is thrown while accessing a device.
     */
    public static void load(final MemoryMap memory, final long address, final byte[] dst, final int offset, final int length) throws MemoryAccessException {
        final ByteBuffer buffer = ByteBuffer.wrap(dst, offset, length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        load(memory, address, buffer);
    }

    /**
     * Block-copies data from a {@link MemoryMap} into the specified buffer.
     * <p>
     * Buffers using {@link ByteOrder#LITTLE_ENDIAN} order may perform faster.
     *
     * @param memory  the memory map to copy from.
     * @param address the address in memory to copy from.
     * @param dst     the buffer to copy into.
     * @throws MemoryAccessException    when an exception is thrown while accessing a device.
     * @throws IllegalArgumentException if the buffer is not little-endian.
     */
    public static void load(final MemoryMap memory, long address, final ByteBuffer dst) throws MemoryAccessException {
        while (dst.hasRemaining()) {
            final MemoryRange range = memory.getMemoryRange(address);
            if (range == null) {
                address++;
                dst.put((byte) 0);
                continue;
            }

            final int offset = (int) (address - range.start);
            final int length = Math.min((int) (range.end - address + 1), dst.remaining());
            if (length <= 0) {
                throw new AssertionError();
            }

            load(range.device, offset, length, dst);
            address += length;
        }
    }

    /**
     * Block-copies data to a {@link MemoryMap} from the specified range of the specified array.
     *
     * @param memory  the memory map to copy to.
     * @param address the address in memory to copy to.
     * @param src     the array to copy from.
     * @param offset  the offset into the array to start copying from.
     * @param length  the number of bytes to copy.
     * @throws MemoryAccessException when an exception is thrown while accessing a device.
     */
    public static void store(final MemoryMap memory, final long address, final byte[] src, final int offset, final int length) throws MemoryAccessException {
        final ByteBuffer buffer = ByteBuffer.wrap(src, offset, length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        store(memory, address, buffer);
    }

    /**
     * Block-copies data to a {@link MemoryMap} from the specified buffer.
     * <p>
     * Buffers using {@link ByteOrder#LITTLE_ENDIAN} order may perform faster.
     *
     * @param memory  the memory map to copy to.
     * @param address the address in memory to copy to.
     * @param src     the buffer to copy from.
     * @throws MemoryAccessException    when an exception is thrown while accessing a device.
     * @throws IllegalArgumentException if the buffer is not little-endian.
     */
    public static void store(final MemoryMap memory, long address, final ByteBuffer src) throws MemoryAccessException {
        while (src.hasRemaining()) {
            final MemoryRange range = memory.getMemoryRange(address);
            if (range == null) {
                address++;
                src.position(src.position() + 1);
                continue;
            }

            final int offset = (int) (address - range.start);
            final int length = Math.min((int) (range.end - address + 1), src.remaining());
            if (length <= 0) {
                throw new AssertionError();
            }

            store(range.device, offset, length, src);
            address += length;
        }
    }

    public static void store(final MemoryMap memory, long address, final InputStream stream) throws IOException {
        final byte[] array = new byte[64 * 1024];
        final ByteBuffer buffer = ByteBuffer.wrap(array);
        for (; ; ) {
            final MemoryRange range = memory.getMemoryRange(address);
            if (range == null) {
                address++;
                if (stream.read() < 0) {
                    break;
                }
                continue;
            }

            final int offset = (int) (address - range.start);
            final int maxReadCount = Math.min((int) (range.end - address + 1), array.length);

            final int readCount = stream.read(array, 0, maxReadCount);
            if (readCount < 0) {
                break;
            }

            buffer.clear();
            store(range.device, offset, readCount, buffer);
            address += readCount;
        }
    }

    private static void load(final MemoryMappedDevice device, final int offset, final int length, final ByteBuffer dst) throws MemoryAccessException {
        if (device instanceof PhysicalMemory) {
            final int limit = dst.limit();
            dst.limit(dst.position() + length);
            ((PhysicalMemory) device).load(offset, dst);
            dst.limit(limit);
        } else {
            loadSlow(device, offset, length, dst);
        }
    }

    private static void store(final MemoryMappedDevice device, final int offset, final int length, final ByteBuffer src) throws MemoryAccessException {
        if (device instanceof PhysicalMemory) {
            final int limit = src.limit();
            src.limit(src.position() + length);
            ((PhysicalMemory) device).store(offset, src);
            src.limit(limit);
        } else {
            storeSlow(device, offset, length, src);
        }
    }

    private static void loadSlow(final MemoryMappedDevice device, int offset, final int length, final ByteBuffer dst) throws MemoryAccessException {
        final int end = offset + length;

        if (dst.order() == ByteOrder.LITTLE_ENDIAN && (device.getSupportedSizes() & (1 << Sizes.SIZE_32_LOG2)) != 0) {
            while (offset < end - 3) {
                dst.putInt((int) device.load(offset, Sizes.SIZE_32_LOG2));
                offset += 4;
            }
        }

        while (offset < end) {
            dst.put((byte) device.load(offset, Sizes.SIZE_8_LOG2));
            offset++;
        }
    }

    private static void storeSlow(final MemoryMappedDevice device, int offset, final int length, final ByteBuffer src) throws MemoryAccessException {
        final int end = offset + length;

        if (src.order() == ByteOrder.LITTLE_ENDIAN && (device.getSupportedSizes() & (1 << Sizes.SIZE_32_LOG2)) != 0) {
            while (offset < end - 3) {
                device.store(offset, src.getInt(), Sizes.SIZE_32_LOG2);
                offset += 4;
            }
        }

        while (offset < end) {
            device.store(offset, src.get(), Sizes.SIZE_8_LOG2);
            offset++;
        }
    }
}
