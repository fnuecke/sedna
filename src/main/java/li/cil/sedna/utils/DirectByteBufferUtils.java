package li.cil.sedna.utils;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

public final class DirectByteBufferUtils {
    private static final Unsafe UNSAFE = UnsafeGetter.get();

    public static void release(final ByteBuffer buffer) {
        try {
            UNSAFE.invokeCleaner(buffer);
        } catch (final Throwable ignored) {
        }
    }

    public static long getAddress(final ByteBuffer buffer) {
        if (UNSAFE == null || !buffer.isDirect()) {
            return 0;
        }
        try {
            final Field addressField = Buffer.class.getDeclaredField("address");
            return UNSAFE.getLong(buffer, UNSAFE.objectFieldOffset(addressField));
        } catch (final Throwable e) {
            return 0;
        }
    }
}
