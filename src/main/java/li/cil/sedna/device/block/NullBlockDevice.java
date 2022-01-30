package li.cil.sedna.device.block;

import li.cil.sedna.api.device.BlockDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class NullBlockDevice implements BlockDevice {
    private static final NullBlockDevice READONLY_INSTANCE = new NullBlockDevice(true);
    private static final NullBlockDevice WRITABLE_INSTANCE = new NullBlockDevice(false);

    public static NullBlockDevice get(final boolean readonly) {
        return readonly ? READONLY_INSTANCE : WRITABLE_INSTANCE;
    }

    private final boolean readonly;

    public NullBlockDevice(final boolean readonly) {
        this.readonly = readonly;
    }

    @Override
    public boolean isReadonly() {
        return readonly;
    }

    @Override
    public long getCapacity() {
        return 0;
    }

    @Override
    public InputStream getInputStream(final long offset) {
        return NullInputStream.INSTANCE;
    }

    @Override
    public OutputStream getOutputStream(final long offset) {
        throw new UnsupportedOperationException();
    }

    private static final class NullInputStream extends InputStream {
        public static final NullInputStream INSTANCE = new NullInputStream();

        @Override
        public int read() throws IOException {
            return -1;
        }
    }
}
