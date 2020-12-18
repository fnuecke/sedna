package li.cil.sedna.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class ByteBufferInputStream extends InputStream {
    private final ByteBuffer buffer;

    public ByteBufferInputStream(final ByteBuffer buffer) {
        this.buffer = buffer.slice();
    }

    @Override
    public int read() {
        if (!buffer.hasRemaining()) {
            return -1;
        }
        return buffer.get();
    }

    @Override
    public int read(final byte[] b, final int off, int len) {
        len = Math.min(len, buffer.remaining());
        if (len == 0) {
            return -1;
        }

        buffer.get(b, off, len);

        return len;
    }

    @Override
    public long skip(final long n) throws IOException {
        final int newPosition = (int) Math.min(buffer.position() + n, buffer.limit());
        final int skipped = newPosition - buffer.position();
        buffer.position(newPosition);
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return buffer.remaining();
    }
}
