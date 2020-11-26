package li.cil.sedna.api.device.serial;

import java.io.InputStream;

public class SerialDeviceInputStream extends InputStream {
    private final SerialDevice serialDevice;

    public SerialDeviceInputStream(final SerialDevice serialDevice) {
        this.serialDevice = serialDevice;
    }

    @Override
    public int read() {
        return serialDevice.read();
    }
}
