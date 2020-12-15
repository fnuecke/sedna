package li.cil.sedna.api;

/**
 * Constants for different value sizes.
 * <p>
 * These constants are named by their bit width.
 */
public final class Sizes {
    public static final int SIZE_8 = 8;
    public static final int SIZE_16 = 16;
    public static final int SIZE_32 = 32;
    public static final int SIZE_64 = 64;

    public static final int SIZE_8_BYTES = SIZE_8 / Byte.SIZE;
    public static final int SIZE_16_BYTES = SIZE_16 / Byte.SIZE;
    public static final int SIZE_32_BYTES = SIZE_32 / Byte.SIZE;
    public static final int SIZE_64_BYTES = SIZE_64 / Byte.SIZE;

    public static final int SIZE_8_LOG2 = 0;
    public static final int SIZE_16_LOG2 = 1;
    public static final int SIZE_32_LOG2 = 2;
    public static final int SIZE_64_LOG2 = 3;
}
