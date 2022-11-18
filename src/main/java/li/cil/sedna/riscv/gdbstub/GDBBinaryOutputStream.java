package li.cil.sedna.riscv.gdbstub;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A FilterOutputStream that escapes raw binary for gdb transport, as described in the
 * <a href="https://sourceware.org/gdb/onlinedocs/gdb/Overview.html#Binary-Data">GDB docs</a>
 */
public final class GDBBinaryOutputStream extends FilterOutputStream {
    public GDBBinaryOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(int i) throws IOException {
        byte b = (byte) i;
        switch (b) {
            case '#', '$', '}', '*' -> {
                out.write('}');
                out.write(b ^ 0x20);
            }
            default -> out.write(b);
        }
    }
}
