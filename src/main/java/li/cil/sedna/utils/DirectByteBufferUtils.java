package li.cil.sedna.utils;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public final class DirectByteBufferUtils {
    public static void release(final ByteBuffer buffer) {
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
}
