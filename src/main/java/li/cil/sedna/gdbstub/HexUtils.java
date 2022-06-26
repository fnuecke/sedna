package li.cil.sedna.gdbstub;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.LongBuffer;
import java.util.HexFormat;

public final class HexUtils {
    //If we ever go multithreaded, make this a ThreadLocal
    private static final ByteBuffer byteBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    private static final LongBuffer longBuf = byteBuf.asLongBuffer();

    public static void fromLong(Appendable out, long l) {
        longBuf.put(l);
        HexFormat.of().formatHex(out, byteBuf.array());
        longBuf.clear();
    }

    public static long toLong(CharBuffer buf) {
        while (buf.hasRemaining() && HexFormat.isHexDigit(buf.get()));
        buf.flip();
        buf.limit(buf.limit()-1);
        return Long.parseUnsignedLong(buf, 0, buf.remaining(), 16);
    }
}
