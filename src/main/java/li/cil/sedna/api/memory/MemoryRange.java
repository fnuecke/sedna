package li.cil.sedna.api.memory;

import java.util.Objects;

/**
 * Represents a range in memory.
 */
public class MemoryRange {
    public static MemoryRange of(final long start, final long end) {
        return new MemoryRange(start, end);
    }

    public static MemoryRange at(final long address, final int length) {
        return new MemoryRange(address, address + length - 1);
    }

    /**
     * The first byte-aligned address inside this memory range (inclusive).
     */
    public final long start;

    /**
     * The last byte-aligned address inside this memory range (inclusive).
     */
    public final long end;

    protected MemoryRange(final long start, final long end) {
        if (Long.compareUnsigned(start, end) > 0) {
            throw new IllegalArgumentException();
        }
        if (Long.compareUnsigned(end - start + 1, 0xFFFFFFFFL) > 0) {
            throw new IllegalArgumentException();
        }

        this.start = start;
        this.end = end;
    }

    /**
     * The address of this memory range.
     * <p>
     * This is the same as {@link #start}.
     *
     * @return the address of this memory range.
     */
    public long address() {
        return start;
    }

    /**
     * The size of this memory range, in bytes.
     *
     * @return the size of this memory range.
     */
    public final int size() {
        return (int) (end - start + 1);
    }

    /**
     * Checks if the specified address is contained within this memory range.
     *
     * @param address the address to check for.
     * @return {@code true} if the address falls into this memory range; {@code false} otherwise.
     */
    public boolean contains(final long address) {
        return Long.compareUnsigned(address, start) >= 0 && Long.compareUnsigned(address, end) <= 0;
    }

    /**
     * Checks if the specified memory range intersects with this memory range.
     *
     * @param other the memory range to check for.
     * @return {@code true} if the memory range intersects this memory range; {@code false} otherwise.
     */
    public boolean intersects(final MemoryRange other) {
        return Long.compareUnsigned(start, other.end) <= 0 && Long.compareUnsigned(end, other.start) >= 0;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final MemoryRange that = (MemoryRange) o;
        return start == that.start &&
               end == that.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override
    public String toString() {
        return String.format("[%x-%x]", start, end);
    }
}
