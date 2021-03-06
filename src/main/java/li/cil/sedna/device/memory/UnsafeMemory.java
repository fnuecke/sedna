package li.cil.sedna.device.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.utils.UnsafeGetter;
import sun.misc.Unsafe;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Tends to be around 10% faster than ByteBufferMemory during regular emulation.
public final class UnsafeMemory extends PhysicalMemory {
    private static final Unsafe UNSAFE = UnsafeGetter.get();

    public static PhysicalMemory create(final int size) {
        if ((size & 0b11) != 0)
            throw new IllegalArgumentException("size must be a multiple of four");

        // Extra padding after original size so we don't have to do size specific
        // bounds checks -- if trying to read something out of bounds it'll just
        // result in bogus, but that's fine.
        final ByteBuffer buffer = ByteBuffer.allocateDirect(size + Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try {
            final Method getAddress = buffer.getClass().getMethod("address");
            getAddress.setAccessible(true);
            final long address = (long) getAddress.invoke(buffer);
            return new UnsafeMemory(buffer, address, size);
        } catch (final Throwable e) {
            return new ByteBufferMemory(buffer);
        }
    }

    private final ByteBuffer buffer;
    private final long address;
    private final long size;

    private UnsafeMemory(final ByteBuffer buffer, final long address, final int size) {
        this.buffer = buffer;
        this.address = address;
        this.size = size;
    }

    public void dispose() {
        try {
            final Method getCleaner = buffer.getClass().getMethod("cleaner");
            getCleaner.setAccessible(true);
            final Object cleaner = getCleaner.invoke(buffer);
            final Method clean = cleaner.getClass().getMethod("clean");
            clean.setAccessible(true);
            clean.invoke(cleaner);
        } catch (final Throwable ignored) {
        }
    }

    @Override
    public int getLength() {
        return (int) size;
    }

    @Override
    public long load(final int offset, final int sizeLog2) throws MemoryAccessException {
        if (offset < 0 || offset >= size) {
            throw new MemoryAccessException();
        }
        switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2:
                return UNSAFE.getByte(address + offset);
            case Sizes.SIZE_16_LOG2:
                return UNSAFE.getShort(address + offset);
            case Sizes.SIZE_32_LOG2:
                return UNSAFE.getInt(address + offset);
            case Sizes.SIZE_64_LOG2:
                return UNSAFE.getLong(address + offset);
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) throws MemoryAccessException {
        if (offset < 0 || offset >= size) {
            throw new MemoryAccessException();
        }
        switch (sizeLog2) {
            case Sizes.SIZE_8_LOG2:
                UNSAFE.putByte(address + offset, (byte) value);
                break;
            case Sizes.SIZE_16_LOG2:
                UNSAFE.putShort(address + offset, (short) value);
                break;
            case Sizes.SIZE_32_LOG2:
                UNSAFE.putInt(address + offset, (int) value);
                break;
            case Sizes.SIZE_64_LOG2:
                UNSAFE.putLong(address + offset, value);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public void load(int offset, final ByteBuffer dst) throws MemoryAccessException {
        if (offset < 0 || offset > size - dst.remaining()) {
            throw new MemoryAccessException();
        }
        while (dst.hasRemaining()) {
            dst.put(UNSAFE.getByte(address + offset++));
        }
    }

    @Override
    public void store(int offset, final ByteBuffer src) throws MemoryAccessException {
        if (offset < 0 || offset > size - src.remaining()) {
            throw new MemoryAccessException();
        }
        while (src.hasRemaining()) {
            UNSAFE.putByte(address + offset++, src.get());
        }
    }
}
