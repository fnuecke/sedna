package li.cil.sedna.gdbstub;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Not threadsafe
 */
public final class GDBPacketOutputStream extends FilterOutputStream {
    private byte checksum = 0;
    private boolean closed = false;

    public GDBPacketOutputStream(final OutputStream out) throws IOException {
        super(out);
        out.write('$');
    }

    @Override
    public void write(final int b) throws IOException {
        if (closed) throw new IOException("Stream Closed");
        super.write(b);
        checksum += b;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        out.write('#');
        out.write(HexFormat.of().toHexDigits(checksum).getBytes(StandardCharsets.US_ASCII));
        super.flush();
    }
}
