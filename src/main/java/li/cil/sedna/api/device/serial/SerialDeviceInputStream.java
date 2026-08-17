package li.cil.sedna.api.device.serial;

import java.io.InputStream;
import java.util.Objects;

public class SerialDeviceInputStream extends InputStream {
    private final SerialDevice serialDevice;

    public SerialDeviceInputStream(final SerialDevice serialDevice) {
        this.serialDevice = serialDevice;
    }

    @Override
    public int read() {
        return serialDevice.read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }

        final int count = serialDevice.read(b, off, len);
        return count > 0 ? count : -1;
    }
}
