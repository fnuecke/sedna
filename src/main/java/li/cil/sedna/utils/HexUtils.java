package li.cil.sedna.utils;

import java.nio.CharBuffer;
import java.util.HexFormat;

public final class HexUtils {
    public static void putRegister(final Appendable out, final long value, final int size) {
        for (int i = 0; i < size; i++) {
            HexFormat.of().toHexDigits(out, (byte) (value >>> (i * 8)));
        }
    }

    public static long getVarLengthInt(final CharBuffer buf) {
        while (buf.hasRemaining() && HexFormat.isHexDigit(buf.get())) ;
        buf.flip();
        buf.limit(buf.limit() - 1);
        return Long.parseUnsignedLong(buf, 0, buf.remaining(), 16);
    }
}
