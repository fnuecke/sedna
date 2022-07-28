package li.cil.sedna.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.LongBuffer;
import java.util.HexFormat;

public final class HexUtils {
    //If we ever go multithreaded, make this a ThreadLocal
    private static final ByteBuffer byteBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    private static final ByteBuffer byteBufBE = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    private static final LongBuffer longBuf = byteBuf.asLongBuffer();
    private static final LongBuffer longBufBE = byteBufBE.asLongBuffer();

    public static void putLong(final Appendable out, final long l) {
        longBuf.put(l);
        HexFormat.of().formatHex(out, byteBuf.array());
        longBuf.clear();
    }

    public static void putLongBE(final Appendable out, final long l) {
        longBufBE.put(l);
        HexFormat.of().formatHex(out, byteBufBE.array());
        longBufBE.clear();
    }

    public static long getLong(final CharBuffer buf) {
        while (buf.hasRemaining() && HexFormat.isHexDigit(buf.get())) ;
        buf.flip();
        buf.limit(buf.limit() - 1);
        return Long.parseUnsignedLong(buf, 0, buf.remaining(), 16);
    }
}
