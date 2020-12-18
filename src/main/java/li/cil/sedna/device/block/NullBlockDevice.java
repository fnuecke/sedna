package li.cil.sedna.device.block;

import li.cil.sedna.api.device.BlockDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class NullBlockDevice implements BlockDevice {
    public static final NullBlockDevice INSTANCE = new NullBlockDevice();

    @Override
    public boolean isReadonly() {
        return true;
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
