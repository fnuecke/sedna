package li.cil.sedna.utils;

import java.nio.ByteBuffer;

public final class ByteBufferUtils {
    public static boolean startsWith(final ByteBuffer buffer, final ByteBuffer prefix) {
        return buffer.remaining() >= prefix.remaining() && buffer.slice(buffer.position(), prefix.remaining()).equals(prefix);
    }
}
