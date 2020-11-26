package li.cil.sedna.api.device.serial;

import java.io.IOException;
import java.io.OutputStream;

public class SerialDeviceOutputStream extends OutputStream {
    private final SerialDevice serialDevice;

    public SerialDeviceOutputStream(final SerialDevice serialDevice) {
        this.serialDevice = serialDevice;
    }

    @Override
    public void write(final int b) throws IOException {
        if (!serialDevice.canPutByte()) {
            throw new IOException("device is not ready");
        }
        if (serialDevice.canPutByte()) {
            serialDevice.putByte((byte) b);
        }
    }
}
