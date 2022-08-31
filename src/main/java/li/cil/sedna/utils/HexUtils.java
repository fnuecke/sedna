package li.cil.sedna.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.HexFormat;

public final class HexUtils {
    //If we ever go multithreaded, make this a ThreadLocal
    private static final ByteBuffer byteBuf = ByteBuffer.allocate(8);
    public static void put64(final Appendable out, final long l) {
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);
        byteBuf.putLong(l);
        HexFormat.of().formatHex(out, byteBuf.array());
        byteBuf.clear();
    }

    public static void put32(final Appendable out, final int i) {
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);
        byteBuf.putInt(i);
        HexFormat.of().formatHex(out, byteBuf.array());
        byteBuf.clear();
    }

    public static void put64BE(final Appendable out, final long l) {
        byteBuf.order(ByteOrder.BIG_ENDIAN);
        byteBuf.putLong(l);
        HexFormat.of().formatHex(out, byteBuf.array());
        byteBuf.clear();
    }

    public static long getVarLengthInt(final CharBuffer buf) {
        while (buf.hasRemaining() && HexFormat.isHexDigit(buf.get())) ;
        buf.flip();
        buf.limit(buf.limit() - 1);
        return Long.parseUnsignedLong(buf, 0, buf.remaining(), 16);
    }
}
