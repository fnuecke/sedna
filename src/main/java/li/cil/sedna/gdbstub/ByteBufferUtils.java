package li.cil.sedna.gdbstub;

import java.nio.ByteBuffer;

public final class ByteBufferUtils {
    public static boolean startsWith(ByteBuffer buffer, ByteBuffer prefix) {
        return buffer.remaining() >= prefix.remaining() && buffer.slice(buffer.position(), prefix.remaining()).equals(prefix);
    }
}
