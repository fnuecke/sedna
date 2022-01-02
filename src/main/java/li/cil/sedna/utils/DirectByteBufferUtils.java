package li.cil.sedna.utils;

import sun.misc.Unsafe;

import java.nio.ByteBuffer;

public final class DirectByteBufferUtils {
    private static final Unsafe UNSAFE = UnsafeGetter.get();

    public static void release(final ByteBuffer buffer) {
        try {
            UNSAFE.invokeCleaner(buffer);
        } catch (final Throwable ignored) {
        }
    }
}
