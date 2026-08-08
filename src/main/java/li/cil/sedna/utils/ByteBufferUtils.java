package li.cil.sedna.utils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

public final class ByteBufferUtils {
    public static final class TokenException extends Exception {
        public TokenException(String message, Throwable cause){
            super(message, cause);
        }
    }

    public static boolean startsWith(final ByteBuffer buffer, final ByteBuffer prefix) {
        return buffer.remaining() >= prefix.remaining() && buffer.slice(buffer.position(), prefix.remaining()).equals(prefix);
    }

    public static ByteBuffer getToken(final ByteBuffer buf, byte delimeter) throws TokenException {
        ByteBuffer token = buf.slice();
        int len = 0;
        try {
            while (buf.get() != delimeter) len++;
            token.limit(len);
            return token;
        } catch (BufferUnderflowException ex) {
            throw new TokenException("Buffer missing delimeter '%c'".formatted((char)delimeter), ex);
        }
    }

    public static CharBuffer tokenAsChar(final ByteBuffer buf) {
        return StandardCharsets.US_ASCII.decode(buf);
    }

    public static CharBuffer getCharToken(final ByteBuffer buf, byte delimeter) throws TokenException {
        return tokenAsChar(getToken(buf, delimeter));
    }

    public static String tokenAsString(final ByteBuffer buf) {
        return tokenAsChar(buf).toString();
    }

    public static String getStringToken(final ByteBuffer buf, byte delimeter) throws TokenException {
        return getCharToken(buf, delimeter).toString();
    }
}
